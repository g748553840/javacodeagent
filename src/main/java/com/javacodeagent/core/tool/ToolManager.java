package com.javacodeagent.core.tool;

import com.javacodeagent.config.AgentConfig;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.hook.HookContext;
import com.javacodeagent.core.hook.HookManager;
import com.javacodeagent.core.hook.HookResult;
import com.javacodeagent.core.hook.HookType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolDefinition;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.permission.PermissionService;
import com.javacodeagent.piagent.tool.AbortSignal;
import com.javacodeagent.piagent.tool.AbortedException;
import com.javacodeagent.piagent.tool.ToolBatchObserver;
import com.javacodeagent.piagent.tool.ToolBatchResult;
import com.javacodeagent.piagent.tool.ToolExecutionMode;
import com.javacodeagent.piagent.tool.ToolUpdateCallback;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolManager {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final List<Tool> toolBeans;
    private final PermissionService permissionService;
    private final HookManager hookManager;
    private final AgentConfig agentConfig;

    @PostConstruct
    public void init() {
        for (Tool tool : toolBeans) {
            registerTool(tool);
        }
    }

    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {}", tool.getName());
    }

    public List<ToolDefinition> getAllToolDefinitions() {
        return tools.values().stream()
            .map(Tool::toDefinition)
            .toList();
    }

    /**
     * 查询工具是否可用
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    // -------------------------------------------------------------------------
    // 单个工具调用
    // -------------------------------------------------------------------------

    public ToolExecutionResult executeToolCall(ToolCall toolCall, ExecutionContext context) {
        return executeToolCall(toolCall, context, AbortSignal.NEVER, null);
    }

    /**
     * 执行工具调用，支持中止与流式进度。
     *
     * @param signal   中止信号，传 {@link AbortSignal#NEVER} 表示不可中止
     * @param onUpdate 流式进度回调，可为 null
     */
    public ToolExecutionResult executeToolCall(ToolCall toolCall,
                                               ExecutionContext context,
                                               AbortSignal signal,
                                               ToolUpdateCallback onUpdate) {
        PreparedCall prepared = prepare(toolCall, context);
        if (prepared.isImmediate()) {
            return prepared.immediate();
        }

        // 阻塞工具（如 BashTool：process.waitFor()）必须在弹性线程池中运行，
        // 不能占用 Netty IO 事件循环线程，否则在响应式管道中会导致死锁。
        // 批量入口不需要这层包装——它已整体调度到 boundedElastic。
        if (prepared.tool().isBlocking()) {
            return Mono.fromCallable(() -> runPrepared(prepared, context, signal, onUpdate))
                .subscribeOn(Schedulers.boundedElastic())
                .block();
        }
        return runPrepared(prepared, context, signal, onUpdate);
    }

    // -------------------------------------------------------------------------
    // 批量工具调用：三阶段模型
    // -------------------------------------------------------------------------

    /**
     * 执行一批工具调用（对应 pi 的 {@code executeToolCallsParallel}）。
     *
     * <p><b>三个阶段各自解决一个问题：</b>
     *
     * <p><b>阶段 1 — 串行准备</b>：工具查找、权限判定、参数适配、{@code PRE_TOOL_CALL} 钩子。
     * 必须串行，因为权限审批天然是有序的交互：并行发起两次审批，用户看到的弹窗顺序
     * 就取决于线程调度，无法复现，也无法在「拒绝第一个」后跳过第二个。
     *
     * <p><b>阶段 2 — 并行执行</b>：真正跑工具。只有这一段是并发的，也只有这一段
     * 值得并发——读 5 个文件、跑 3 个 grep 的耗时是可以叠加省掉的。
     *
     * <p><b>阶段 3 — 按声明顺序发射</b>：结果必须按 assistant 声明 tool_use 的顺序排列，
     * 与完成先后无关。{@code flatMapSequential} 直接给出这个语义：并发订阅、顺序发射。
     * 顺序错乱不只是观感问题——Anthropic API 要求 tool_result 与 tool_use 一一对应，
     * 乱序会让模型把结果归错工具。
     *
     * <p><b>失败与中止不会打断批次</b>：任何一个工具失败、被拒绝或被中止，都仍然
     * 产出一条对应的 tool_result 消息。这是硬性要求——缺失任何一条，
     * 下一轮请求就会因 tool_use 无配对结果而被 API 拒绝，整轮对话卡死。
     *
     * @param observer 生命周期观察者，传 {@link ToolBatchObserver#NOOP} 表示不关心
     */
    public Mono<ToolBatchResult> executeBatch(List<ToolCall> toolCalls,
                                              ExecutionContext context,
                                              AbortSignal signal,
                                              ToolBatchObserver observer) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(new ToolBatchResult(List.of(), List.of(), false));
        }
        ToolBatchObserver effectiveObserver = observer != null ? observer : ToolBatchObserver.NOOP;
        AbortSignal effectiveSignal = signal != null ? signal : AbortSignal.NEVER;

        // 阶段 1 串行准备。权限检查与钩子都是阻塞调用，整体放到 boundedElastic。
        return Mono.fromCallable(() -> {
                List<PreparedCall> prepared = new ArrayList<>(toolCalls.size());
                for (ToolCall toolCall : toolCalls) {
                    prepared.add(prepare(toolCall, context));
                }
                return prepared;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(prepared -> {
                int concurrency = resolveConcurrency(prepared);
                log.debug("Executing tool batch of {} with concurrency {}", prepared.size(), concurrency);

                // 阶段 2 并行执行 + 阶段 3 顺序发射，由 flatMapSequential 一并完成
                return Flux.fromIterable(prepared)
                    .flatMapSequential(
                        p -> Mono.fromCallable(
                                () -> runOne(p, context, effectiveSignal, effectiveObserver))
                            .subscribeOn(Schedulers.boundedElastic()),
                        concurrency)
                    .collectList()
                    .map(results -> toBatchResult(toolCalls, results));
            });
    }

    /** 执行批次中的一项：立即失败的直接返回，其余真正调用工具。 */
    private ToolExecutionResult runOne(PreparedCall prepared,
                                       ExecutionContext context,
                                       AbortSignal signal,
                                       ToolBatchObserver observer) {
        if (prepared.isImmediate()) {
            // 未通过准备阶段的调用不发 onStart——UI 上不该出现「开始了却没结束」的条目
            observer.onComplete(prepared.call(), prepared.immediate());
            return prepared.immediate();
        }

        observer.onStart(prepared.call());
        ToolCall call = prepared.call();
        ToolExecutionResult result = runPrepared(
            prepared, context, signal, partial -> observer.onUpdate(call, partial));
        observer.onComplete(call, result);
        return result;
    }

    /**
     * 决定这一批的并发度。
     *
     * <p>「有任意一个 SEQUENTIAL 就整批串行」——这条规则来自 pi，看起来粗暴，
     * 但替代方案（串行的串行跑、并行的并行跑）要回答一个没有好答案的问题：
     * 串行工具与并行工具之间要不要加屏障？不加则串行工具照样和别人并发，
     * 声明形同虚设；加了则等于整批串行，只是绕了一圈。所以直接退化最诚实。
     */
    private int resolveConcurrency(List<PreparedCall> prepared) {
        AgentConfig.ToolExecution config = agentConfig.getTool();
        if (!config.isParallelEnabled()) {
            return 1;
        }
        boolean anySequential = prepared.stream()
            .filter(p -> !p.isImmediate())
            .anyMatch(p -> p.tool().getExecutionMode() == ToolExecutionMode.SEQUENTIAL);
        if (anySequential) {
            return 1;
        }
        return Math.max(1, Math.min(config.getMaxParallelism(), prepared.size()));
    }

    /** 阶段 3：把结果按声明顺序包装成 tool_result 消息。 */
    private ToolBatchResult toBatchResult(List<ToolCall> toolCalls, List<ToolExecutionResult> results) {
        List<Message> messages = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            messages.add(Message.builder()
                .type(MessageType.TOOL_RESULT)
                .content(renderForLlm(results.get(i)))
                .toolCallId(toolCalls.get(i).getId())
                .build());
        }
        return new ToolBatchResult(
            List.copyOf(messages),
            List.copyOf(results),
            ToolBatchResult.shouldTerminate(results));
    }

    /**
     * 把执行结果渲染成发给 LLM 的文本。
     *
     * <p>失败时必须带上 {@code Error:} 前缀而不是留空——模型需要知道这一步没成功，
     * 否则它会把空结果当成「执行了但没有输出」继续往下推。
     */
    public static String renderForLlm(ToolExecutionResult result) {
        if (result.isSuccess()) {
            return result.getContent() != null ? result.getContent() : "";
        }
        return "Error: " + (result.getError() != null ? result.getError() : "unknown error");
    }

    // -------------------------------------------------------------------------
    // 准备与执行
    // -------------------------------------------------------------------------

    /**
     * 准备阶段的产物：要么是一个可以直接执行的调用，要么是一个已经确定的失败结果。
     *
     * @param immediate 非 null 表示准备阶段就已失败（工具不存在 / 权限不足 / 钩子拦截），
     *                  此时 {@code tool} 与 {@code input} 可能为 null
     */
    private record PreparedCall(ToolCall call,
                                Tool tool,
                                Map<String, Object> input,
                                ToolExecutionResult immediate) {

        boolean isImmediate() {
            return immediate != null;
        }

        static PreparedCall failed(ToolCall call, ToolExecutionResult result) {
            return new PreparedCall(call, null, null, result);
        }

        static PreparedCall ready(ToolCall call, Tool tool, Map<String, Object> input) {
            return new PreparedCall(call, tool, input, null);
        }
    }

    /** 阶段 1：工具查找、权限判定、参数适配、前置钩子。不执行工具本身。 */
    private PreparedCall prepare(ToolCall toolCall, ExecutionContext context) {
        Tool tool = tools.get(toolCall.getName());
        if (tool == null) {
            return PreparedCall.failed(toolCall,
                ToolExecutionResult.error("Tool not found: " + toolCall.getName()));
        }

        // 权限检查
        if (tool.requiresPermission()) {
            String userId = context.getUserId() != null ? context.getUserId() : "default";
            PermissionType requiredPermission = tool.getRequiredPermission();

            if (requiredPermission != null) {
                // ExecutionContext.permissionLevel 优先（ExploreAgent / 隔离 Agent 设置的 READ_ONLY 等）
                // 若为 null 则退化为 PermissionService 的用户级配置
                boolean allowed = (context.getPermissionLevel() != null)
                    ? permissionService.checkPermissionLevel(context.getPermissionLevel(), requiredPermission)
                    : permissionService.checkPermission(userId, requiredPermission);

                if (!allowed) {
                    log.warn("Permission denied for tool {}: {} required (level={})",
                        tool.getName(), requiredPermission, context.getPermissionLevel());
                    hookManager.triggerHook(HookType.PERMISSION_DENIED, HookContext.builder()
                        .type(HookType.PERMISSION_DENIED)
                        .userId(userId)
                        .conversationId(context.getConversationId())
                        .data(Map.of(
                            "toolName", tool.getName(),
                            "reason", requiredPermission + " required for " + tool.getName()
                        ))
                        .build());
                    return PreparedCall.failed(toolCall, ToolExecutionResult.error(
                        "Permission denied: " + requiredPermission + " required for tool " + tool.getName()
                    ));
                }
            }
        }

        // 参数适配：让工具有机会把 LLM 传来的旧格式参数转成自己期望的形态
        Map<String, Object> rawInput = toolCall.getInput() != null ? toolCall.getInput() : Map.of();
        Map<String, Object> input;
        try {
            Map<String, Object> prepared = tool.prepareArguments(rawInput);
            input = prepared != null ? prepared : rawInput;
        } catch (Exception e) {
            log.warn("prepareArguments failed for tool {}, using raw input", tool.getName(), e);
            input = rawInput;
        }

        // Pre-tool-call Hook
        HookContext preHookContext = HookContext.builder()
            .type(HookType.PRE_TOOL_CALL)
            .userId(context.getUserId())
            .conversationId(context.getConversationId())
            .data(Map.of("toolName", tool.getName(), "input", input))
            .build();
        HookResult preHookResult = hookManager.triggerHook(HookType.PRE_TOOL_CALL, preHookContext);
        if (!preHookResult.shouldContinue()) {
            return PreparedCall.failed(toolCall, ToolExecutionResult.error(
                "Tool execution rejected by hook: " + preHookResult.getMessage()));
        }

        return PreparedCall.ready(toolCall, tool, input);
    }

    /**
     * 阶段 2 的同步内核：执行一个已准备好的调用。
     *
     * <p>调用方负责把它放到合适的线程上——批量入口整体调度到 boundedElastic，
     * 单次入口只对 {@link Tool#isBlocking()} 的工具做调度。
     */
    private ToolExecutionResult runPrepared(PreparedCall prepared,
                                            ExecutionContext context,
                                            AbortSignal signal,
                                            ToolUpdateCallback onUpdate) {
        Tool tool = prepared.tool();
        AbortSignal effectiveSignal = signal != null ? signal : AbortSignal.NEVER;

        // 执行前先看一眼是否已被中止，避免做无用功。
        // 串行批次中这一检查尤其重要：中止后剩余工具会在这里快速返回，
        // 而不是逐个跑完——但每个仍然产出结果，保持与 tool_use 的配对。
        if (effectiveSignal.isAborted()) {
            return ToolExecutionResult.error("Tool execution aborted before start: " + tool.getName());
        }

        try {
            ToolExecutionResult result = tool.execute(
                prepared.input(), context, effectiveSignal, onUpdate);
            if (result == null) {
                result = ToolExecutionResult.error("Tool returned null result");
            }

            // Post-tool-call Hook
            HookContext postHookContext = HookContext.builder()
                .type(HookType.POST_TOOL_CALL)
                .userId(context.getUserId())
                .conversationId(context.getConversationId())
                .data(Map.of("toolName", tool.getName(), "result", result.isSuccess() ? "success" : "failed"))
                .build();
            hookManager.triggerHook(HookType.POST_TOOL_CALL, postHookContext);

            return result;
        } catch (Exception e) {
            // 中止是预期路径而非故障，单独识别以免污染错误日志
            if (isAbortion(e)) {
                log.info("Tool {} aborted by signal", tool.getName());
                return ToolExecutionResult.error("Tool execution aborted: " + tool.getName());
            }
            log.error("Tool execution failed: {}", tool.getName(), e);
            return ToolExecutionResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    /** 识别中止异常，包括被 Reactor 包装后藏在 cause 链里的情形。 */
    private boolean isAbortion(Throwable e) {
        Throwable cursor = e;
        int depth = 0;
        while (cursor != null && depth < 5) {
            if (cursor instanceof AbortedException) {
                return true;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }
}

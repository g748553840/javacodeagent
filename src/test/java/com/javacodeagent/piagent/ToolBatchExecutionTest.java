package com.javacodeagent.piagent;

import com.javacodeagent.config.AgentConfig;
import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.hook.HookManager;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.permission.PermissionService;
import com.javacodeagent.core.tool.Tool;
import com.javacodeagent.core.tool.ToolManager;
import com.javacodeagent.piagent.tool.AbortSignal;
import com.javacodeagent.piagent.tool.ToolBatchObserver;
import com.javacodeagent.piagent.tool.ToolBatchResult;
import com.javacodeagent.piagent.tool.ToolExecutionMode;
import com.javacodeagent.piagent.tool.ToolUpdateCallback;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ToolManager#executeBatch} 三阶段模型的单元测试。
 *
 * <p>这里验证的四件事各自对应一类真实故障：
 * <ul>
 *   <li><b>顺序</b>——结果乱序会让模型把输出归错工具，且 Anthropic API 要求
 *       tool_result 与 tool_use 一一对应</li>
 *   <li><b>并发确实发生</b>——用闭锁证明，而不是比较耗时（后者在 CI 上不稳定）</li>
 *   <li><b>SEQUENTIAL 确实退化整批</b>——否则声明形同虚设</li>
 *   <li><b>失败/中止仍配对</b>——缺一条 tool_result，下一轮请求就会被 API 拒绝，
 *       整个对话卡死，这是最难排查的一类问题</li>
 * </ul>
 */
class ToolBatchExecutionTest {

    private static final ExecutionContext CTX = ExecutionContext.builder()
        .userId("tester")
        .conversationId("conv-1")
        .build();

    // -------------------------------------------------------------------------
    // 测试替身
    // -------------------------------------------------------------------------

    /** 可配置延迟、执行模式与失败行为的测试工具。 */
    private static class RecordingTool implements Tool {
        private final String name;
        private final long delayMillis;
        private final ToolExecutionMode mode;
        private final AtomicInteger concurrentNow;
        private final AtomicInteger concurrentPeak;
        private final CountDownLatch rendezvous;
        private final boolean throwOnExecute;
        private final boolean terminate;

        RecordingTool(String name, long delayMillis, ToolExecutionMode mode,
                      AtomicInteger concurrentNow, AtomicInteger concurrentPeak,
                      CountDownLatch rendezvous, boolean throwOnExecute, boolean terminate) {
            this.name = name;
            this.delayMillis = delayMillis;
            this.mode = mode;
            this.concurrentNow = concurrentNow;
            this.concurrentPeak = concurrentPeak;
            this.rendezvous = rendezvous;
            this.throwOnExecute = throwOnExecute;
            this.terminate = terminate;
        }

        static RecordingTool simple(String name) {
            return new RecordingTool(name, 0, null, null, null, null, false, false);
        }

        static RecordingTool delayed(String name, long millis) {
            return new RecordingTool(name, millis, null, null, null, null, false, false);
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "test tool " + name; }
        @Override public Map<String, Object> getParameterSchema() { return Map.of("type", "object"); }
        @Override public ToolExecutionMode getExecutionMode() { return mode; }

        @Override
        public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
            if (concurrentNow != null) {
                int now = concurrentNow.incrementAndGet();
                concurrentPeak.updateAndGet(peak -> Math.max(peak, now));
            }
            try {
                if (rendezvous != null) {
                    // 只有真正并行时所有工具才能同时到达这里，闭锁才会归零。
                    // 串行执行时第一个工具会在 await 上超时，测试随即失败。
                    rendezvous.countDown();
                    boolean allArrived = rendezvous.await(3, TimeUnit.SECONDS);
                    if (!allArrived) {
                        return ToolExecutionResult.error(name + " timed out waiting for peers");
                    }
                }
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                if (throwOnExecute) {
                    throw new IllegalStateException("boom from " + name);
                }
                return terminate
                    ? ToolExecutionResult.successAndTerminate("done:" + name)
                    : ToolExecutionResult.success("done:" + name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolExecutionResult.error("interrupted");
            } finally {
                if (concurrentNow != null) {
                    concurrentNow.decrementAndGet();
                }
            }
        }
    }

    /** 会被权限系统拒绝的工具。 */
    private static class PermissionedTool implements Tool {
        @Override public String getName() { return "Restricted"; }
        @Override public String getDescription() { return "needs permission"; }
        @Override public Map<String, Object> getParameterSchema() { return Map.of("type", "object"); }
        @Override public boolean requiresPermission() { return true; }
        @Override public PermissionType getRequiredPermission() { return PermissionType.FILE_WRITE; }

        @Override
        public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
            throw new AssertionError("must not run: permission should have been denied in phase 1");
        }
    }

    /** 每 10ms 推一次进度的工具，用于验证 onUpdate 打通到观察者。 */
    private static class StreamingTool implements Tool {
        @Override public String getName() { return "Streamer"; }
        @Override public String getDescription() { return "emits progress"; }
        @Override public Map<String, Object> getParameterSchema() { return Map.of("type", "object"); }

        @Override
        public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
            return ToolExecutionResult.success("no streaming");
        }

        @Override
        public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context,
                                           AbortSignal signal, ToolUpdateCallback onUpdate) {
            for (int i = 0; i < 3; i++) {
                if (onUpdate != null) {
                    onUpdate.update(Map.of("output", "line " + i));
                }
            }
            return ToolExecutionResult.success("streamed");
        }
    }

    // -------------------------------------------------------------------------
    // 装配
    // -------------------------------------------------------------------------

    private ToolManager managerFor(List<Tool> tools, boolean parallelEnabled, int maxParallelism) {
        AgentConfig config = new AgentConfig();
        config.getTool().setParallelEnabled(parallelEnabled);
        config.getTool().setMaxParallelism(maxParallelism);
        ToolManager manager = new ToolManager(
            tools, mock(PermissionService.class), new HookManager(), config);
        manager.init();
        return manager;
    }

    private ToolManager managerFor(List<Tool> tools) {
        return managerFor(tools, true, 4);
    }

    private static ToolCall call(String name) {
        return ToolCall.builder().name(name).id("id-" + name).input(Map.of()).build();
    }

    // -------------------------------------------------------------------------
    // 阶段 3：顺序
    // -------------------------------------------------------------------------

    @Test
    void resultsFollowDeclarationOrderNotCompletionOrder() {
        // 声明顺序 Slow → Medium → Fast，完成顺序恰好相反
        ToolManager manager = managerFor(List.of(
            RecordingTool.delayed("Slow", 150),
            RecordingTool.delayed("Medium", 80),
            RecordingTool.delayed("Fast", 0)));

        ToolBatchResult batch = manager.executeBatch(
            List.of(call("Slow"), call("Medium"), call("Fast")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(batch).isNotNull();
        assertThat(batch.results())
            .extracting(ToolExecutionResult::getContent)
            .containsExactly("done:Slow", "done:Medium", "done:Fast");
        assertThat(batch.messages())
            .extracting(Message::getToolCallId)
            .containsExactly("id-Slow", "id-Medium", "id-Fast");
    }

    // -------------------------------------------------------------------------
    // 阶段 2：并发
    // -------------------------------------------------------------------------

    @Test
    void independentToolsRunConcurrently() {
        // 三个工具在闭锁上互相等待：只有同时在跑才能全部通过
        CountDownLatch rendezvous = new CountDownLatch(3);
        List<Tool> tools = List.of(
            new RecordingTool("A", 0, null, null, null, rendezvous, false, false),
            new RecordingTool("B", 0, null, null, null, rendezvous, false, false),
            new RecordingTool("C", 0, null, null, null, rendezvous, false, false));

        ToolBatchResult batch = managerFor(tools).executeBatch(
            List.of(call("A"), call("B"), call("C")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(batch).isNotNull();
        assertThat(batch.results()).allSatisfy(r ->
            assertThat(r.isSuccess())
                .as("all three must be in flight simultaneously")
                .isTrue());
    }

    @Test
    void oneSequentialToolForcesTheWholeBatchSerial() {
        AtomicInteger now = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Tool> tools = List.of(
            new RecordingTool("Par1", 30, null, now, peak, null, false, false),
            new RecordingTool("Seq", 30, ToolExecutionMode.SEQUENTIAL, now, peak, null, false, false),
            new RecordingTool("Par2", 30, null, now, peak, null, false, false));

        managerFor(tools).executeBatch(
            List.of(call("Par1"), call("Seq"), call("Par2")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(peak.get())
            .as("a single SEQUENTIAL tool degrades the entire batch, "
              + "including the tools that did not ask for it")
            .isEqualTo(1);
    }

    @Test
    void parallelismCanBeDisabledGlobally() {
        AtomicInteger now = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Tool> tools = List.of(
            new RecordingTool("A", 30, null, now, peak, null, false, false),
            new RecordingTool("B", 30, null, now, peak, null, false, false));

        managerFor(tools, false, 4).executeBatch(
            List.of(call("A"), call("B")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    void concurrencyIsCappedByMaxParallelism() {
        AtomicInteger now = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Tool> tools = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tools.add(new RecordingTool("T" + i, 60, null, now, peak, null, false, false));
            calls.add(call("T" + i));
        }

        managerFor(tools, true, 2).executeBatch(
            calls, CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 配对不变量：任何失败路径都必须产出 tool_result
    // -------------------------------------------------------------------------

    @Test
    void throwingToolStillProducesAPairedResultMessage() {
        ToolManager manager = managerFor(List.of(
            RecordingTool.simple("Ok"),
            new RecordingTool("Boom", 0, null, null, null, null, true, false)));

        ToolBatchResult batch = manager.executeBatch(
            List.of(call("Ok"), call("Boom")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(batch).isNotNull();
        assertThat(batch.messages()).hasSize(2);
        assertThat(batch.messages())
            .extracting(Message::getToolCallId)
            .containsExactly("id-Ok", "id-Boom");
        assertThat(batch.results().get(1).isSuccess()).isFalse();
        assertThat(batch.messages().get(1).getContent())
            .as("failure must be visible to the model, not an empty string")
            .startsWith("Error:")
            .contains("boom from Boom");
    }

    @Test
    void unknownToolStillProducesAPairedResultMessage() {
        ToolManager manager = managerFor(List.of(RecordingTool.simple("Ok")));

        ToolBatchResult batch = manager.executeBatch(
            List.of(call("Ok"), call("NoSuchTool")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(batch).isNotNull();
        assertThat(batch.messages()).hasSize(2);
        assertThat(batch.messages().get(1).getToolCallId()).isEqualTo("id-NoSuchTool");
        assertThat(batch.messages().get(1).getContent()).contains("Tool not found");
    }

    @Test
    void permissionDeniedToolIsRejectedInPhaseOneAndNeverExecuted() {
        // PermissionService 的 mock 默认返回 false，等同于拒绝。
        // PermissionedTool.execute 一旦被调用就会抛 AssertionError。
        ToolManager manager = managerFor(List.of(new PermissionedTool(), RecordingTool.simple("Ok")));

        ToolBatchResult batch = manager.executeBatch(
            List.of(call("Restricted"), call("Ok")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(batch).isNotNull();
        assertThat(batch.messages()).hasSize(2);
        assertThat(batch.messages().get(0).getContent()).contains("Permission denied");
        assertThat(batch.results().get(1).isSuccess()).isTrue();
    }

    @Test
    void abortedSignalShortCircuitsRemainingToolsButKeepsThemPaired() {
        AbortSignal signal = new AbortSignal();
        signal.abort("user pressed stop");

        ToolManager manager = managerFor(List.of(
            RecordingTool.simple("A"), RecordingTool.simple("B")));

        ToolBatchResult batch = manager.executeBatch(
            List.of(call("A"), call("B")), CTX, signal, ToolBatchObserver.NOOP).block();

        assertThat(batch).isNotNull();
        assertThat(batch.messages()).hasSize(2);
        assertThat(batch.results()).allSatisfy(r -> assertThat(r.isSuccess()).isFalse());
        assertThat(batch.messages().get(0).getContent()).contains("aborted before start");
    }

    @Test
    void emptyBatchIsHandledWithoutTouchingTools() {
        ToolBatchResult batch = managerFor(List.of(RecordingTool.simple("Ok")))
            .executeBatch(List.of(), CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(batch).isNotNull();
        assertThat(batch.messages()).isEmpty();
        assertThat(batch.terminate())
            .as("an empty batch has nobody asking to stop")
            .isFalse();
    }

    // -------------------------------------------------------------------------
    // terminate 的「全部一致」原则
    // -------------------------------------------------------------------------

    @Test
    void batchTerminatesOnlyWhenEveryToolAgrees() {
        ToolManager manager = managerFor(List.of(
            new RecordingTool("Stopper", 0, null, null, null, null, false, true),
            RecordingTool.simple("Reader")));

        ToolBatchResult mixed = manager.executeBatch(
            List.of(call("Stopper"), call("Reader")),
            CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(mixed).isNotNull();
        assertThat(mixed.terminate())
            .as("Reader's output has not reached the model yet; stopping here would waste it")
            .isFalse();

        ToolBatchResult unanimous = manager.executeBatch(
            List.of(call("Stopper")), CTX, AbortSignal.NEVER, ToolBatchObserver.NOOP).block();

        assertThat(unanimous).isNotNull();
        assertThat(unanimous.terminate()).isTrue();
    }

    @Test
    void shouldTerminateIsFalseForEmptyAndForAnyDissent() {
        assertThat(ToolBatchResult.shouldTerminate(null)).isFalse();
        assertThat(ToolBatchResult.shouldTerminate(List.of())).isFalse();
        assertThat(ToolBatchResult.shouldTerminate(List.of(
            ToolExecutionResult.successAndTerminate("a"),
            ToolExecutionResult.success("b")))).isFalse();
        assertThat(ToolBatchResult.shouldTerminate(List.of(
            ToolExecutionResult.successAndTerminate("a"),
            ToolExecutionResult.successAndTerminate("b")))).isTrue();
    }

    // -------------------------------------------------------------------------
    // 观察者
    // -------------------------------------------------------------------------

    @Test
    void observerSeesStartAndCompleteForExecutedToolsAndProgressFromStreamingOnes() {
        List<String> events = new CopyOnWriteArrayList<>();
        ToolBatchObserver observer = new ToolBatchObserver() {
            @Override public void onStart(ToolCall c) { events.add("start:" + c.getName()); }
            @Override public void onUpdate(ToolCall c, Map<String, Object> p) {
                events.add("update:" + c.getName() + ":" + p.get("output"));
            }
            @Override public void onComplete(ToolCall c, ToolExecutionResult r) {
                events.add("complete:" + c.getName());
            }
        };

        managerFor(List.of(new StreamingTool())).executeBatch(
            List.of(call("Streamer")), CTX, AbortSignal.NEVER, observer).block();

        assertThat(events).containsExactly(
            "start:Streamer",
            "update:Streamer:line 0",
            "update:Streamer:line 1",
            "update:Streamer:line 2",
            "complete:Streamer");
    }

    @Test
    void rejectedToolReportsCompleteWithoutStart() {
        List<String> events = new CopyOnWriteArrayList<>();
        ToolBatchObserver observer = new ToolBatchObserver() {
            @Override public void onStart(ToolCall c) { events.add("start:" + c.getName()); }
            @Override public void onComplete(ToolCall c, ToolExecutionResult r) {
                events.add("complete:" + c.getName());
            }
        };

        managerFor(List.of(RecordingTool.simple("Ok"))).executeBatch(
            List.of(call("Ghost")), CTX, AbortSignal.NEVER, observer).block();

        assertThat(events)
            .as("a tool that never ran must not appear as 'started' in the UI")
            .containsExactly("complete:Ghost");
    }
}

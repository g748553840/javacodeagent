package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.plan.Plan;
import com.javacodeagent.core.plan.PlanResult;
import com.javacodeagent.core.plan.PlanService;
import com.javacodeagent.core.plan.PlanStep;
import com.javacodeagent.core.tool.Tool;
import com.javacodeagent.piagent.tool.ToolExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 退出计划模式：把拟好的计划提交给用户审阅，并让 Agent 停下来等待批准。
 *
 * <p>这是目前唯一返回 {@code terminate=true} 的工具，也是这个字段存在的理由。
 * 其他工具执行完都希望模型接着往下想；这个工具恰恰相反——计划已经写完，
 * 下一步该由人决定批不批，模型继续自主行动反而是错的（它会直接开始改代码，
 * 而"先看计划再动手"正是用户要求计划模式的原因）。
 *
 * <p>注意 {@code terminate} 的「全部一致」语义：如果模型在同一批里既调了
 * 本工具又调了 {@code Read}，循环<b>不会</b>停——因为 Read 的结果还没被模型看到，
 * 停在这里等于白读。这是刻意的，见
 * {@link com.javacodeagent.piagent.tool.ToolBatchResult#shouldTerminate}。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.tool.exit-plan-mode-enabled",
                       havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExitPlanModeTool implements Tool {

    private final PlanService planService;

    @Override
    public String getName() {
        return "ExitPlanMode";
    }

    @Override
    public String getDescription() {
        return "Submit the implementation plan you have written for user approval and stop. "
             + "Use this only after you have finished exploring and the plan is complete. "
             + "Execution halts here: the user reviews the plan and approves or rejects it "
             + "before any code is written.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> planProp = new HashMap<>();
        planProp.put("type", "string");
        planProp.put("description", "One-paragraph summary of what the plan achieves");
        properties.put("plan", planProp);

        Map<String, Object> stepsProp = new HashMap<>();
        stepsProp.put("type", "array");
        stepsProp.put("items", Map.of("type", "string"));
        stepsProp.put("description", "Ordered implementation steps, one sentence each");
        properties.put("steps", stepsProp);

        Map<String, Object> planIdProp = new HashMap<>();
        planIdProp.put("type", "string");
        planIdProp.put("description",
            "Existing plan id to submit. Omit to create a new plan for this conversation.");
        properties.put("planId", planIdProp);

        schema.put("properties", properties);
        schema.put("required", List.of("plan", "steps"));
        return schema;
    }

    /**
     * 串行执行。
     *
     * <p>提交计划会推进 {@link PlanService} 里的状态机（DRAFT → IN_REVIEW）。
     * 更实际的原因是：本工具一旦成功就会终止整个循环，让它与同批其他工具
     * 并发执行，会出现"循环已决定停止、另一个工具还在写文件"的窗口。
     */
    @Override
    public ToolExecutionMode getExecutionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String planText = input.get("plan") instanceof String s ? s.trim() : "";
        if (planText.isEmpty()) {
            return ToolExecutionResult.error("'plan' is required and must be a non-empty string");
        }

        List<String> steps = extractSteps(input.get("steps"));
        if (steps.isEmpty()) {
            // 退化而不是报错：模型偶尔会把全部内容塞进 plan 而不填 steps。
            // 直接失败会让它重试一次同样的调用；把计划正文当作单一步骤，
            // 用户拿到的信息完全一样，只是少了拆解。
            log.debug("ExitPlanMode called without steps; using the plan text as a single step");
            steps = List.of(planText);
        }

        Plan plan = resolvePlan(input.get("planId"), context.getConversationId(), planText);
        if (plan == null) {
            return ToolExecutionResult.error(
                "Plan not found: " + input.get("planId"));
        }

        // 重新提交时先清空旧步骤，否则会与新步骤叠加成一份重复的计划
        plan.getSteps().clear();
        for (int i = 0; i < steps.size(); i++) {
            planService.addStep(plan.getId(), PlanStep.builder()
                .order(i + 1)
                .description(steps.get(i))
                .build());
        }

        PlanResult result = planService.submitForReview(plan.getId());
        if (!result.isSuccess()) {
            return ToolExecutionResult.error(result.getError());
        }

        log.info("Plan {} submitted for review; halting agent loop", plan.getId());

        // metadata 让前端能直接拿到 planId 去调 /plan/{id}/approve，
        // 不必从输出文本里正则抠一个 UUID 出来
        return ToolExecutionResult.builder()
            .content(result.getOutput())
            .success(true)
            .terminate(true)
            .status(com.javacodeagent.core.enums.ToolStatus.COMPLETED)
            .metadata(Map.of(
                "planId", plan.getId(),
                "stepCount", steps.size(),
                "awaitingApproval", true))
            .build();
    }

    /** 指定了 planId 就用它，否则复用会话里尚未批准的计划，再没有就新建。 */
    private Plan resolvePlan(Object planIdInput, String conversationId, String planText) {
        if (planIdInput instanceof String planId && !planId.isBlank()) {
            return planService.getPlan(planId.trim());
        }

        if (conversationId != null) {
            Plan reusable = planService.getPlansByConversation(conversationId).stream()
                .filter(p -> p.getStatus() == Plan.PlanStatus.DRAFT
                          || p.getStatus() == Plan.PlanStatus.IN_REVIEW)
                .reduce((first, second) -> second)   // 取最近一个
                .orElse(null);
            if (reusable != null) {
                return reusable;
            }
        }

        PlanResult created = planService.enterPlanMode(conversationId, planText);
        return created.getPlan();
    }

    private List<String> extractSteps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> steps = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = item.toString().trim();
            if (!text.isEmpty()) {
                steps.add(text);
            }
        }
        return steps;
    }
}

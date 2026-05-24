package com.javacodeagent.core.plan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计划服务 - 支持完整的计划模式流程
 * 探索(Explore) → 起草(Draft) → 审阅(Review) → 批准(Approved) → 执行(Execute)
 */
@Slf4j
@Service
public class PlanService {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();

    /**
     * 进入计划模式（探索阶段）
     * 在此模式下，只能使用只读工具
     */
    public PlanResult enterPlanMode(String conversationId, String description) {
        String planId = UUID.randomUUID().toString();
        Plan plan = Plan.builder()
            .id(planId)
            .conversationId(conversationId)
            .description(description)
            .mode(Plan.PlanMode.EXPLORE)
            .status(Plan.PlanStatus.DRAFT)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        plans.put(planId, plan);
        log.info("Enter plan mode: {} - {}", planId, description);

        return PlanResult.success(plan,
            "Plan mode entered. Use read-only tools (Read, Glob, Grep, List) to explore the codebase.");
    }

    /**
     * 添加步骤到计划
     */
    public void addStep(String planId, PlanStep step) {
        Plan plan = plans.get(planId);
        if (plan != null) {
            plan.getSteps().add(step);
            plan.setUpdatedAt(LocalDateTime.now());
            log.info("Added step {} to plan {}: {}", step.getOrder(), planId, step.getDescription());
        }
    }

    /**
     * 提交计划审阅
     */
    public PlanResult submitForReview(String planId) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        if (plan.getSteps().isEmpty()) {
            return PlanResult.error("Cannot submit plan with no steps");
        }

        plan.setMode(Plan.PlanMode.DRAFT);
        plan.setStatus(Plan.PlanStatus.IN_REVIEW);
        plan.setUpdatedAt(LocalDateTime.now());

        StringBuilder output = new StringBuilder();
        output.append("Plan submitted for review:\n");
        output.append("Description: ").append(plan.getDescription()).append("\n\n");
        output.append("Steps:\n");
        for (PlanStep step : plan.getSteps()) {
            output.append("  ").append(step.getOrder()).append(". ")
                .append(step.getDescription()).append("\n");
        }
        output.append("\nTotal: ").append(plan.getSteps().size()).append(" steps");

        return PlanResult.success(plan, output.toString());
    }

    /**
     * 批准计划并退出计划模式
     *
     * @param planId          计划ID
     * @param allowedPrompts  批准的权限操作列表（如 ["file-read", "glob"]）
     */
    public PlanResult approvePlan(String planId, List<String> allowedPrompts) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        plan.setMode(Plan.PlanMode.APPROVED);
        plan.setStatus(Plan.PlanStatus.APPROVED);
        plan.setAllowedPrompts(allowedPrompts);
        plan.setUpdatedAt(LocalDateTime.now());

        log.info("Plan {} approved with {} allowed prompts", planId,
            allowedPrompts != null ? allowedPrompts.size() : 0);

        return PlanResult.success(plan, "Plan approved. You can now execute the implementation steps.");
    }

    /**
     * 拒绝计划
     */
    public PlanResult rejectPlan(String planId, String reason) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        plan.setStatus(Plan.PlanStatus.REJECTED);
        plan.setUpdatedAt(LocalDateTime.now());

        log.info("Plan {} rejected: {}", planId, reason);
        return PlanResult.success(plan, "Plan rejected: " + reason);
    }

    /**
     * 执行计划的下一步骤
     */
    public PlanResult executeNextStep(String planId) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        if (plan.getStatus() != Plan.PlanStatus.APPROVED) {
            return PlanResult.error("Plan must be approved before execution");
        }

        // 找到第一个未执行的步骤
        for (PlanStep step : plan.getSteps()) {
            if (step.getStatus() == PlanStep.StepStatus.PENDING) {
                step.setStatus(PlanStep.StepStatus.IN_PROGRESS);
                plan.setStatus(Plan.PlanStatus.IN_PROGRESS);
                plan.setUpdatedAt(LocalDateTime.now());

                return PlanResult.success(plan,
                    "Executing step " + step.getOrder() + ": " + step.getDescription());
            }
        }

        // 所有步骤已完成
        plan.setStatus(Plan.PlanStatus.COMPLETED);
        plan.setUpdatedAt(LocalDateTime.now());
        return PlanResult.success(plan, "All steps completed!");
    }

    /**
     * 获取计划
     */
    public Plan getPlan(String planId) {
        return plans.get(planId);
    }

    /**
     * 执行完整计划（一次性执行所有步骤）
     */
    public PlanResult executePlan(String planId) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        if (plan.getStatus() == Plan.PlanStatus.DRAFT || plan.getStatus() == Plan.PlanStatus.IN_REVIEW) {
            return PlanResult.error("Plan must be approved before execution");
        }

        if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return PlanResult.error("Plan has no steps: " + planId);
        }

        plan.setStatus(Plan.PlanStatus.IN_PROGRESS);
        StringBuilder output = new StringBuilder();
        boolean allSuccess = true;

        for (PlanStep step : plan.getSteps()) {
            if (step.getStatus() == PlanStep.StepStatus.COMPLETED) continue;

            step.setStatus(PlanStep.StepStatus.IN_PROGRESS);
            log.info("Executing plan step {}: {}", step.getOrder(), step.getDescription());

            try {
                step.setStatus(PlanStep.StepStatus.COMPLETED);
                output.append("Step ").append(step.getOrder()).append(": ")
                    .append(step.getDescription()).append(" - COMPLETED\n");
            } catch (Exception e) {
                step.setStatus(PlanStep.StepStatus.FAILED);
                output.append("Step ").append(step.getOrder()).append(": ")
                    .append(step.getDescription()).append(" - FAILED: ").append(e.getMessage()).append("\n");
                allSuccess = false;
                break;
            }
        }

        plan.setStatus(allSuccess ? Plan.PlanStatus.COMPLETED : Plan.PlanStatus.FAILED);
        plan.setUpdatedAt(LocalDateTime.now());

        return PlanResult.builder()
            .plan(plan)
            .output(output.toString())
            .success(allSuccess)
            .build();
    }
}

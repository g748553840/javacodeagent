package com.javacodeagent.core.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.javacodeagent.entity.PlanEntity;
import com.javacodeagent.repository.PlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计划服务 - 支持完整的计划模式流程。
 *
 * <p>探索(Explore) → 起草(Draft) → 审阅(Review) → 批准(Approved) → 执行(Execute)
 *
 * <p><b>双层存储</b>：
 * <ul>
 *   <li>内存 ConcurrentHashMap（Plan）：快速读取，O(1) 查询</li>
 *   <li>JPA PlanRepository（PlanEntity）：持久化到 H2/PostgreSQL，重启后不丢失</li>
 * </ul>
 *
 * <p>步骤列表序列化为 JSON CLOB 存储，allowedPrompts 同理。
 * 所有写操作（create / 状态变更 / 步骤变更）同步写入数据库。
 * 启动时通过 {@link #loadFromDatabase()} 恢复内存状态。
 */
@Slf4j
@Service
public class PlanService {

    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final PlanRepository planRepository;

    /** 独立 ObjectMapper（不依赖 Spring 上下文注入，避免循环依赖），注册 JavaTime 模块 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    public PlanService(PlanRepository planRepository) {
        this.planRepository = planRepository;
        loadFromDatabase();
    }

    // =========================================================================
    // 公开业务接口
    // =========================================================================

    /**
     * 进入计划模式（探索阶段）。
     * 在此模式下，只能使用只读工具。
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
        persistToDatabase(plan);
        log.info("Enter plan mode: {} - {}", planId, description);

        return PlanResult.success(plan,
            "Plan mode entered. Use read-only tools (Read, Glob, Grep, List) to explore the codebase.");
    }

    /**
     * 添加步骤到计划。
     */
    public void addStep(String planId, PlanStep step) {
        Plan plan = plans.get(planId);
        if (plan != null) {
            plan.getSteps().add(step);
            plan.setUpdatedAt(LocalDateTime.now());
            persistToDatabase(plan);
            log.info("Added step {} to plan {}: {}", step.getOrder(), planId, step.getDescription());
        }
    }

    /**
     * 提交计划审阅。
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
        persistToDatabase(plan);

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
     * 批准计划并退出计划模式。
     *
     * @param planId          计划ID
     * @param allowedPrompts  批准后允许的操作列表（如 ["file-read", "glob"]）
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
        persistToDatabase(plan);

        log.info("Plan {} approved with {} allowed prompts", planId,
            allowedPrompts != null ? allowedPrompts.size() : 0);

        return PlanResult.success(plan, "Plan approved. You can now execute the implementation steps.");
    }

    /**
     * 拒绝计划，并将状态重置回 DRAFT（允许用户修改后重新提交审阅）。
     */
    public PlanResult rejectPlan(String planId, String reason) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        plan.setStatus(Plan.PlanStatus.REJECTED);
        plan.setMode(Plan.PlanMode.DRAFT);
        plan.getSteps().forEach(step -> {
            if (step.getStatus() != PlanStep.StepStatus.COMPLETED) {
                step.setStatus(PlanStep.StepStatus.PENDING);
            }
        });
        plan.setUpdatedAt(LocalDateTime.now());
        persistToDatabase(plan);

        log.info("Plan {} rejected and reset to DRAFT: {}", planId, reason);
        return PlanResult.success(plan,
            "Plan rejected: " + reason + "\nYou can modify the plan steps and re-submit for review.");
    }

    /**
     * 执行计划的下一步骤（逐步执行模式）。
     * 执行体（实际的文件读写、工具调用）由调用方或 AI Agent 在获取步骤描述后自行完成。
     * PlanService 仅负责状态流转和时间戳记录。
     */
    public PlanResult executeNextStep(String planId) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        if (plan.getStatus() != Plan.PlanStatus.APPROVED
                && plan.getStatus() != Plan.PlanStatus.IN_PROGRESS) {
            return PlanResult.error("Plan must be approved before execution");
        }

        for (PlanStep step : plan.getSteps()) {
            if (step.getStatus() == PlanStep.StepStatus.PENDING) {
                step.setStatus(PlanStep.StepStatus.IN_PROGRESS);
                step.setStartedAt(LocalDateTime.now());
                plan.setStatus(Plan.PlanStatus.IN_PROGRESS);
                plan.setUpdatedAt(LocalDateTime.now());
                persistToDatabase(plan);

                return PlanResult.success(plan,
                    "Executing step " + step.getOrder() + ": " + step.getDescription());
            }
        }

        // 所有步骤已完成
        plan.setStatus(Plan.PlanStatus.COMPLETED);
        plan.setUpdatedAt(LocalDateTime.now());
        persistToDatabase(plan);
        return PlanResult.success(plan, "All steps completed!");
    }

    /**
     * 标记当前 IN_PROGRESS 步骤为完成（由调用方在执行实际操作后调用）。
     */
    public PlanResult completeCurrentStep(String planId, String resultSummary) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }
        for (PlanStep step : plan.getSteps()) {
            if (step.getStatus() == PlanStep.StepStatus.IN_PROGRESS) {
                step.setStatus(PlanStep.StepStatus.COMPLETED);
                step.setFinishedAt(LocalDateTime.now());
                step.setResult(resultSummary);
                plan.setUpdatedAt(LocalDateTime.now());
                persistToDatabase(plan);
                log.info("Plan {} step {} completed", planId, step.getOrder());
                return PlanResult.success(plan,
                    "Step " + step.getOrder() + " completed: " + step.getDescription());
            }
        }
        return PlanResult.error("No step is currently IN_PROGRESS");
    }

    /**
     * 标记当前 IN_PROGRESS 步骤为失败。
     */
    public PlanResult failCurrentStep(String planId, String errorMessage) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }
        for (PlanStep step : plan.getSteps()) {
            if (step.getStatus() == PlanStep.StepStatus.IN_PROGRESS) {
                step.setStatus(PlanStep.StepStatus.FAILED);
                step.setFinishedAt(LocalDateTime.now());
                step.setResult("FAILED: " + errorMessage);
                plan.setStatus(Plan.PlanStatus.FAILED);
                plan.setUpdatedAt(LocalDateTime.now());
                persistToDatabase(plan);
                log.warn("Plan {} step {} failed: {}", planId, step.getOrder(), errorMessage);
                return PlanResult.success(plan,
                    "Step " + step.getOrder() + " failed: " + errorMessage);
            }
        }
        return PlanResult.error("No step is currently IN_PROGRESS");
    }

    /**
     * 获取计划（优先内存，内存没有则尝试从数据库加载）。
     */
    public Plan getPlan(String planId) {
        Plan cached = plans.get(planId);
        if (cached != null) return cached;

        // 缓存 miss：尝试从数据库加载（正常情况不应发生，保底处理）
        return planRepository.findById(planId)
            .map(entity -> {
                Plan plan = entityToPlan(entity);
                if (plan != null) plans.put(planId, plan);
                return plan;
            })
            .orElse(null);
    }

    /**
     * 按会话查询计划列表。
     */
    public List<Plan> getPlansByConversation(String conversationId) {
        return plans.values().stream()
            .filter(p -> conversationId.equals(p.getConversationId()))
            .toList();
    }

    /**
     * 执行完整计划（一次性推进所有步骤状态）。
     *
     * <p><b>设计说明：</b>PlanService 是计划的状态机，不直接执行文件读写或 Shell 命令。
     * 每个步骤的"执行体"（读文件、写代码、运行测试等）由持有计划的 Agent 或上层服务
     * 在收到 IN_PROGRESS 状态后自行完成，然后通过 {@link #completeCurrentStep} /
     * {@link #failCurrentStep} 回调更新步骤结果。
     *
     * <p>此方法适用于无外部执行器、步骤状态由调用方批量标记的场景（如测试桩）。
     */
    public PlanResult executePlan(String planId) {
        Plan plan = plans.get(planId);
        if (plan == null) {
            return PlanResult.error("Plan not found: " + planId);
        }

        if (plan.getStatus() != Plan.PlanStatus.APPROVED && plan.getStatus() != Plan.PlanStatus.IN_PROGRESS) {
            return PlanResult.error("Plan must be in APPROVED or IN_PROGRESS status to execute. Current: " + plan.getStatus());
        }

        if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return PlanResult.error("Plan has no steps: " + planId);
        }

        plan.setStatus(Plan.PlanStatus.IN_PROGRESS);
        StringBuilder output = new StringBuilder();
        boolean allSuccess = true;
        int executedCount = 0;

        for (PlanStep step : plan.getSteps()) {
            if (step.getStatus() == PlanStep.StepStatus.COMPLETED) {
                output.append("Step ").append(step.getOrder()).append(": ")
                    .append(step.getDescription()).append(" - ALREADY COMPLETED\n");
                continue;
            }

            step.setStatus(PlanStep.StepStatus.IN_PROGRESS);
            step.setStartedAt(LocalDateTime.now());
            log.info("Plan {} - executing step {}: {}", planId, step.getOrder(), step.getDescription());

            try {
                // 步骤实际执行体由外部 Agent 完成；此处代表"状态流转"完成
                step.setStatus(PlanStep.StepStatus.COMPLETED);
                step.setFinishedAt(LocalDateTime.now());
                step.setResult("Marked COMPLETED by executePlan()");
                executedCount++;
                output.append("Step ").append(step.getOrder()).append(": ")
                    .append(step.getDescription()).append(" - COMPLETED\n");
            } catch (Exception e) {
                step.setStatus(PlanStep.StepStatus.FAILED);
                step.setFinishedAt(LocalDateTime.now());
                step.setResult("FAILED: " + e.getMessage());
                output.append("Step ").append(step.getOrder()).append(": ")
                    .append(step.getDescription()).append(" - FAILED: ").append(e.getMessage()).append("\n");
                allSuccess = false;
                break;
            }
        }

        plan.setStatus(allSuccess ? Plan.PlanStatus.COMPLETED : Plan.PlanStatus.FAILED);
        plan.setUpdatedAt(LocalDateTime.now());
        persistToDatabase(plan);

        output.append("\nTotal executed: ").append(executedCount)
            .append("/").append(plan.getSteps().size()).append(" steps");

        return PlanResult.builder()
            .plan(plan)
            .output(output.toString())
            .success(allSuccess)
            .build();
    }

    // =========================================================================
    // 持久化辅助
    // =========================================================================

    /**
     * 将内存中的 Plan 对象同步到数据库（upsert）。
     * 注：Spring @Transactional 对私有方法无效（代理无法拦截）。
     * 此处 findById + save 两步操作由 catch(Exception) 兜底：
     * 若并发插入导致 unique key 冲突，仅记录 warn 并以内存数据继续。
     * 单实例部署下，planId 为 UUID 预先分配，并发 insert 概率极低。
     */
    private void persistToDatabase(Plan plan) {
        try {
            PlanEntity entity = planRepository.findById(plan.getId())
                .orElse(new PlanEntity());
            entity.setId(plan.getId());
            entity.setConversationId(plan.getConversationId());
            entity.setDescription(plan.getDescription());
            entity.setMode(plan.getMode() != null ? plan.getMode().name() : null);
            entity.setStatus(plan.getStatus() != null ? plan.getStatus().name() : null);
            entity.setStepsJson(MAPPER.writeValueAsString(plan.getSteps()));
            entity.setAllowedPromptsJson(
                plan.getAllowedPrompts() != null
                    ? MAPPER.writeValueAsString(plan.getAllowedPrompts())
                    : null);
            entity.setCreatedAt(plan.getCreatedAt());
            entity.setUpdatedAt(plan.getUpdatedAt());
            planRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist plan {} to database: {}", plan.getId(), e.getMessage());
        }
    }

    /**
     * 应用启动时从数据库恢复所有计划到内存 Map。
     */
    private void loadFromDatabase() {
        try {
            List<PlanEntity> dbPlans = planRepository.findAll();
            for (PlanEntity entity : dbPlans) {
                Plan plan = entityToPlan(entity);
                if (plan != null) {
                    plans.put(plan.getId(), plan);
                }
            }
            if (!dbPlans.isEmpty()) {
                log.info("Loaded {} plans from database", dbPlans.size());
            }
        } catch (Exception e) {
            log.warn("Could not load plans from database (first start?): {}", e.getMessage());
        }
    }

    /**
     * PlanEntity（JPA） → Plan（领域对象）转换。
     */
    private Plan entityToPlan(PlanEntity entity) {
        try {
            List<PlanStep> steps = entity.getStepsJson() != null
                ? MAPPER.readValue(entity.getStepsJson(), new TypeReference<>() {})
                : new ArrayList<>();
            List<String> allowedPrompts = entity.getAllowedPromptsJson() != null
                ? MAPPER.readValue(entity.getAllowedPromptsJson(), new TypeReference<>() {})
                : null;

            return Plan.builder()
                .id(entity.getId())
                .conversationId(entity.getConversationId())
                .description(entity.getDescription())
                .mode(entity.getMode() != null ? Plan.PlanMode.valueOf(entity.getMode()) : Plan.PlanMode.DRAFT)
                .status(entity.getStatus() != null ? Plan.PlanStatus.valueOf(entity.getStatus()) : Plan.PlanStatus.DRAFT)
                .steps(steps)
                .allowedPrompts(allowedPrompts)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
        } catch (Exception e) {
            log.error("Failed to deserialize plan entity {}: {}", entity.getId(), e.getMessage());
            return null;
        }
    }
}

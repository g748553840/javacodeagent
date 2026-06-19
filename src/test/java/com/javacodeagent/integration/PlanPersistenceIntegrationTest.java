package com.javacodeagent.integration;

import com.javacodeagent.core.plan.Plan;
import com.javacodeagent.core.plan.PlanResult;
import com.javacodeagent.core.plan.PlanService;
import com.javacodeagent.core.plan.PlanStep;
import com.javacodeagent.entity.PlanEntity;
import com.javacodeagent.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计划持久化集成测试。
 *
 * <p>使用 H2 内存数据库验证 PlanService ↔ PlanRepository（JPA）双层存储的完整读写路径：
 * <ul>
 *   <li>创建计划 → 数据库中存在记录</li>
 *   <li>状态流转（批准、拒绝、执行步骤）→ 数据库实时更新</li>
 *   <li>步骤完成/失败 → steps JSON 更新</li>
 *   <li>getPlan() 缓存 miss 后回源数据库</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "security.api-key=",
    "llm.api-key=test-key",
    "llm.provider=anthropic"
})
class PlanPersistenceIntegrationTest {

    @Autowired
    private PlanService planService;

    @Autowired
    private PlanRepository planRepository;

    @BeforeEach
    void cleanUp() {
        // 每个测试前清空 plans 表，防止数据污染
        planRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // 创建计划 → 写库
    // ------------------------------------------------------------------

    @Test
    void enterPlanMode_persistsToDatabase() {
        PlanResult result = planService.enterPlanMode("conv-persist-1", "Test plan description");

        assertThat(result.isSuccess()).isTrue();
        String planId = result.getPlan().getId();

        Optional<PlanEntity> entity = planRepository.findById(planId);
        assertThat(entity).isPresent();
        assertThat(entity.get().getDescription()).isEqualTo("Test plan description");
        assertThat(entity.get().getStatus()).isEqualTo("DRAFT");
        assertThat(entity.get().getMode()).isEqualTo("EXPLORE");
    }

    // ------------------------------------------------------------------
    // 批准计划 → 状态更新到库
    // ------------------------------------------------------------------

    @Test
    void approvePlan_updatesStatusInDatabase() {
        PlanResult created = planService.enterPlanMode("conv-persist-2", "Approve test");
        String planId = created.getPlan().getId();

        planService.addStep(planId, PlanStep.builder()
            .order(1)
            .description("Step A")
            .build());

        planService.approvePlan(planId, List.of("file-read", "glob"));

        PlanEntity entity = planRepository.findById(planId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("APPROVED");
        assertThat(entity.getAllowedPromptsJson()).contains("file-read");
    }

    // ------------------------------------------------------------------
    // 步骤执行 → steps JSON 更新
    // ------------------------------------------------------------------

    @Test
    void executeNextStep_updatesStepsJsonInDatabase() {
        PlanResult created = planService.enterPlanMode("conv-persist-3", "Step execution test");
        String planId = created.getPlan().getId();

        planService.addStep(planId, PlanStep.builder().order(1).description("Step 1").build());
        planService.addStep(planId, PlanStep.builder().order(2).description("Step 2").build());
        planService.approvePlan(planId, null);

        // 推进到步骤1 IN_PROGRESS
        planService.executeNextStep(planId);

        PlanEntity entity = planRepository.findById(planId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(entity.getStepsJson()).contains("IN_PROGRESS");
    }

    // ------------------------------------------------------------------
    // 完成步骤 → 结果摘要写入 JSON
    // ------------------------------------------------------------------

    @Test
    void completeCurrentStep_persistsResultSummary() {
        PlanResult created = planService.enterPlanMode("conv-persist-4", "Complete step test");
        String planId = created.getPlan().getId();

        planService.addStep(planId, PlanStep.builder().order(1).description("Write code").build());
        planService.approvePlan(planId, null);
        planService.executeNextStep(planId);

        planService.completeCurrentStep(planId, "Code written successfully");

        PlanEntity entity = planRepository.findById(planId).orElseThrow();
        assertThat(entity.getStepsJson()).contains("COMPLETED");
        assertThat(entity.getStepsJson()).contains("Code written successfully");
    }

    // ------------------------------------------------------------------
    // 失败步骤 → FAILED 状态写库
    // ------------------------------------------------------------------

    @Test
    void failCurrentStep_persistsFailedStatus() {
        PlanResult created = planService.enterPlanMode("conv-persist-5", "Fail step test");
        String planId = created.getPlan().getId();

        planService.addStep(planId, PlanStep.builder().order(1).description("Risky step").build());
        planService.approvePlan(planId, null);
        planService.executeNextStep(planId);

        planService.failCurrentStep(planId, "Compilation error");

        PlanEntity entity = planRepository.findById(planId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getStepsJson()).contains("FAILED");
        assertThat(entity.getStepsJson()).contains("Compilation error");
    }

    // ------------------------------------------------------------------
    // getPlan() 缓存 miss → 回源数据库
    // ------------------------------------------------------------------

    @Test
    void getPlan_cacheMiss_loadsFromDatabase() {
        // 直接写一条 PlanEntity 到数据库（绕过 PlanService 的内存 Map）
        PlanEntity entity = new PlanEntity();
        entity.setId("direct-db-id-001");
        entity.setConversationId("conv-direct");
        entity.setDescription("Direct DB insert");
        entity.setMode("DRAFT");
        entity.setStatus("DRAFT");
        entity.setStepsJson("[]");
        planRepository.save(entity);

        // PlanService 内存中没有这个 id（因为我们绕过了 enterPlanMode）
        // getPlan() 应该回源数据库
        Plan plan = planService.getPlan("direct-db-id-001");

        assertThat(plan).isNotNull();
        assertThat(plan.getDescription()).isEqualTo("Direct DB insert");
        assertThat(plan.getId()).isEqualTo("direct-db-id-001");
    }

    // ------------------------------------------------------------------
    // 拒绝计划 → 状态重置 DRAFT 写库
    // ------------------------------------------------------------------

    @Test
    void rejectPlan_resetsToDraftInDatabase() {
        PlanResult created = planService.enterPlanMode("conv-persist-6", "Reject test");
        String planId = created.getPlan().getId();

        planService.addStep(planId, PlanStep.builder().order(1).description("Step A").build());
        planService.submitForReview(planId);

        // 状态应为 IN_REVIEW
        assertThat(planRepository.findById(planId).map(PlanEntity::getStatus).orElse(""))
            .isEqualTo("IN_REVIEW");

        planService.rejectPlan(planId, "Needs more detail");

        // 拒绝后状态回到 DRAFT
        PlanEntity entity = planRepository.findById(planId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo("DRAFT");
    }

    // ------------------------------------------------------------------
    // 按会话查询
    // ------------------------------------------------------------------

    @Test
    void findByConversationId_returnsPlansForConversation() {
        planService.enterPlanMode("conv-query-1", "Plan A");
        planService.enterPlanMode("conv-query-1", "Plan B");
        planService.enterPlanMode("conv-query-2", "Plan C");

        List<PlanEntity> results = planRepository.findByConversationId("conv-query-1");

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> "conv-query-1".equals(e.getConversationId()));
    }
}

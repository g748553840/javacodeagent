package com.javacodeagent.repository;

import com.javacodeagent.entity.PlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends JpaRepository<PlanEntity, String> {

    /** 按会话查询计划列表 */
    List<PlanEntity> findByConversationId(String conversationId);

    /** 按状态查询计划 */
    List<PlanEntity> findByStatus(String status);
}

package com.javacodeagent;

import com.javacodeagent.core.enums.TaskStatus;
import com.javacodeagent.core.task.TaskManager;
import com.javacodeagent.core.task.TaskRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端对端集成测试：启动完整应用上下文，通过 HTTP 测试核心 API。
 *
 * 使用 H2 内存数据库，不需要真实 LLM API Key（仅测试不调用 LLM 的接口）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "llm.api-key=test-key",
        "security.api-key="  // 关闭认证方便测试
    })
@AutoConfigureWebTestClient
class IntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TaskManager taskManager;

    // -------------------------------------------------------------------------
    // 健康检查
    // -------------------------------------------------------------------------

    @Test
    void healthCheck() {
        webTestClient.get()
            .uri("/api/v1/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("OK");
    }

    // -------------------------------------------------------------------------
    // 任务 CRUD + JPA 持久化
    // -------------------------------------------------------------------------

    @Test
    void createAndRetrieveTask() {
        webTestClient.post()
            .uri("/api/v1/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"subject": "集成测试任务", "description": "验证 TaskManager JPA 持久化"}
                """)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.subject").isEqualTo("集成测试任务")
            .jsonPath("$.status").isEqualTo("PENDING");
    }

    @Test
    void listTasksReturnsAll() {
        taskManager.createTask("任务1", "描述1");
        taskManager.createTask("任务2", "描述2");

        webTestClient.get()
            .uri("/api/v1/tasks")
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(TaskRecord.class)
            .value(list -> assertThat(list.size()).isGreaterThanOrEqualTo(2));
    }

    @Test
    void updateTaskStatus() {
        TaskRecord task = taskManager.createTask("状态测试", "状态变更验证");

        webTestClient.put()
            .uri("/api/v1/tasks/{id}/status", task.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"status\": \"IN_PROGRESS\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("IN_PROGRESS");
    }

    @Test
    void deleteTask() {
        TaskRecord task = taskManager.createTask("待删除任务", "删除测试");

        webTestClient.delete()
            .uri("/api/v1/tasks/{id}", task.getId())
            .exchange()
            .expectStatus().isNoContent();

        assertThat(taskManager.getTask(task.getId())).isNull();
    }

    // -------------------------------------------------------------------------
    // 任务依赖 DAG
    // -------------------------------------------------------------------------

    @Test
    void taskDependencyBlocking() {
        TaskRecord parent = taskManager.createTask("父任务", "必须先完成");
        TaskRecord child  = taskManager.createTask("子任务", "依赖父任务",
            null, java.util.Set.of(parent.getId()));

        // 父任务未完成 → 子任务被阻塞
        assertThat(taskManager.canStart(child.getId())).isFalse();
        assertThat(taskManager.getBlockingTasks(child.getId())).hasSize(1);

        // 完成父任务 → 子任务可开始
        taskManager.updateStatus(parent.getId(), TaskStatus.COMPLETED);
        assertThat(taskManager.canStart(child.getId())).isTrue();
    }

    // -------------------------------------------------------------------------
    // 记忆 API
    // -------------------------------------------------------------------------

    @Test
    void saveAndRetrieveMemory() {
        webTestClient.post()
            .uri("/api/v1/memory")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "userId": "test-user",
                  "name": "test-memory",
                  "description": "集成测试记忆",
                  "type": "USER",
                  "content": "测试用户背景信息"
                }
                """)
            .exchange()
            .expectStatus().isOk();

        webTestClient.get()
            .uri("/api/v1/memory/test-user")
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(Object.class)
            .value(list -> assertThat(list).isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // HTTP 认证（security.api-key 关闭时应无障碍访问）
    // -------------------------------------------------------------------------

    @Test
    void withoutApiKeyConfiguredAllRequestsPass() {
        // security.api-key="" 时不需要 Authorization header
        webTestClient.get()
            .uri("/api/v1/tasks")
            .exchange()
            .expectStatus().isOk();
    }
}

package com.javacodeagent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.llm.LLMStreamChunk;
import com.javacodeagent.core.model.LLMResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SSE 流式 HTTP 端对端测试。
 *
 * <p>启动完整 Spring Boot 应用（随机端口），通过 WebTestClient 向
 * {@code /api/v1/chat/stream} 发送 POST 请求，验证 SSE 事件格式与内容。
 *
 * <p>LLM 调用通过 {@code @MockBean} 拦截，不需要真实 API Key。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "security.api-key=",      // 关闭认证
    "llm.api-key=test-key",
    "llm.provider=anthropic"
})
class SseStreamIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LLMClient llmClient;

    // ------------------------------------------------------------------
    // 场景 1：/chat 非流式端点 — 返回 JSON 响应
    // 注：ConversationWebSocketHandler.chat() 接受 Map<String, String>
    // ------------------------------------------------------------------

    @Test
    void chat_nonStreaming_returnsJsonResponse() {
        LLMResponse resp = LLMResponse.builder()
            .id("resp-sse-1")
            .content("Non-streaming response")
            .stopReason("end_turn")
            .build();

        when(llmClient.chat(any())).thenReturn(Mono.just(resp));
        // chatStreamFull 默认通过 chat() 降级，无需额外 stub

        // ConversationWebSocketHandler.chat() 接受 Map<String,String> body
        webTestClient.post()
            .uri("/api/v1/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"content": "Hello", "sessionId": "sse-session-1"}
                """)
            .header("X-User-Id", "sse-user")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content").isEqualTo("Non-streaming response");
    }

    // ------------------------------------------------------------------
    // 场景 2：/chat/stream SSE 端点 — text/event-stream 格式
    // ------------------------------------------------------------------

    @Test
    void chatStream_textChunks_returnsSseEvents() {
        when(llmClient.chatStreamFull(any())).thenReturn(Flux.just(
            LLMStreamChunk.text("Hello "),
            LLMStreamChunk.text("World"),
            LLMStreamChunk.done("end_turn")
        ));

        // 收集所有 SSE data 行
        List<String> lines = webTestClient
            .mutate().responseTimeout(Duration.ofSeconds(10)).build()
            .post()
            .uri("/api/v1/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"content": "say hello", "sessionId": "sse-session-2"}
                """)
            .header("X-User-Id", "sse-user")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .block(Duration.ofSeconds(15));

        assertThat(lines).isNotNull().isNotEmpty();

        // 验证包含 content 和 done 类型的 SSE 事件
        boolean hasContent = lines.stream().anyMatch(l -> l.contains("\"type\":\"content\""));
        boolean hasDone    = lines.stream().anyMatch(l -> l.contains("\"type\":\"done\""));

        assertThat(hasContent).isTrue();
        assertThat(hasDone).isTrue();
    }

    // ------------------------------------------------------------------
    // 场景 3：content JSON 中携带正确的文本片段
    // ------------------------------------------------------------------

    @Test
    void chatStream_contentEvents_carryTextPayload() throws Exception {
        when(llmClient.chatStreamFull(any())).thenReturn(Flux.just(
            LLMStreamChunk.text("chunk-alpha"),
            LLMStreamChunk.done("end_turn")
        ));

        List<String> lines = webTestClient
            .mutate().responseTimeout(Duration.ofSeconds(10)).build()
            .post()
            .uri("/api/v1/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"content": "test", "sessionId": "sse-session-3"}
                """)
            .header("X-User-Id", "sse-user")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .block(Duration.ofSeconds(15));

        assertThat(lines).isNotNull();

        // 找到 content 事件并解析 JSON
        String contentEvent = lines.stream()
            .filter(l -> l.contains("\"type\":\"content\""))
            .findFirst()
            .orElse(null);

        assertThat(contentEvent).isNotNull();

        // SSE 行格式：每个元素已经是 "data: {...}\n\n" 或仅 "data: {...}"
        String json = contentEvent.startsWith("data: ")
            ? contentEvent.substring(6).trim()
            : contentEvent.trim();

        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("content");
        assertThat(node.get("text").asText()).isEqualTo("chunk-alpha");
    }

    // ------------------------------------------------------------------
    // 场景 4：/health 端点返回 OK
    // ------------------------------------------------------------------

    @Test
    void health_returnsOk() {
        webTestClient.get()
            .uri("/api/v1/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("OK");
    }

    // ------------------------------------------------------------------
    // 场景 5：SSE error chunk 应映射为 error SSE 事件
    // ------------------------------------------------------------------

    @Test
    void chatStream_errorChunk_propagatesErrorEvent() {
        when(llmClient.chatStreamFull(any())).thenReturn(Flux.just(
            LLMStreamChunk.error("LLM upstream timeout")
        ));

        List<String> lines = webTestClient
            .mutate().responseTimeout(Duration.ofSeconds(10)).build()
            .post()
            .uri("/api/v1/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"content": "trigger error", "sessionId": "sse-session-5"}
                """)
            .header("X-User-Id", "sse-user")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .block(Duration.ofSeconds(15));

        assertThat(lines).isNotNull();
        boolean hasError = lines.stream().anyMatch(l -> l.contains("\"type\":\"error\""));
        assertThat(hasError).isTrue();
    }
}

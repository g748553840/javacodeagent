package com.javacodeagent.integration;

import com.javacodeagent.core.conversation.ConversationManager;
import com.javacodeagent.core.conversation.ConversationRequest;
import com.javacodeagent.core.conversation.ConversationResponse;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.llm.LLMStreamChunk;
import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.core.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Agentic Loop 集成测试。
 *
 * <p>使用 MockBean 替换 LLMClient，模拟三类场景：
 * <ol>
 *   <li>纯文本回复（无工具调用）</li>
 *   <li>单轮工具调用 + 工具结果 + 最终回复</li>
 *   <li>流式（chatStreamFull）文本 + 工具调用片段</li>
 * </ol>
 *
 * <p>测试验证 ConversationManager 的循环调度逻辑，不依赖真实 LLM API。
 */
@SpringBootTest
@TestPropertySource(properties = {
    "security.api-key=",
    "llm.api-key=test-key",
    "llm.provider=anthropic",
    "agent.max-tool-call-depth=3"
})
class AgenticLoopIntegrationTest {

    @Autowired
    private ConversationManager conversationManager;

    /** Mock LLM 客户端，控制每轮 LLM 输出 */
    @MockBean
    private LLMClient llmClient;

    // ------------------------------------------------------------------
    // 场景 1：纯文本回复（无工具调用）
    // ------------------------------------------------------------------

    @Test
    void processMessage_pureTextReply_returnsDirect() {
        LLMResponse textResponse = LLMResponse.builder()
            .id("resp-1")
            .content("Hello, I am your AI assistant!")
            .stopReason("end_turn")
            .build();

        when(llmClient.chat(any())).thenReturn(Mono.just(textResponse));
        // chatStreamFull 默认实现通过 chat() 降级，不需要额外 stub

        ConversationRequest req = ConversationRequest.builder()
            .conversationId(UUID.randomUUID().toString())
            .content("Hello")
            .userId("user-loop-1")
            .build();

        ConversationResponse resp = conversationManager.processMessage(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getContent()).contains("Hello");
    }

    // ------------------------------------------------------------------
    // 场景 2：单轮工具调用循环（Read 工具 → 最终回复）
    // ------------------------------------------------------------------

    @Test
    void processMessage_singleToolCallLoop_executesToolAndContinues() {
        // 第一轮 LLM → 返回工具调用（Read 一个临时文件路径）
        ToolCall readCall = ToolCall.builder()
            .id("tool-call-1")
            .name("Read")
            .input(Map.of("file_path", System.getProperty("java.io.tmpdir") + "/nonexistent_test.txt"))
            .build();

        LLMResponse toolCallResponse = LLMResponse.builder()
            .id("resp-tool")
            .content("")
            .toolCalls(List.of(readCall))
            .stopReason("tool_use")
            .build();

        // 第二轮 LLM（收到工具结果后）→ 返回最终文本
        LLMResponse finalResponse = LLMResponse.builder()
            .id("resp-final")
            .content("I tried to read the file but it does not exist.")
            .stopReason("end_turn")
            .build();

        // 第1次 chat() 返回工具调用，第2次返回最终回复
        when(llmClient.chat(any()))
            .thenReturn(Mono.just(toolCallResponse))
            .thenReturn(Mono.just(finalResponse));

        ConversationRequest req = ConversationRequest.builder()
            .conversationId(UUID.randomUUID().toString())
            .content("Read the file /tmp/nonexistent_test.txt")
            .userId("user-loop-2")
            .build();

        ConversationResponse resp = conversationManager.processMessage(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getContent()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // 场景 3：流式处理 — 纯文本 chunks
    // ------------------------------------------------------------------

    @Test
    void processMessageStream_textChunks_emitsSseContentEvents() {
        // chatStreamFull 模拟：依次发 TEXT / TEXT / DONE
        when(llmClient.chatStreamFull(any())).thenReturn(Flux.just(
            LLMStreamChunk.text("Hello "),
            LLMStreamChunk.text("World!"),
            LLMStreamChunk.done("end_turn")
        ));

        String conversationId = UUID.randomUUID().toString();
        ConversationRequest req = ConversationRequest.builder()
            .conversationId(conversationId)
            .content("Say hello")
            .userId("user-stream-1")
            .build();

        StepVerifier.create(conversationManager.processMessageStream(req))
            .assertNext(event -> assertThat(event).contains("\"type\":\"content\"").contains("Hello "))
            .assertNext(event -> assertThat(event).contains("\"type\":\"content\"").contains("World!"))
            .assertNext(event -> assertThat(event).contains("\"type\":\"done\""))
            .verifyComplete();
    }

    // ------------------------------------------------------------------
    // 场景 4：流式处理 — 含工具调用
    // ------------------------------------------------------------------

    @Test
    void processMessageStream_withToolCall_emitsToolStartResultDone() {
        ToolCall tc = ToolCall.builder()
            .id("stream-tool-1")
            .name("Read")
            .input(Map.of("file_path", "/nonexistent.txt"))
            .build();

        // 第一轮流：TOOL_CALL + DONE
        // 第二轮流（工具执行后 LLM 继续）：TEXT + DONE
        when(llmClient.chatStreamFull(any()))
            .thenReturn(Flux.just(
                LLMStreamChunk.toolCall(tc),
                LLMStreamChunk.done("tool_use")
            ))
            .thenReturn(Flux.just(
                LLMStreamChunk.text("File not found."),
                LLMStreamChunk.done("end_turn")
            ));

        String conversationId = UUID.randomUUID().toString();
        ConversationRequest req = ConversationRequest.builder()
            .conversationId(conversationId)
            .content("Read /nonexistent.txt")
            .userId("user-stream-2")
            .build();

        // 收集所有 SSE 事件并验证顺序
        List<String> events = conversationManager.processMessageStream(req)
            .collectList()
            .block();

        assertThat(events).isNotNull();
        // 应包含 tool_start、tool_result、content、done 事件
        String all = String.join("", events);
        assertThat(all).contains("tool_start");
        assertThat(all).contains("tool_result");
        assertThat(all).contains("content");
        assertThat(all).contains("done");
    }

    // ------------------------------------------------------------------
    // 场景 5：超过最大工具调用深度（测试设置 3 次）应终止并返回错误消息
    // ------------------------------------------------------------------

    @Test
    void processMessage_exceedsMaxDepth_returnsSafeError() {
        // 每轮都返回工具调用 → 触发深度超限
        ToolCall loopCall = ToolCall.builder()
            .id("loop-call")
            .name("Read")
            .input(Map.of("file_path", "/tmp/loop.txt"))
            .build();

        LLMResponse infiniteLoop = LLMResponse.builder()
            .id("resp-loop")
            .content("")
            .toolCalls(List.of(loopCall))
            .stopReason("tool_use")
            .build();

        // 永远返回工具调用
        when(llmClient.chat(any())).thenReturn(Mono.just(infiniteLoop));

        ConversationRequest req = ConversationRequest.builder()
            .conversationId(UUID.randomUUID().toString())
            .content("Loop forever")
            .userId("user-loop-depth")
            .build();

        ConversationResponse resp = conversationManager.processMessage(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getContent()).containsIgnoringCase("Maximum tool call depth");
    }
}

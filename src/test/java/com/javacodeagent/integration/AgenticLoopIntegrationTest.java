package com.javacodeagent.integration;

import com.javacodeagent.core.conversation.ConversationManager;
import com.javacodeagent.core.conversation.ConversationRequest;
import com.javacodeagent.core.conversation.ConversationResponse;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.llm.LLMStreamChunk;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import com.javacodeagent.piagent.abort.AbortRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    // ------------------------------------------------------------------
    // 场景 5：LLM 调用失败（重试耗尽 / 不可重试错误）
    // ------------------------------------------------------------------

    /**
     * LLM 失败响应必须以明确的失败信息返回，而不能被当作助手回复。
     *
     * <p>历史上客户端把传输异常吞掉、转成 {@code content="Error: ..."} 的正常响应，
     * 结果这段文本会被持久化进对话历史，污染后续每一轮的上下文——模型会以为
     * 自己上一轮真的说了这句话。
     */
    @Test
    void processMessage_llmError_returnsFailureWithoutPollutingHistory() {
        LLMResponse failure = LLMResponse.ofError("503 Service Unavailable");
        when(llmClient.chat(any())).thenReturn(Mono.just(failure));

        ConversationRequest req = ConversationRequest.builder()
            .conversationId(UUID.randomUUID().toString())
            .content("Anything")
            .userId("user-llm-error")
            .build();

        ConversationResponse resp = conversationManager.processMessage(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getContent())
            .as("failure must be surfaced explicitly, not disguised as an assistant reply")
            .contains("LLM request failed")
            .contains("503");
    }

    // ------------------------------------------------------------------
    // 场景 6：工具要求终止（terminate）
    // ------------------------------------------------------------------

    /**
     * 工具返回 {@code terminate=true} 时循环必须立刻停止。
     *
     * <p>断言的关键是 {@code verify(chat, times(1))}：mock 被设成永远返回工具调用，
     * 所以如果 terminate 没有被读取，循环会一路撞到 maxToolCallDepth（本测试配置为 3），
     * 结果文本变成 "Maximum tool call depth"。两种行为的差别非常明显，
     * 不会因为断言写得宽松而蒙混过关。
     */
    @Test
    void processMessage_toolRequestsTermination_stopsImmediatelyAndReturnsToolOutput() {
        ToolCall exitPlan = ToolCall.builder()
            .id("plan-1")
            .name("ExitPlanMode")
            .input(Map.of(
                "plan", "Refactor the response parser",
                "steps", List.of("Extract the lexer", "Add regression tests")))
            .build();

        LLMResponse alwaysCallsTool = LLMResponse.builder()
            .id("resp-plan")
            .content("Here is my plan.")
            .toolCalls(List.of(exitPlan))
            .stopReason("tool_use")
            .build();
        when(llmClient.chat(any())).thenReturn(Mono.just(alwaysCallsTool));

        ConversationRequest req = ConversationRequest.builder()
            .conversationId(UUID.randomUUID().toString())
            .content("Plan the refactor")
            .userId("user-terminate")
            .build();

        ConversationResponse resp = conversationManager.processMessage(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getContent())
            .as("the terminating tool's output is the last thing the user sees, "
              + "so it must be carried into the response")
            .contains("Here is my plan.")
            .contains("Extract the lexer")
            .contains("Add regression tests");
        assertThat(resp.getContent()).doesNotContain("Maximum tool call depth");

        verify(llmClient, times(1)).chat(any());
    }

    // ------------------------------------------------------------------
    // 场景 7：执行途中被中止
    // ------------------------------------------------------------------

    /**
     * 用户在工具执行期间按下停止：当前批次跑完，但不再发起新一轮 LLM 调用。
     *
     * <p>同样用 {@code times(1)} 与深度超限区分——mock 永远返回工具调用，
     * 中止若没生效就会走到第 3 轮。
     */
    @Test
    void processMessage_abortedDuringToolExecution_stopsBeforeNextLlmCall() {
        ToolCall stopCall = ToolCall.builder()
            .id("stop-1")
            .name("PressStop")
            .input(Map.of())
            .build();

        LLMResponse alwaysCallsTool = LLMResponse.builder()
            .id("resp-abort")
            .content("")
            .toolCalls(List.of(stopCall))
            .stopReason("tool_use")
            .build();
        when(llmClient.chat(any())).thenReturn(Mono.just(alwaysCallsTool));

        ConversationRequest req = ConversationRequest.builder()
            .conversationId(UUID.randomUUID().toString())
            .content("Do something long")
            .userId("user-abort")
            .build();

        ConversationResponse resp = conversationManager.processMessage(req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getContent()).containsIgnoringCase("aborted");
        assertThat(resp.getContent()).doesNotContain("Maximum tool call depth");

        verify(llmClient, times(1)).chat(any());
    }

    /**
     * 模拟"用户在工具执行到一半时点了停止按钮"。
     *
     * <p>真实场景里中止来自另一个 HTTP 请求，与对话不在同一调用栈上；
     * 用一个从工具内部调用 {@link AbortRegistry#abort} 的测试工具，
     * 能在不引入时序竞争的前提下走通完全相同的代码路径。
     */
    @TestConfiguration
    static class AbortingToolConfig {

        @Bean
        Tool pressStopTool(AbortRegistry abortRegistry) {
            return new Tool() {
                @Override public String getName() { return "PressStop"; }
                @Override public String getDescription() { return "test-only: aborts the conversation"; }
                @Override public Map<String, Object> getParameterSchema() { return Map.of("type", "object"); }

                @Override
                public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
                    abortRegistry.abort(context.getConversationId(), "user pressed stop");
                    return ToolExecutionResult.success("stop requested");
                }
            };
        }
    }
}

package com.javacodeagent.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LLMResponse {
    private String id;
    private String content;
    private List<ToolCall> toolCalls;
    private String stopReason;
    private Map<String, Object> usage;
    private String model;

    /**
     * 请求是否失败。
     *
     * <p>历史上 LLM 客户端把传输异常吞掉、转成 {@code content="Error: ..."} 的正常响应，
     * 导致上游无法区分"模型输出了以 Error 开头的文本"和"请求真的失败了"，
     * 重试与降级逻辑都无从下手。此字段让失败显式化。
     */
    @Builder.Default
    private boolean error = false;

    /** 失败时的错误文本，供重试分类器判定是否值得重试。成功时为 null。 */
    private String errorMessage;

    /** 构造一个失败响应。 */
    public static LLMResponse ofError(String message) {
        return LLMResponse.builder()
            .content("")
            .error(true)
            .errorMessage(message)
            .stopReason("error")
            .build();
    }
}
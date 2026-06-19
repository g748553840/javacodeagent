package com.javacodeagent.core.llm;

import com.javacodeagent.core.model.ToolCall;
import lombok.Builder;
import lombok.Data;

/**
 * 流式 LLM 事件单元。
 * chatStreamFull() 以此为元素，携带文本 token、完整工具调用或结束标记。
 */
@Data
@Builder
public class LLMStreamChunk {

    public enum Type { TEXT, TOOL_CALL, DONE, ERROR }

    private Type type;

    /** TEXT 类型：当前 token */
    private String text;

    /** TOOL_CALL 类型：完整组装好的工具调用 */
    private ToolCall toolCall;

    /** DONE 类型：stop_reason */
    private String stopReason;

    /** ERROR 类型：错误信息 */
    private String error;

    public static LLMStreamChunk text(String t) {
        return LLMStreamChunk.builder().type(Type.TEXT).text(t).build();
    }

    public static LLMStreamChunk toolCall(ToolCall tc) {
        return LLMStreamChunk.builder().type(Type.TOOL_CALL).toolCall(tc).build();
    }

    public static LLMStreamChunk done(String reason) {
        return LLMStreamChunk.builder().type(Type.DONE).stopReason(reason).build();
    }

    public static LLMStreamChunk error(String msg) {
        return LLMStreamChunk.builder().type(Type.ERROR).error(msg).build();
    }
}

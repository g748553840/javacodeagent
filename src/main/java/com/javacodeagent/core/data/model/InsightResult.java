package com.javacodeagent.core.data.model;

/**
 * @param failed 洞察生成是否因 LLM 错误/超时而失败并降级到兜底文案；
 *               true 时 markdown 只是 thought 字段或占位提示，不是真正的 LLM 洞察，
 *               调用方应将其与"LLM 正常生成但内容简短"区分开，避免误导用户。
 */
public record InsightResult(ChartSpec chartSpec, String markdown, boolean failed) {
    public InsightResult(ChartSpec chartSpec, String markdown) {
        this(chartSpec, markdown, false);
    }
}

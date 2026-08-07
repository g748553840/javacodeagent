package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataAnalysisReport {
    private String question;
    private ChartSpec chartSpec;
    private String insightMarkdown;
    private boolean success;
    private String errorMessage;
    /** true 表示 insightMarkdown 是 LLM 失败后的兜底文案（如 chartSpec.thought 或占位提示），
     *  并非真实生成的分析洞察；调用方应据此向用户提示"洞察生成未成功"而非静默展示。 */
    private boolean insightFailed;

    public static DataAnalysisReport error(String message) {
        return DataAnalysisReport.builder()
            .success(false)
            .errorMessage(message)
            .build();
    }

    public static DataAnalysisReport error(String question, String message) {
        return DataAnalysisReport.builder()
            .question(question)
            .success(false)
            .errorMessage(message)
            .build();
    }
}

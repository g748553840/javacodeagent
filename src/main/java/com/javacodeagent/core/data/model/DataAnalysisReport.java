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

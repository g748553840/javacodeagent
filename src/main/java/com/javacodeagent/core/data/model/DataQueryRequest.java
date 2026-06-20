package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataQueryRequest {
    private String question;
    private String dataSourceId;
    @Builder.Default
    private int maxRows = 200;
}

package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Nl2SqlResult {
    private String thought;
    private String sql;
    @Builder.Default
    private String displayType = "response_table";
}

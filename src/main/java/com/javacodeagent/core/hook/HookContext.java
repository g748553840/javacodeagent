package com.javacodeagent.core.hook;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HookContext {
    private HookType type;
    private String userId;
    private String conversationId;
    private Map<String, Object> data;
}

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
public class HookResult {
    private boolean shouldContinue;
    private String message;
    private Map<String, Object> data;

    public static HookResult continueWith() {
        return HookResult.builder().shouldContinue(true).build();
    }

    public static HookResult reject(String message) {
        return HookResult.builder().shouldContinue(false).message(message).build();
    }
}

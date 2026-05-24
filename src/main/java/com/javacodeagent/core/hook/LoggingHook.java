package com.javacodeagent.core.hook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingHook implements HookHandler {

    @Override
    public HookResult handle(HookContext context) {
        switch (context.getType()) {
            case PRE_TOOL_CALL -> {
                String toolName = context.getData() != null
                    ? (String) context.getData().getOrDefault("toolName", "unknown")
                    : "unknown";
                log.info("[Hook] Pre-tool call: {} (user: {})", toolName, context.getUserId());
            }
            case POST_TOOL_CALL -> {
                String toolName = context.getData() != null
                    ? (String) context.getData().getOrDefault("toolName", "unknown")
                    : "unknown";
                String result = context.getData() != null
                    ? (String) context.getData().getOrDefault("result", "unknown")
                    : "unknown";
                log.info("[Hook] Post-tool call: {} result: {}", toolName, result);
            }
            case PERMISSION_DENIED -> {
                String reason = context.getData() != null
                    ? (String) context.getData().getOrDefault("reason", "no reason")
                    : "no reason";
                log.warn("[Hook] Permission denied: {} (user: {})", reason, context.getUserId());
            }
            default -> log.debug("[Hook] Type: {} (user: {})", context.getType(), context.getUserId());
        }
        return HookResult.continueWith();
    }
}

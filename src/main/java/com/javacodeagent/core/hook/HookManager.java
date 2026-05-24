package com.javacodeagent.core.hook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class HookManager {

    private final Map<HookType, List<HookHandler>> hooks = new ConcurrentHashMap<>();

    public void registerHook(HookType type, HookHandler handler) {
        hooks.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        log.info("Registered hook for type: {}", type);
    }

    public HookResult triggerHook(HookType type, HookContext context) {
        List<HookHandler> handlers = hooks.get(type);
        if (handlers == null || handlers.isEmpty()) {
            return HookResult.continueWith();
        }

        for (HookHandler handler : handlers) {
            try {
                HookResult result = handler.handle(context);
                if (!result.shouldContinue()) {
                    log.info("Hook {} rejected: {}", type, result.getMessage());
                    return result;
                }
            } catch (Exception e) {
                log.error("Hook handler failed for type: {}", type, e);
                return HookResult.reject("Hook execution error: " + e.getMessage());
            }
        }

        return HookResult.continueWith();
    }
}

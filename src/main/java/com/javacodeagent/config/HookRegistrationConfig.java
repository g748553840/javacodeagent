package com.javacodeagent.config;

import com.javacodeagent.core.hook.HookManager;
import com.javacodeagent.core.hook.HookType;
import com.javacodeagent.core.hook.LoggingHook;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class HookRegistrationConfig {

    private final HookManager hookManager;
    private final LoggingHook loggingHook;

    @PostConstruct
    public void registerHooks() {
        hookManager.registerHook(HookType.PRE_TOOL_CALL, loggingHook);
        hookManager.registerHook(HookType.POST_TOOL_CALL, loggingHook);
        hookManager.registerHook(HookType.PERMISSION_DENIED, loggingHook);
    }
}

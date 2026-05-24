package com.javacodeagent.core.hook;

@FunctionalInterface
public interface HookHandler {
    HookResult handle(HookContext context);
}

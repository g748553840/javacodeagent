package com.javacodeagent.core.data.model;

public record SqlValidationResult(boolean allowed, String reason) {
    public static SqlValidationResult allow() {
        return new SqlValidationResult(true, null);
    }

    public static SqlValidationResult reject(String reason) {
        return new SqlValidationResult(false, reason);
    }

    public boolean isAllowed() { return allowed; }
}

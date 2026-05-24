package com.javacodeagent.core.permission;

import com.javacodeagent.config.PermissionConfig;
import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.enums.PermissionType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionConfig permissionConfig;
    private final Map<String, UserPermissions> userPermissions = new ConcurrentHashMap<>();

    private PermissionLevel defaultLevel = PermissionLevel.SAFE;

    @PostConstruct
    public void init() {
        if (permissionConfig.getDefaultLevel() != null) {
            try {
                this.defaultLevel = PermissionLevel.valueOf(
                    permissionConfig.getDefaultLevel().toUpperCase());
            } catch (IllegalArgumentException e) {
                this.defaultLevel = PermissionLevel.SAFE;
            }
        }

        // 注册自动审批的权限类型
        if (permissionConfig.getAutoApprove() != null) {
            for (String autoApprove : permissionConfig.getAutoApprove()) {
                try {
                    PermissionType type = PermissionType.valueOf(autoApprove.toUpperCase()
                        .replace("-", "_"));
                    // Apply to all users by setting on a global key
                    setAutoApproved("_global", type, true);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown permission type in auto-approve: {}", autoApprove);
                }
            }
        }
    }

    public boolean checkPermission(String userId, PermissionType permissionType) {
        UserPermissions permissions = userPermissions.computeIfAbsent(
            userId,
            id -> new UserPermissions(defaultLevel)
        );

        PermissionLevel level = permissions.getLevel();

        return switch (level) {
            case READ_ONLY -> permissionType == PermissionType.FILE_READ;
            case SAFE -> permissionType == PermissionType.FILE_READ
                || permissionType == PermissionType.FILE_WRITE;
            case NORMAL -> true;
            case ALL -> true;
        };
    }

    public void setPermissionLevel(String userId, PermissionLevel level) {
        userPermissions.compute(userId, (id, p) -> new UserPermissions(level));
    }

    public boolean isAutoApproved(String userId, PermissionType permissionType) {
        // 检查全局自动审批
        UserPermissions global = userPermissions.get("_global");
        if (global != null && global.isAutoApproved(permissionType)) {
            return true;
        }
        // 检查用户级别的自动审批
        UserPermissions permissions = userPermissions.get(userId);
        return permissions != null && permissions.isAutoApproved(permissionType);
    }

    public void setAutoApproved(String userId, PermissionType permissionType, boolean approved) {
        userPermissions.computeIfAbsent(
            userId,
            id -> new UserPermissions(PermissionLevel.SAFE)
        ).setAutoApproved(permissionType, approved);
    }

    private static class UserPermissions {
        private final PermissionLevel level;
        private final Map<PermissionType, Boolean> autoApproved = new HashMap<>();

        public UserPermissions(PermissionLevel level) {
            this.level = level;
            autoApproved.put(PermissionType.FILE_READ, true);
        }

        public PermissionLevel getLevel() {
            return level;
        }

        public boolean isAutoApproved(PermissionType permissionType) {
            return autoApproved.getOrDefault(permissionType, false);
        }

        public void setAutoApproved(PermissionType permissionType, boolean approved) {
            autoApproved.put(permissionType, approved);
        }
    }
}

package com.javacodeagent.core.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * JWT 令牌颁发端点（公开路径，不需要认证）。
 *
 * <p>仅在 {@code security.jwt.secret} 配置后生效。
 * 生产环境中此端点应接入真实的用户凭证验证逻辑（数据库 / LDAP / OAuth2 upstream）。
 * 当前为演示实现：直接以传入的 userId 生成令牌。
 *
 * <pre>
 * POST /api/v1/auth/token
 * {"userId": "alice"}
 * → {"token": "eyJhbGciO..."}
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;

    @PostMapping("/token")
    public Mono<ResponseEntity<Map<String, String>>> generateToken(
            @RequestBody Map<String, String> body) {

        if (!jwtService.isEnabled()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "JWT not configured — set security.jwt.secret")));
        }

        String userId = body.get("userId");
        if (userId == null || userId.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(Map.of("error", "userId is required")));
        }

        String token = jwtService.generateToken(userId.trim());
        log.info("JWT token issued for userId={}", userId.trim());
        return Mono.just(ResponseEntity.ok(Map.of("token", token, "userId", userId.trim())));
    }
}

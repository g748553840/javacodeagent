package com.javacodeagent.config;

import com.javacodeagent.core.auth.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * JWT 认证过滤器（优先级高于 ApiKeyAuthFilter）。
 *
 * <p>仅在配置了 {@code security.jwt.secret} 时激活。未配置则直接放行所有请求，
 * 由 {@link ApiKeyAuthFilter} 继续处理 API Key 认证逻辑。
 *
 * <p>认证流程：
 * <ol>
 *   <li>公开路径 → 直接放行</li>
 *   <li>提取 {@code Authorization: Bearer <token>} 头</li>
 *   <li>JWT 有效 → 将 userId 写入 exchange attribute {@code "authenticated-user-id"}</li>
 *   <li>JWT 无效/缺失 → 返回 401</li>
 * </ol>
 *
 * <p>下游 Controller 通过 {@code exchange.getAttribute("authenticated-user-id")} 获取 userId，
 * 无需额外改造 X-User-Id 请求头。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthFilter implements WebFilter {

    static final String USER_ID_ATTR = "authenticated-user-id";

    private static final String[] PUBLIC_PATHS = {
        "/api/v1/health", "/h2-console", "/actuator", "/api/v1/auth"
    };

    private final JwtService jwtService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!jwtService.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        for (String pub : PUBLIC_PATHS) {
            if (path.startsWith(pub)) {
                return chain.filter(exchange);
            }
        }

        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }

        String token = auth.substring(7).trim();
        String userId = jwtService.extractUserId(token);
        if (userId == null) {
            return unauthorized(exchange, "Invalid or expired JWT token");
        }

        // Inject userId as exchange attribute so downstream controllers can read it
        exchange.getAttributes().put(USER_ID_ATTR, userId);
        log.debug("JWT authenticated: userId={} path={}", userId, path);
        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        log.warn("JWT auth rejected [{}]: {}", exchange.getRequest().getPath().value(), reason);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}

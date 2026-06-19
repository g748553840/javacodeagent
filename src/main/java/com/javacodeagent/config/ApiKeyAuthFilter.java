package com.javacodeagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * HTTP API Key 认证过滤器。
 *
 * 配置方式（application.yml）：
 *   security:
 *     api-key: your-secret-key   # 留空或不配置则关闭认证
 *
 * 客户端传递方式（任选其一）：
 *   Authorization: Bearer your-secret-key
 *   X-API-Key: your-secret-key
 *
 * 以下路径无需认证：
 *   /api/v1/health、/h2-console/**、/actuator/**
 */
@Slf4j
@Component
public class ApiKeyAuthFilter implements WebFilter {

    @Value("${security.api-key:}")
    private String configuredApiKey;

    private static final String[] PUBLIC_PATHS = {
        "/api/v1/health", "/h2-console", "/actuator"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 未配置 api-key 则跳过认证
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        for (String pub : PUBLIC_PATHS) {
            if (path.startsWith(pub)) {
                return chain.filter(exchange);
            }
        }

        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        String xApiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");

        boolean valid = false;
        if (auth != null && auth.startsWith("Bearer ")) {
            valid = configuredApiKey.equals(auth.substring(7).trim());
        } else if (xApiKey != null) {
            valid = configuredApiKey.equals(xApiKey.trim());
        }

        if (!valid) {
            log.warn("Unauthorized request to {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}

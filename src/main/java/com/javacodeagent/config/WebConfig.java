package com.javacodeagent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebFluxConfigurer {

    private final CorsConfig corsConfig;

    /**
     * 按配置的白名单开放 CORS；未配置任何来源时不注册跨域映射
     * （浏览器同源策略生效，跨域请求被拒绝）。
     * 刻意不提供 "*" 通配符选项：一旦允许携带 Authorization 头的跨域请求，
     * 通配符来源会让任意网站以受害者身份读取 JWT/API Key 保护的响应数据。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsConfig.getAllowedOrigins().isEmpty()) {
            log.warn("security.cors.allowed-origins is empty — cross-origin browser requests to /api/** "
                + "will be rejected by same-origin policy. Configure explicit origins if a separate frontend needs access.");
            return;
        }
        registry.addMapping("/api/**")
            .allowedOrigins(corsConfig.getAllowedOrigins().toArray(new String[0]))
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*");
    }
}

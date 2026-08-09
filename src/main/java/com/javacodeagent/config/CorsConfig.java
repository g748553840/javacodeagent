package com.javacodeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * CORS 跨域配置（绑定 application.yml 中的 security.cors.* 节点）。
 *
 * <pre>
 * security:
 *   cors:
 *     allowed-origins: "https://your-frontend.example.com,https://another.example.com"
 * </pre>
 *
 * 默认不配置任何来源（空列表），此时 {@link WebConfig} 不会注册 CORS 映射，
 * 浏览器跨域请求按同源策略默认被拒绝。生产环境必须显式配置允许的前端域名，
 * 不提供通配符 "*" 便捷项，避免误配为全放开。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "security.cors")
public class CorsConfig {
    private List<String> allowedOrigins = List.of();
}

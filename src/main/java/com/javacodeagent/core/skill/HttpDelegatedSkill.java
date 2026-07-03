package com.javacodeagent.core.skill;

import com.javacodeagent.core.model.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * 将 Skill 执行委托给外部 HTTP 端点。
 *
 * 请求：POST/GET <url>，Body = SkillInput.parameters（JSON）
 * 响应：JSON，需包含 "output" 字段；可选 "success"、"error"、"data" 字段。
 * 若远端返回非 2xx 或超时，自动封装为 SkillResult.error()。
 */
@Slf4j
public class HttpDelegatedSkill implements Skill {

    private final ExternalSkillDescriptor descriptor;
    private final WebClient webClient;

    public HttpDelegatedSkill(ExternalSkillDescriptor descriptor, WebClient.Builder webClientBuilder) {
        this.descriptor = descriptor;
        WebClient.Builder builder = webClientBuilder.clone()
            .baseUrl(descriptor.getExecution().getUrl());
        if (descriptor.getExecution().getHeaders() != null) {
            descriptor.getExecution().getHeaders()
                .forEach((k, v) -> builder.defaultHeader(k, v));
        }
        this.webClient = builder.build();
    }

    @Override
    public String getName() {
        return descriptor.getName();
    }

    @Override
    public String getDescription() {
        return descriptor.getDescription();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return descriptor.getParameterSchema() != null
            ? descriptor.getParameterSchema()
            : Map.of("type", "object", "properties", Map.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public SkillResult execute(SkillInput input, ExecutionContext context) {
        ExternalSkillDescriptor.Execution exec = descriptor.getExecution();
        int timeout = exec.getTimeoutSeconds() > 0 ? exec.getTimeoutSeconds() : 30;

        try {
            Map<String, Object> responseBody = webClient.method(
                    org.springframework.http.HttpMethod.valueOf(exec.getMethod().toUpperCase()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input.getParameters() != null ? input.getParameters() : Map.of())
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(timeout))
                .block();

            if (responseBody == null) {
                return SkillResult.error("External skill returned empty response");
            }

            String output = responseBody.containsKey("output")
                ? String.valueOf(responseBody.get("output"))
                : responseBody.toString();

            boolean success = responseBody.containsKey("success")
                ? Boolean.parseBoolean(String.valueOf(responseBody.get("success")))
                : true;

            String error = (String) responseBody.get("error");

            return SkillResult.builder()
                .success(success)
                .output(output)
                .error(error)
                .data((Map<String, Object>) responseBody.get("data"))
                .build();

        } catch (Exception e) {
            log.error("HttpDelegatedSkill [{}] execution failed: {}", getName(), e.getMessage());
            return SkillResult.error("HTTP call failed: " + e.getMessage());
        }
    }
}

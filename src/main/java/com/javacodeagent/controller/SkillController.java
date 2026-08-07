package com.javacodeagent.controller;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.skill.ExternalSkillDescriptor;
import com.javacodeagent.core.skill.ExternalSkillLoader;
import com.javacodeagent.core.skill.HttpDelegatedSkill;
import com.javacodeagent.core.skill.Skill;
import com.javacodeagent.core.skill.SkillInput;
import com.javacodeagent.core.skill.SkillManager;
import com.javacodeagent.core.skill.SkillResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Skill 管理 REST API。
 *
 * GET    /api/v1/skills                   — 列出所有已注册 Skill
 * POST   /api/v1/skills/register          — 动态注册一个外部 HTTP Skill
 * POST   /api/v1/skills/reload            — 从文件目录重新扫描加载
 * DELETE /api/v1/skills/{name}            — 注销指定 Skill
 * POST   /api/v1/skills/{name}/execute    — 执行指定 Skill（管理调试用）
 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillManager skillManager;
    private final ExternalSkillLoader externalSkillLoader;
    private final WebClient.Builder webClientBuilder;

    /** 列出所有已注册 Skill 的元信息 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSkills() {
        List<Map<String, Object>> result = skillManager.listSkills().stream()
            .map(s -> Map.<String, Object>of(
                "name", s.getName(),
                "description", s.getDescription() != null ? s.getDescription() : "",
                "type", s instanceof HttpDelegatedSkill ? "external-http" : "builtin",
                "parameterSchema", s.getParameterSchema() != null ? s.getParameterSchema() : Map.of()
            ))
            .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 动态注册一个外部 HTTP Skill。
     * Body 格式同 ExternalSkillDescriptor：
     * <pre>
     * {
     *   "name": "my-skill",
     *   "description": "...",
     *   "execution": { "type": "http", "url": "http://host/path", "method": "POST" }
     * }
     * </pre>
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerSkill(
            @RequestBody ExternalSkillDescriptor descriptor) {

        if (descriptor.getName() == null || descriptor.getName().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", "name is required"));
        }
        if (descriptor.getExecution() == null || descriptor.getExecution().getUrl() == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", "execution.url is required"));
        }
        try {
            com.javacodeagent.core.skill.SkillUrlValidator.validateNotInternal(descriptor.getExecution().getUrl());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", e.getMessage()));
        }

        HttpDelegatedSkill skill = new HttpDelegatedSkill(descriptor, webClientBuilder);
        skillManager.registerSkill(skill);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Skill registered: " + descriptor.getName()
        ));
    }

    /** 重新扫描 skills.location 目录，热加载所有 .yml 描述符 */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadSkills() {
        List<String> loaded = externalSkillLoader.reload();
        return ResponseEntity.ok(Map.of(
            "success", true,
            "loaded", loaded,
            "count", loaded.size()
        ));
    }

    /** 注销指定 Skill（内置 Skill 也可被注销，重启后恢复） */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> unregisterSkill(@PathVariable String name) {
        boolean removed = skillManager.unregisterSkill(name);
        if (!removed) {
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "error", "Skill not found: " + name));
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "Skill unregistered: " + name));
    }

    /** 直接执行指定 Skill（管理调试用）
     *
     * <p>{@link HttpDelegatedSkill#execute} 内部对 WebClient 的 Mono 调用了
     * {@code .block()}（阻塞调用）；WebFlux 控制器方法若直接返回同步 {@code ResponseEntity}，
     * Spring 会在 Netty event-loop 线程上调用本方法，而 event-loop 线程不允许阻塞——
     * 轻则每次调用报 "block()/blockFirst()/blockLast() are blocked" 异常（被内部 catch
     * 吞掉，返回误导性的 "HTTP call failed"），重则并发调用会占满本就有限的
     * event-loop 线程池，拖慢其他与此无关的请求。
     * 改为返回 {@code Mono} 并显式 {@code subscribeOn(boundedElastic())}，
     * 让阻塞调用发生在专用的弹性线程池而非 IO 事件循环线程上。
     */
    @PostMapping("/{name}/execute")
    public Mono<ResponseEntity<SkillResult>> executeSkill(
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> parameters,
            @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {

        ExecutionContext ctx = ExecutionContext.builder()
            .userId(userId)
            .build();
        SkillInput input = new SkillInput(name, parameters != null ? parameters : Map.of());
        return Mono.fromCallable(() -> skillManager.executeSkill(name, input, ctx))
            .subscribeOn(Schedulers.boundedElastic())
            .map(result -> result.isSuccess()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result));
    }
}

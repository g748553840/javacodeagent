package com.javacodeagent.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.javacodeagent.config.SkillConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时从 skills.location 目录加载外部 Skill 描述符（.yml 文件），
 * 将每个描述符包装成 {@link HttpDelegatedSkill} 注册到 {@link SkillManager}。
 *
 * 支持通过 {@link #reload()} 在运行时重新扫描目录（增量注册）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalSkillLoader {

    private final SkillConfig skillConfig;
    private final SkillManager skillManager;
    private final WebClient.Builder webClientBuilder;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void init() {
        if (!skillConfig.isEnabled() || skillConfig.getLocation() == null) {
            log.debug("External skill loading disabled or location not configured");
            return;
        }
        reload();
    }

    /**
     * 扫描 skills.location 目录，加载所有 .yml 描述符并注册。
     * 已存在同名 Skill 时会覆盖（热更新）。
     *
     * @return 本次成功加载的 Skill 名称列表
     */
    public List<String> reload() {
        String location = skillConfig.getLocation();
        Path dir = Paths.get(location);
        List<String> loaded = new ArrayList<>();

        if (!Files.isDirectory(dir)) {
            log.info("Skills directory does not exist, skipping load: {}", dir);
            return loaded;
        }

        try (var stream = Files.walk(dir, 1)) {
            stream.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                .forEach(file -> {
                    try {
                        ExternalSkillDescriptor desc = yamlMapper.readValue(
                            file.toFile(), ExternalSkillDescriptor.class);

                        if (desc.getName() == null || desc.getName().isBlank()) {
                            log.warn("Skill descriptor missing 'name', skipping: {}", file);
                            return;
                        }
                        if (desc.getExecution() == null || desc.getExecution().getUrl() == null) {
                            log.warn("Skill [{}] missing execution.url, skipping: {}", desc.getName(), file);
                            return;
                        }

                        HttpDelegatedSkill skill = new HttpDelegatedSkill(desc, webClientBuilder);
                        skillManager.registerSkill(skill);
                        loaded.add(desc.getName());
                        log.info("Loaded external skill: {} ({})", desc.getName(), file.getFileName());
                    } catch (IOException e) {
                        log.error("Failed to parse skill descriptor: {}", file, e);
                    }
                });
        } catch (IOException e) {
            log.error("Failed to scan skills directory: {}", dir, e);
        }

        return loaded;
    }
}

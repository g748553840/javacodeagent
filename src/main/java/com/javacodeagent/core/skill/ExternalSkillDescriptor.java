package com.javacodeagent.core.skill;

import lombok.Data;

import java.util.Map;

/**
 * 外部 Skill 描述符，对应 skill 目录下的 .yml 文件格式：
 *
 * <pre>
 * name: my-skill
 * description: "Does something custom"
 * version: "1.0"
 * parameterSchema:
 *   type: object
 *   properties:
 *     input:
 *       type: string
 *       description: "Input text"
 *   required: [input]
 * execution:
 *   type: http
 *   url: http://localhost:9000/skills/my-skill
 *   method: POST
 *   timeoutSeconds: 30
 *   headers:
 *     Authorization: "Bearer token"
 * </pre>
 */
@Data
public class ExternalSkillDescriptor {
    private String name;
    private String description;
    private String version;
    private Map<String, Object> parameterSchema;
    private Execution execution;

    @Data
    public static class Execution {
        /** 当前仅支持 "http"，保留扩展 */
        private String type = "http";
        private String url;
        private String method = "POST";
        private int timeoutSeconds = 30;
        private Map<String, String> headers;
    }
}

package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One versioned UTF-8 resource is packaged by both Java and the local AI gateway. */
public final class RolePromptResource {
    private static final JsonNode PROMPT = load();
    public static final String SYSTEM = PROMPT.get("system").asText();
    public static final String INSTRUCTION = PROMPT.get("instruction").asText();
    private RolePromptResource() {}
    private static JsonNode load() {
        try (var input = RolePromptResource.class.getResourceAsStream("/ai/role-assignment.json")) {
            if (input == null) throw new IllegalStateException("缺少角色提示词资源");
            var value = new ObjectMapper().readTree(input);
            if (!value.path("version").asText().equals("1") || value.path("system").asText().isBlank()
                    || value.path("instruction").asText().isBlank()) throw new IllegalStateException("角色提示词资源无效");
            return value;
        } catch (Exception error) { throw new IllegalStateException("无法加载角色提示词资源", error); }
    }
}

package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/** 在服务商响应留存为仅后端可见的审计数据前执行脱敏。 */
public final class AsrRawResponse {
    private AsrRawResponse() {}

    public static String sanitizedJson(JsonNode source) {
        if (source == null || source.isNull()) return null;
        return sanitize(source).toString();
    }

    public static String sha256(String value) {
        if (value == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("ASR 原始响应哈希生成失败", e);
        }
    }

    private static JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.contains("authorization") || key.contains("api_key") || key.contains("apikey")
                        || key.equals("token") || key.endsWith("_token") || key.contains("secret")) {
                    copy.put(field.getKey(), "[REDACTED]");
                } else if (field.getValue().isTextual() && key.contains("url")) {
                    copy.put(field.getKey(), stripQuery(field.getValue().asText()));
                } else {
                    copy.set(field.getKey(), sanitize(field.getValue()));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> copy.add(sanitize(value)));
            return copy;
        }
        return node;
    }

    private static String stripQuery(String value) {
        try {
            URI uri = URI.create(value);
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            int query = value.indexOf('?');
            return query < 0 ? value : value.substring(0, query);
        }
    }
}

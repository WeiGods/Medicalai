package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class DashScopeAsrClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final boolean diarizationEnabled;

    public DashScopeAsrClient(
            ObjectMapper objectMapper,
            @Value("${medicalai.dashscope.endpoint:https://dashscope.aliyuncs.com}") String endpoint,
            @Value("${medicalai.dashscope.api-key:}") String apiKey,
            @Value("${medicalai.dashscope.asr-model:qwen-audio-3.0-asr-flash-filetrans}") String model,
            @Value("${medicalai.dashscope.diarization-enabled:true}") boolean diarizationEnabled) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.diarizationEnabled = diarizationEnabled;
        this.client = RestClient.builder().baseUrl(endpoint).build();
    }

    public String submit(String fileUrl) {
        if (apiKey.isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "DASHSCOPE_NOT_CONFIGURED", "未配置 DASHSCOPE_API_KEY");
        }
        try {
            JsonNode response = client.post().uri("/api/v1/services/audio/asr/transcription")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model,
                            "input", Map.of("file_urls", List.of(fileUrl)),
                            "parameters", Map.of("channel_id", List.of(0), "diarization_enabled", diarizationEnabled)))
                    .retrieve().body(JsonNode.class);
            String taskId = text(response == null ? null : response.at("/output/task_id"));
            if (taskId.isBlank()) throw new IllegalStateException("DashScope response did not contain output.task_id");
            return taskId;
        } catch (Exception e) {
            throw unavailable("DashScope ASR 提交失败", e);
        }
    }

    public Task query(String taskId) {
        try {
            JsonNode response = client.get().uri("/api/v1/tasks/{taskId}", taskId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve().body(JsonNode.class);
            JsonNode output = response == null ? null : response.path("output");
            String status = text(output == null ? null : output.path("task_status"));
            if (status.isBlank()) status = text(output == null ? null : output.path("status"));
            return new Task(taskId, status.toUpperCase(Locale.ROOT), output == null ? objectMapper.createObjectNode() : output);
        } catch (Exception e) {
            throw unavailable("DashScope ASR 查询失败", e);
        }
    }

    public List<Segment> result(Task task) {
        JsonNode result = task.output();
        String resultUrl = findUrl(result);
        if (!resultUrl.isBlank()) {
            try {
                result = client.get().uri(java.net.URI.create(resultUrl))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .retrieve().body(JsonNode.class);
            } catch (Exception e) {
                throw unavailable("DashScope ASR 结果下载失败", e);
            }
        }
        List<Segment> segments = new ArrayList<>();
        collectSegments(result, segments);
        return segments;
    }

    private void collectSegments(JsonNode node, List<Segment> segments) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(item -> collectSegments(item, segments));
            return;
        }
        if (!node.isObject()) return;
        String text = firstText(node, "text", "sentence", "transcript");
        Integer speakerId = firstInteger(node, "speaker_id", "speakerId");
        Long start = firstNumber(node, "begin_time", "start_time", "start_ms", "startMs", "start");
        Long end = firstNumber(node, "end_time", "end_time_ms", "end_ms", "endMs", "end");
        if (!text.isBlank() && start != null && end != null) {
            segments.add(new Segment(text.strip(), Math.max(0, start), Math.max(start, end), speakerId));
            return;
        }
        node.fields().forEachRemaining(entry -> collectSegments(entry.getValue(), segments));
    }

    private String findUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isObject()) {
            for (String key : List.of("transcription_url", "transcriptionUrl", "result_url", "resultUrl")) {
                String value = text(node.get(key));
                if (!value.isBlank()) return value;
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                String value = findUrl(values.next());
                if (!value.isBlank()) return value;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String value = findUrl(item);
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node.get(name));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private Long firstNumber(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            if (value.isNumber()) return value.longValue();
            try { return Long.parseLong(value.asText()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private Integer firstInteger(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) continue;
            try { return value.isNumber() ? value.intValue() : Integer.valueOf(value.asText()); }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "" : node.asText("");
    }

    private BusinessException unavailable(String message, Exception cause) {
        if (cause instanceof BusinessException business) return business;
        if (cause instanceof RestClientResponseException response) {
            String body = response.getResponseBodyAsString();
            if (body.length() > 500) body = body.substring(0, 500) + "…";
            return new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_UNAVAILABLE",
                    message + "（" + body + "）", cause);
        }
        return new BusinessException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_UNAVAILABLE", message, cause);
    }

    public record Task(String taskId, String status, JsonNode output) {}
    public record Segment(String text, long startMs, long endMs, Integer speakerId) {}
}

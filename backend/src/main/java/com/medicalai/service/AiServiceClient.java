package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AiServiceClient {
    private static final Logger LOG = LoggerFactory.getLogger(AiServiceClient.class);
    private final RestClient client;
    private final String baseUrl;

    public AiServiceClient(@Value("${medicalai.ai.base-url:http://127.0.0.1:8000}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    public GenerateResponse generate(String snapshotHash, List<Map<String, Object>> dialogue, Map<String, Object> patient) {
        LOG.info("AI record generation started: endpoint=/internal/medical-record/generate, snapshotHash={}, dialogueItems={}, baseUrl={}",
                safe(snapshotHash), dialogue == null ? 0 : dialogue.size(), baseUrl);
        try {
            GenerateResponse response = client.post().uri("/internal/medical-record/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("visit_id", "visit", "snapshot_hash", snapshotHash, "dialogue", dialogue, "patient", patient))
                    .retrieve().body(GenerateResponse.class);
            LOG.info("AI record generation succeeded: jobId={}, status={}, snapshotHash={}",
                    response == null ? null : response.jobId(), response == null ? null : response.status(), safe(snapshotHash));
            return response;
        } catch (Exception e) {
            logFailure("/internal/medical-record/generate", "snapshotHash=" + safe(snapshotHash), e);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "病历生成服务暂不可用", e);
        }
    }

    public Map<Integer, String> assignRoles(List<Map<String, Object>> turns) {
        try {
            Map response = client.post().uri("/internal/transcript/assign-roles")
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("turns", turns)).retrieve().body(Map.class);
            Map<Integer, String> result = new LinkedHashMap<>();
            if (response != null && response.get("roles") instanceof Map roles) {
                roles.forEach((k, v) -> { try { result.put(Integer.valueOf(String.valueOf(k)), normalizeRole(String.valueOf(v))); } catch (Exception ignored) {} });
            }
            return result;
        } catch (Exception e) {
            LOG.warn("Speaker role assignment unavailable; using OTHER");
            return Map.of();
        }
    }

    private String normalizeRole(String role) {
        return switch (role.toUpperCase()) { case "DOCTOR" -> "DOCTOR"; case "PATIENT" -> "PATIENT"; default -> "OTHER"; };
    }

    private void logFailure(String endpoint, String details, Exception error) {
        if (error instanceof RestClientResponseException response) {
            String body = response.getResponseBodyAsString();
            if (body != null && body.length() > 500) body = body.substring(0, 500) + "…";
            LOG.error("AI request failed: endpoint={}, {}, status={}, responseBody={}", endpoint, details,
                    response.getStatusCode(), safe(body), error);
        } else {
            LOG.error("AI request failed: endpoint={}, {}, exceptionType={}, message={}", endpoint, details,
                    error.getClass().getName(), safe(error.getMessage()), error);
        }
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    public record GenerateResponse(String jobId, String status, String sourceSnapshotHash,
                                   Map<String, Object> record) {}
}

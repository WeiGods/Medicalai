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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AiServiceClient {
    private static final Logger LOG = LoggerFactory.getLogger(AiServiceClient.class);
    private final RestClient client;
    private final String baseUrl;

    public AiServiceClient(@Value("${medicalai.internal-ai.base-url:${medicalai.ai.base-url:http://127.0.0.1:8000}}") String baseUrl) {
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

    /** 内网快照专用提取端点；公网快照必须由 DashScopeClinicalExtractionClient 直连处理。 */
    public ExtractionResponse extract(String snapshotHash, List<Map<String, Object>> turns) {
        long startedAt = System.nanoTime();
        LOG.info("内网信息提取 LLM 请求: route=LOCAL, snapshotHash={}, turns={}, chars={}, baseUrl={}",
                shortHash(snapshotHash), turns == null ? 0 : turns.size(), turnCharacters(turns), baseUrl);
        try {
            ExtractionResponse response = client.post().uri("/internal/clinical-extraction/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("snapshot_hash", snapshotHash, "turns", turns))
                    .retrieve().body(ExtractionResponse.class);
            LOG.info("内网信息提取 LLM 响应: route=LOCAL, jobId={}, status={}, model={}, snapshotHash={}, fields={}, elapsedMs={}",
                    response == null ? null : response.jobId(), response == null ? null : response.status(),
                    response == null ? null : response.model(), shortHash(snapshotHash),
                    response == null ? 0 : populatedFields(response.extraction()), elapsedMs(startedAt));
            return response;
        } catch (ResourceAccessException error) {
            // 提取链路不允许模板降级。连接失败通常表示独立网关没有启动，明确提示部署问题，
            // 避免医生误以为可通过重复点击修复，或误把不可追溯内容写入病历。
            logExtractionFailure(snapshotHash, startedAt, error, null);
            logFailure("/internal/clinical-extraction/generate", "snapshotHash=" + shortHash(snapshotHash), error);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_OFFLINE",
                    "信息提取服务未启动或无法连接（" + baseUrl + "），请启动文本提取网关后重试", error);
        } catch (RestClientResponseException error) {
            logExtractionFailure(snapshotHash, startedAt, error, error.getStatusCode().value());
            logFailure("/internal/clinical-extraction/generate", "snapshotHash=" + shortHash(snapshotHash), error);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_EXTRACTION_UNAVAILABLE",
                    extractionFailureMessage(error), error);
        } catch (Exception error) {
            logExtractionFailure(snapshotHash, startedAt, error, null);
            logFailure("/internal/clinical-extraction/generate", "snapshotHash=" + shortHash(snapshotHash), error);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "信息提取服务暂不可用，请稍后重试", error);
        }
    }

    /**
     * 调用内网角色识别服务。调用失败只返回 FALLBACK，绝不把本地转写文本发送到公网模型补救。
     */
    public Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoleAssignments(List<Map<String, Object>> turns) {
        Map<Integer, DashScopeRoleClient.RoleAssignment> fallback = roleFallback(turns);
        if (turns == null || turns.isEmpty()) return fallback;
        try {
            Map response = client.post().uri("/internal/transcript/assign-roles")
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("turns", turns)).retrieve().body(Map.class);
            if (response == null || !(response.get("items") instanceof List<?> items)) return fallback;
            Map<Integer, DashScopeRoleClient.RoleAssignment> result = new LinkedHashMap<>();
            for (Object value : items) {
                if (!(value instanceof Map<?, ?> item)) return fallback;
                Integer index = integer(item.get("index"));
                String role = normalizeRole(String.valueOf(item.get("role")));
                Integer confidence = integer(item.get("confidence"));
                String source = String.valueOf(item.get("source")).strip().toUpperCase(java.util.Locale.ROOT);
                if (index == null || !fallback.containsKey(index) || confidence == null || confidence < 0 || confidence > 100
                        || !"LLM".equals(source) || result.put(index,
                        new DashScopeRoleClient.RoleAssignment(role, confidence, source)) != null) {
                    return fallback;
                }
            }
            return result.keySet().equals(fallback.keySet()) ? result : fallback;
        } catch (Exception error) {
            LOG.warn("Internal role assignment failed: endpoint={}/internal/transcript/assign-roles, exception={}",
                    baseUrl, error.getClass().getSimpleName());
            return fallback;
        }
    }

    /**
     * 仅把 AI 网关返回的受控 detail 透传给界面，便于区分缺少模型配置、超时和格式校验失败。
     * 绝不透传调用栈、请求体或密钥，防止医疗对话和部署凭据出现在医生端。
     */
    private String extractionFailureMessage(RestClientResponseException error) {
        String body = error.getResponseBodyAsString();
        if (body != null && body.contains("未配置结构化信息提取模型")) {
            return "内网信息提取模型未配置，请设置 LLM_API_BASE/LLM_API_KEY 后重试";
        }
        if (body != null && body.contains("信息提取模型暂不可用")) {
            return "信息提取模型调用失败或超时，可稍后重试";
        }
        if (body != null && body.contains("信息提取模型未返回有效 JSON")) {
            return "信息提取模型输出不符合要求，未保存任何提取结果，可稍后重试";
        }
        if (body != null && body.contains("信息提取模型未返回完整标准字段")) {
            return "信息提取模型输出字段不完整，未保存任何提取结果，可稍后重试";
        }
        return "信息提取服务返回异常（HTTP " + error.getStatusCode().value() + "），未保存任何提取结果";
    }

    public Map<Integer, String> assignRoles(List<Map<String, Object>> turns) {
        Map<Integer, String> result = new LinkedHashMap<>();
        assignRoleAssignments(turns).forEach((index, assignment) -> result.put(index, assignment.role()));
        return result;
    }

    private String normalizeRole(String role) {
        return switch (role.strip().toUpperCase(java.util.Locale.ROOT)) { case "DOCTOR" -> "DOCTOR"; case "PATIENT" -> "PATIENT"; default -> "OTHER"; };
    }

    private Map<Integer, DashScopeRoleClient.RoleAssignment> roleFallback(List<Map<String, Object>> turns) {
        Map<Integer, DashScopeRoleClient.RoleAssignment> result = new LinkedHashMap<>();
        if (turns == null) return result;
        for (Map<String, Object> turn : turns) {
            Integer index = turn == null ? null : integer(turn.get("index"));
            if (index != null && index >= 0) {
                result.put(index, new DashScopeRoleClient.RoleAssignment("OTHER", null, "FALLBACK"));
            }
        }
        return result;
    }

    private Integer integer(Object value) {
        if (!(value instanceof Number number) || Math.floor(number.doubleValue()) != number.doubleValue()
                || number.doubleValue() < Integer.MIN_VALUE || number.doubleValue() > Integer.MAX_VALUE) return null;
        return number.intValue();
    }

    private void logFailure(String endpoint, String details, Exception error) {
        if (error instanceof RestClientResponseException response) {
            // 网关可能返回模型错误详情；日志只保留状态和异常类型，避免意外记录患者原文或提示词。
            LOG.error("AI request failed: endpoint={}, {}, status={}, exceptionType={}", endpoint, details,
                    response.getStatusCode(), error.getClass().getSimpleName(), error);
        } else {
            LOG.error("AI request failed: endpoint={}, {}, exceptionType={}, message={}", endpoint, details,
                    error.getClass().getName(), safe(error.getMessage()), error);
        }
    }

    private void logExtractionFailure(String snapshotHash, long startedAt, Exception error, Integer httpStatus) {
        LOG.warn("内网信息提取 LLM 失败: route=LOCAL, snapshotHash={}, httpStatus={}, exception={}, elapsedMs={}",
                shortHash(snapshotHash), httpStatus, error.getClass().getSimpleName(), elapsedMs(startedAt));
    }

    private int populatedFields(Map<String, Object> extraction) {
        Object fields = extraction == null ? null : extraction.get("fields");
        if (!(fields instanceof Map<?, ?> map)) return 0;
        return (int) map.values().stream().filter(java.util.Objects::nonNull).count();
    }

    private int turnCharacters(List<Map<String, Object>> turns) {
        if (turns == null) return 0;
        return turns.stream().map(turn -> turn == null ? null : turn.get("text"))
                .filter(String.class::isInstance).map(String.class::cast).mapToInt(String::length).sum();
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String shortHash(String value) {
        String result = safe(value);
        return result.substring(0, Math.min(12, result.length()));
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    public record GenerateResponse(String jobId, String status, String sourceSnapshotHash,
                                   Map<String, Object> record) {}
    public record ExtractionResponse(String jobId, String status, String sourceSnapshotHash,
                                     String model, Map<String, Object> extraction) {}
}

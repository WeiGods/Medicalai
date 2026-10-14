package com.medicalai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 内网本地 LLM（llama.cpp / OpenAI 兼容）的结构化事实提取适配器。
 *
 * <p>与 {@link LocalLlmRoleClient} 同构：直连 {@code POST /v1/chat/completions}，与公网路由
 * {@link DashScopeClinicalExtractionClient} 共用 {@link ClinicalExtractionLlmClient} 的提示词、
 * JSON Schema 与解析规则，因此两条路由的提取口径一致。
 *
 * <p>默认不启用（{@code medicalai.local-llm.extraction-enabled}，未显式配置时跟随
 * {@code LOCAL_LLM_ENABLED}）。未启用时不会发出任何网络请求。
 * 失败重试沿用 {@code role-retry-*} 配置（本地 LLM 的通用调用重试）。
 */
@Service
public class LocalLlmExtractionClient implements ClinicalExtractionLlmClient {
    private static final Logger LOG = LoggerFactory.getLogger(LocalLlmExtractionClient.class);
    private static final String ROUTE_LABEL = "内网";
    private static final String JOB_ID = "local-llm-extract";
    private static final int MIN_MAX_TOKENS = 512;
    private static final int MAX_MAX_TOKENS = 32768;
    private static final int MAX_RETRY_MAX_ATTEMPTS = 3;
    private static final int MAX_RETRY_DELAY_MS = 10_000;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final boolean disableThinking;
    private final int retryMaxAttempts;
    private final Duration retryDelay;

    public LocalLlmExtractionClient(
            ObjectMapper mapper,
            @Value("${medicalai.local-llm.extraction-enabled:${medicalai.local-llm.enabled:false}}") boolean enabled,
            @Value("${medicalai.local-llm.endpoint:http://192.168.1.56:8080}") String endpoint,
            @Value("${medicalai.local-llm.api-key:}") String apiKey,
            @Value("${medicalai.local-llm.extraction-model:/models/Ternary-Bonsai-2-27B-PQ2_0.gguf}") String model,
            @Value("${medicalai.local-llm.extraction-max-tokens:4096}") int maxTokens,
            @Value("${medicalai.local-llm.extraction-disable-thinking:true}") boolean disableThinking,
            @Value("${medicalai.local-llm.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${medicalai.local-llm.extraction-read-timeout-ms:180000}") long readTimeoutMs,
            @Value("${medicalai.local-llm.role-retry-max-attempts:2}") int retryMaxAttempts,
            @Value("${medicalai.local-llm.role-retry-delay-ms:200}") long retryDelayMs) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.endpoint = requireText("LOCAL_LLM_ENDPOINT", endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = requireText("LOCAL_LLM_EXTRACTION_MODEL", model);
        this.maxTokens = clamp(maxTokens, MIN_MAX_TOKENS, MAX_MAX_TOKENS);
        this.disableThinking = disableThinking;
        this.retryMaxAttempts = clamp(retryMaxAttempts, 1, MAX_RETRY_MAX_ATTEMPTS);
        this.retryDelay = Duration.ofMillis(clamp(retryDelayMs, 0, MAX_RETRY_DELAY_MS));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMs)));
        this.client = RestClient.builder().baseUrl(this.endpoint).requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return enabled;
    }

    @Override
    public AiServiceClient.ExtractionResponse extract(String snapshotHash, List<Map<String, Object>> turns) {
        if (!enabled) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_LLM_EXTRACTION_DISABLED",
                    "内网信息提取未启用，请设置 LOCAL_LLM_EXTRACTION_ENABLED=true 后重试");
        }
        long startedAt = System.nanoTime();
        LOG.info("内网信息提取 LLM 请求: route=LOCAL, model={}, endpoint={}, snapshotHash={}, turns={}, chars={}, maxTokens={}",
                model, hostLabel(endpoint), shortHash(snapshotHash), turns == null ? 0 : turns.size(),
                ClinicalExtractionLlmClient.turnCharacters(turns), maxTokens);

        Exception failure = null;
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return extractOnce(snapshotHash, turns, startedAt, attempt);
            } catch (BusinessException error) {
                // 解析失败也重试一次：本地小模型偶发输出残缺，重试常能拿到合规 JSON。
                if (!"EXTRACTION_INVALID_RESPONSE".equals(error.code()) || attempt == retryMaxAttempts) {
                    LOG.warn("内网信息提取 LLM 失败: route=LOCAL, model={}, snapshotHash={}, code={}, elapsedMs={}",
                            model, shortHash(snapshotHash), error.code(), elapsedMs(startedAt));
                    throw error;
                }
                failure = error;
            } catch (Exception error) {
                failure = error;
                if (!shouldRetry(error) || attempt == retryMaxAttempts) {
                    break;
                }
            }
            LOG.warn("内网信息提取 LLM 准备重试: route=LOCAL, model={}, snapshotHash={}, 当前尝试={}, 异常类型={}",
                    model, shortHash(snapshotHash), attempt,
                    failure == null ? "unknown" : failure.getClass().getSimpleName());
            if (!waitForRetry()) {
                break;
            }
        }

        LOG.warn("内网信息提取 LLM 调用失败: route=LOCAL, model={}, snapshotHash={}, httpStatus={}, exception={}, elapsedMs={}",
                model, shortHash(snapshotHash), httpStatus(failure),
                failure == null ? "unknown" : failure.getClass().getSimpleName(), elapsedMs(startedAt));
        throw translate(failure, snapshotHash);
    }

    private AiServiceClient.ExtractionResponse extractOnce(String snapshotHash, List<Map<String, Object>> turns,
                                                           long startedAt, int attempt) throws JsonProcessingException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", ClinicalExtractionLlmClient.userMessage(mapper, turns))));
        request.put("max_tokens", maxTokens);
        request.put("temperature", 0);
        request.put("response_format", Map.of("type", "json_schema",
                "json_schema", Map.of("name", SCHEMA_NAME, "strict", true,
                        "schema", ClinicalExtractionLlmClient.schema(mapper))));
        if (disableThinking) {
            // 角色识别与提取都不需要思考过程；关掉可把每批耗时压到 1/2～1/3。
            request.put("chat_template_kwargs", Map.of("enable_thinking", false));
        }
        var requestBuilder = client.post().uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request);
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        JsonNode response = requestBuilder.retrieve().body(JsonNode.class);
        Map<String, Object> extraction = ClinicalExtractionLlmClient.parse(mapper, response, ROUTE_LABEL);
        LOG.info("内网信息提取 LLM 响应: route=LOCAL, model={}, snapshotHash={}, fields={}, promptTokens={}, completionTokens={}, attempt={}, elapsedMs={}",
                model, shortHash(snapshotHash), ClinicalExtractionLlmClient.populatedFields(extraction),
                response == null ? 0 : response.path("usage").path("prompt_tokens").asInt(0),
                response == null ? 0 : response.path("usage").path("completion_tokens").asInt(0),
                attempt, elapsedMs(startedAt));
        return new AiServiceClient.ExtractionResponse(JOB_ID, "SUCCEEDED", snapshotHash, model, extraction);
    }

    private boolean shouldRetry(Exception error) {
        if (error instanceof ResourceAccessException || error instanceof JsonProcessingException) {
            return true;
        }
        if (error instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value() == 429
                    || responseException.getStatusCode().is5xxServerError();
        }
        return false;
    }

    private boolean waitForRetry() {
        if (retryDelay.isZero()) {
            return true;
        }
        try {
            Thread.sleep(retryDelay.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOG.warn("内网信息提取 LLM 重试等待被中断");
            return false;
        }
    }

    /** 只向医生透出受控原因，绝不带出患者原文、提示词或密钥。 */
    private BusinessException translate(Exception error, String snapshotHash) {
        if (error instanceof ResourceAccessException) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_LLM_EXTRACTION_UNAVAILABLE",
                    "内网信息提取服务未启动或无法连接（" + endpoint + "），请检查 LOCAL_LLM_ENDPOINT", error);
        }
        if (error instanceof RestClientResponseException responseException) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_LLM_EXTRACTION_UNAVAILABLE",
                    "内网信息提取模型返回异常（HTTP " + responseException.getStatusCode().value()
                            + "），未保存任何提取结果", error);
        }
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_LLM_EXTRACTION_UNAVAILABLE",
                "内网信息提取模型暂不可用，未保存任何提取结果，可稍后重试", error);
    }

    private static Integer httpStatus(Exception error) {
        return error instanceof RestClientResponseException responseException
                ? responseException.getStatusCode().value()
                : null;
    }

    private static int clamp(long value, int minimum, int maximum) {
        return (int) Math.max(minimum, Math.min(maximum, value));
    }

    private static String requireText(String propertyName, String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(propertyName + " 不能为空");
        }
        return trimmed;
    }

    private static String hostLabel(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() == null ? "configured" : uri.getHost();
        } catch (IllegalArgumentException ignored) {
            return "configured";
        }
    }

    private static String shortHash(String value) {
        String safe = value == null ? "" : value;
        return safe.substring(0, Math.min(12, safe.length()));
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

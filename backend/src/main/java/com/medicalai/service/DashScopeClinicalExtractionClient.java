package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 公网 DashScope 的结构化事实提取客户端。
 *
 * <p>实现 {@link ClinicalExtractionLlmClient} 适配器契约，与内网路由 {@link LocalLlmExtractionClient}
 * 共用同一套系统提示词、提取指令、JSON Schema 与解析规则；失败一律抛出 {@link BusinessException}，
 * 由上层保存 FAILED 版本，绝不写模板兜底内容。
 */
@Service
public class DashScopeClinicalExtractionClient implements ClinicalExtractionLlmClient {
    private static final Logger LOG = LoggerFactory.getLogger(DashScopeClinicalExtractionClient.class);
    private static final String ROUTE_LABEL = "公网";

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final com.fasterxml.jackson.databind.node.ObjectNode schema;
    private final String endpointLabel;

    public DashScopeClinicalExtractionClient(
            ObjectMapper mapper,
            @Value("${medicalai.dashscope.endpoint:https://dashscope.aliyuncs.com}") String endpoint,
            @Value("${medicalai.dashscope.api-key:}") String apiKey,
            @Value("${medicalai.dashscope.extraction-model:qwen-plus}") String model,
            @Value("${medicalai.dashscope.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${medicalai.dashscope.extraction-timeout-ms:${medicalai.dashscope.read-timeout-ms:60000}}") long readTimeoutMs,
            @Value("${medicalai.dashscope.extraction-max-tokens:4096}") int maxTokens) {
        this.mapper = mapper;
        this.apiKey = trim(apiKey);
        this.model = required("DASHSCOPE_EXTRACTION_MODEL", model);
        this.maxTokens = Math.max(512, maxTokens);
        String configuredEndpoint = required("DASHSCOPE_ENDPOINT", endpoint);
        this.client = client(configuredEndpoint, connectTimeoutMs, readTimeoutMs);
        this.endpointLabel = endpointLabel(configuredEndpoint);
        this.schema = ClinicalExtractionLlmClient.schema(mapper);
    }

    /** 公网路由可用性：配置了密钥才算可用（与提取时的校验一致）。 */
    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public AiServiceClient.ExtractionResponse extract(String snapshotHash, List<Map<String, Object>> turns) {
        if (apiKey.isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_NOT_CONFIGURED",
                    "未配置 DASHSCOPE_API_KEY，无法使用公网信息提取");
        }
        long startedAt = System.nanoTime();
        LOG.info("公网信息提取 LLM 请求: route=DASHSCOPE, model={}, endpoint={}, snapshotHash={}, turns={}, chars={}, maxTokens={}",
                model, endpointLabel, shortHash(snapshotHash), turns == null ? 0 : turns.size(),
                ClinicalExtractionLlmClient.turnCharacters(turns), maxTokens);
        try {
            ResponseEntity<JsonNode> entity = client.post().uri("/compatible-mode/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "messages", List.of(
                                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                                    Map.of("role", "user", "content", ClinicalExtractionLlmClient.userMessage(mapper, turns))),
                            // 多字段和多条证据需要足够输出空间；截断结果会在后端被拒绝，绝不模板降级。
                            "max_tokens", maxTokens,
                            "temperature", 0,
                            "response_format", Map.of("type", "json_schema",
                                    "json_schema", Map.of("name", SCHEMA_NAME, "strict", true,
                                            "schema", schema))))
                    .retrieve().toEntity(JsonNode.class);
            JsonNode response = entity.getBody();
            Map<String, Object> extraction = ClinicalExtractionLlmClient.parse(mapper, response, ROUTE_LABEL);
            LOG.info("公网信息提取 LLM 响应: route=DASHSCOPE, model={}, snapshotHash={}, httpStatus={}, fields={}, promptTokens={}, completionTokens={}, totalTokens={}, elapsedMs={}",
                    model, shortHash(snapshotHash), entity.getStatusCode().value(),
                    ClinicalExtractionLlmClient.populatedFields(extraction),
                    response == null ? 0 : response.path("usage").path("prompt_tokens").asInt(0),
                    response == null ? 0 : response.path("usage").path("completion_tokens").asInt(0),
                    response == null ? 0 : response.path("usage").path("total_tokens").asInt(0), elapsedMs(startedAt));
            return new AiServiceClient.ExtractionResponse("dashscope-extract", "SUCCEEDED", snapshotHash, model, extraction);
        } catch (BusinessException error) {
            LOG.warn("公网信息提取 LLM 结果无效: route=DASHSCOPE, model={}, snapshotHash={}, code={}, elapsedMs={}",
                    model, shortHash(snapshotHash), error.code(), elapsedMs(startedAt));
            throw error;
        } catch (RestClientResponseException error) {
            LOG.warn("公网信息提取 LLM 调用失败: route=DASHSCOPE, model={}, snapshotHash={}, httpStatus={}, exception={}, elapsedMs={}",
                    model, shortHash(snapshotHash), error.getStatusCode().value(), error.getClass().getSimpleName(), elapsedMs(startedAt));
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_EXTRACTION_UNAVAILABLE",
                    "公网信息提取模型调用失败", error);
        } catch (Exception error) {
            LOG.warn("公网信息提取 LLM 调用异常: route=DASHSCOPE, model={}, snapshotHash={}, exception={}, elapsedMs={}",
                    model, shortHash(snapshotHash), error.getClass().getSimpleName(), elapsedMs(startedAt));
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_EXTRACTION_UNAVAILABLE",
                    "公网信息提取模型暂不可用", error);
        }
    }

    private static RestClient client(String endpoint, long connectTimeoutMs, long readTimeoutMs) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs))).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMs)));
        return RestClient.builder().baseUrl(endpoint).requestFactory(factory).build();
    }

    private static String endpointLabel(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() == null ? "configured" : uri.getHost();
        } catch (IllegalArgumentException ignored) {
            return "configured";
        }
    }

    private static String shortHash(String value) {
        String safe = trim(value);
        return safe.substring(0, Math.min(12, safe.length()));
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String trim(String value) { return value == null ? "" : value.strip(); }
    private static String required(String property, String value) {
        String result = trim(value);
        if (result.isBlank()) throw new IllegalArgumentException(property + " 不能为空");
        return result;
    }
}

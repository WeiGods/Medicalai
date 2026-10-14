package com.medicalai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 内网本地 LLM（llama.cpp / OpenAI 兼容）的角色识别适配器。
 *
 * <p>这是通信适配器网络层新增的一路：把 ASR 的 MOSS 匿名推理结果发送到内网本地 LLM 进行
 * speaker 识别（角色判断），与公网 DashScope 客户端互为替代路由。同步角色识别走
 * {@code POST /v1/chat/completions}（与 DashScope 客户端同语义：分批、重试、降级 FALLBACK）；
 * 另提供 SSE 流式对话能力 {@link #streamChat}，供流式输出场景使用。
 *
 * <p>请求语义与公网路由完全一致（同一套提示词、{@code temperature=0}、{@code response_format=json_object}），
 * 另外默认附带 {@code chat_template_kwargs.enable_thinking=false}，避免本地思考 token 成倍拉长每批耗时
 * （可用 {@code medicalai.local-llm.role-disable-thinking=false} 关掉这个附带字段）。
 *
 * <p>默认不启用（{@code medicalai.local-llm.enabled=false}）。未启用时 {@link #assignRoles}
 * 只会返回降级结果 (OTHER, null, FALLBACK)，绝不静默改走公网、也绝不发出任何网络请求。
 */
@Service
public class LocalLlmRoleClient implements RoleLlmClient {
    private static final Logger LOG = LoggerFactory.getLogger(LocalLlmRoleClient.class);
    private static final String ROLE_OTHER = "OTHER";
    private static final String SOURCE_LLM = "LLM";
    private static final String SOURCE_FALLBACK = "FALLBACK";
    private static final Set<String> ALLOWED_ROLES = Set.of("DOCTOR", "PATIENT", ROLE_OTHER);
    private static final int MIN_CONFIDENCE = 0;
    private static final int MAX_CONFIDENCE = 100;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 120_000;
    private static final Duration STREAM_OVERALL_TIMEOUT = Duration.ofMinutes(10);
    private static final int DEFAULT_RETRY_MAX_ATTEMPTS = 2;
    private static final int MAX_RETRY_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_RETRY_DELAY_MS = 200;
    private static final int MAX_RETRY_DELAY_MS = 10_000;
    private static final boolean DEFAULT_DISABLE_THINKING = true;

    private final RestClient client;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int reviewThreshold;
    private final int batchSize;
    private final boolean disableThinking;
    private final int retryMaxAttempts;
    private final Duration retryDelay;

    @Autowired
    public LocalLlmRoleClient(
            ObjectMapper mapper,
            @Value("${medicalai.local-llm.enabled:false}") boolean enabled,
            @Value("${medicalai.local-llm.endpoint:http://192.168.1.56:8080}") String endpoint,
            @Value("${medicalai.local-llm.api-key:}") String apiKey,
            @Value("${medicalai.local-llm.role-model:/models/Ternary-Bonsai-2-27B-PQ2_0.gguf}") String model,
            @Value("${medicalai.local-llm.role-review-threshold:70}") int reviewThreshold,
            @Value("${medicalai.local-llm.role-batch-size:40}") int batchSize,
            @Value("${medicalai.local-llm.role-disable-thinking:true}") boolean disableThinking,
            @Value("${medicalai.local-llm.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${medicalai.local-llm.read-timeout-ms:120000}") long readTimeoutMs,
            @Value("${medicalai.local-llm.role-retry-max-attempts:2}") int retryMaxAttempts,
            @Value("${medicalai.local-llm.role-retry-delay-ms:200}") long retryDelayMs) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.endpoint = requireText("LOCAL_LLM_ENDPOINT", endpoint);
        this.apiKey = trimToEmpty(apiKey);
        this.model = requireText("LOCAL_LLM_ROLE_MODEL", model);
        this.reviewThreshold = clamp(reviewThreshold, MIN_CONFIDENCE, MAX_CONFIDENCE);
        this.batchSize = clamp(batchSize, 1, MAX_BATCH_SIZE);
        this.disableThinking = disableThinking;
        this.retryMaxAttempts = clamp(retryMaxAttempts, 1, MAX_RETRY_MAX_ATTEMPTS);
        this.retryDelay = Duration.ofMillis(clamp(retryDelayMs, 0, MAX_RETRY_DELAY_MS));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .build();
        this.client = createRestClient(httpClient, this.endpoint, readTimeoutMs);
    }

    /**
     * 保持直接构造客户端的兼容性（默认启用、默认关闭思考），生产环境应使用可配置超时和重试参数的 Spring 构造器。
     */
    public LocalLlmRoleClient(
            ObjectMapper mapper,
            String endpoint,
            String apiKey,
            String model,
            int reviewThreshold,
            int batchSize) {
        this(mapper, true, endpoint, apiKey, model, reviewThreshold, batchSize, DEFAULT_DISABLE_THINKING,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS,
                DEFAULT_RETRY_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MS);
    }

    @Override
    public Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoles(List<Map<String, Object>> turns) {
        if (turns == null || turns.isEmpty()) {
            return Map.of();
        }

        Set<Integer> indexes = collectIndexes(turns);
        if (indexes.size() != turns.size()) {
            LOG.warn("本地LLM角色判断已跳过：模型={}，句段数={}，原因=句段索引无效", model, turns.size());
            return fallbackAssignments(indexes);
        }
        if (!enabled) {
            LOG.warn("本地LLM角色判断已跳过：模型={}，句段数={}，原因=未启用（LOCAL_LLM_ENABLED=true 时接入内网路由）",
                    model, turns.size());
            return fallbackAssignments(indexes);
        }

        long assignmentStartedAt = System.nanoTime();
        int batchCount = (turns.size() + batchSize - 1) / batchSize;
        LOG.info("本地LLM角色判断开始：模型={}，句段数={}，批次数={}，复核阈值={}",
                model, turns.size(), batchCount, reviewThreshold);

        Map<Integer, DashScopeRoleClient.RoleAssignment> assignments = new LinkedHashMap<>();
        boolean degraded = false;
        for (int start = 0; start < turns.size(); start += batchSize) {
            List<Map<String, Object>> batch = turns.subList(start, Math.min(start + batchSize, turns.size()));
            Set<Integer> batchIndexes = collectIndexes(batch);
            Map<Integer, DashScopeRoleClient.RoleAssignment> batchAssignments = assignBatch(
                    batch, batchIndexes, start / batchSize + 1, batchCount);
            assignments.putAll(batchAssignments);
            degraded |= batchAssignments.values().stream()
                    .anyMatch(assignment -> SOURCE_FALLBACK.equals(assignment.source()));
        }
        logAssignment(assignments, degraded, elapsedMs(assignmentStartedAt));
        return assignments;
    }

    @Override
    public boolean isConfigured() {
        return enabled;
    }

    /**
     * SSE 流式对话：向本地 LLM 发送 {@code stream=true} 的对话请求，逐 delta 回调并返回聚合文本。
     *
     * <p>流式输出使用原生 {@link HttpClient} + {@code BodyHandlers.ofLines()} 解析
     * {@code data:} 事件行（跳过空行与 {@code [DONE]}），取 {@code choices[0].delta.content}
     * （兼容非流式实现回退到 {@code choices[0].message.content}）。
     *
     * @param messages OpenAI 兼容的 messages 数组
     * @param onDelta  每次收到内容增量时的回调（可为 null）
     * @return 所有 delta 聚合后的完整内容
     */
    public String streamChat(List<Map<String, Object>> messages, Consumer<String> onDelta) {
        if (!enabled) {
            throw new IllegalStateException("本地LLM未启用，无法进行流式对话");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", messages,
                    "stream", true,
                    "temperature", 0));
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/v1/chat/completions"))
                    .timeout(STREAM_OVERALL_TIMEOUT)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<Stream<String>> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (Stream<String> ignored = response.body()) {
                    // 关闭未消费的响应体
                }
                throw new IllegalStateException("本地LLM流式对话失败：HTTP " + response.statusCode());
            }

            StringBuilder content = new StringBuilder();
            try (Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    String trimmed = line.strip();
                    if (!trimmed.startsWith("data:")) {
                        continue;
                    }
                    String payload = trimmed.substring("data:".length()).strip();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(payload);
                    String delta = node.at("/choices/0/delta/content").asText("");
                    if (delta.isEmpty()) {
                        delta = node.at("/choices/0/message/content").asText("");
                    }
                    if (delta.isEmpty()) {
                        continue;
                    }
                    content.append(delta);
                    if (onDelta != null) {
                        onDelta.accept(delta);
                    }
                }
            }
            return content.toString();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("本地LLM流式对话被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("本地LLM流式对话失败：" + exception.getMessage(), exception);
        }
    }

    private Map<Integer, DashScopeRoleClient.RoleAssignment> assignBatch(
            List<Map<String, Object>> turns, Set<Integer> indexes, int batchNumber, int batchCount) {
        Exception failure = null;
        long batchStartedAt = System.nanoTime();
        LOG.info("本地LLM角色调用开始：模型={}，批次={}/{}，句段数={}",
                model, batchNumber, batchCount, turns.size());

        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                Map<Integer, DashScopeRoleClient.RoleAssignment> assignments = requestAssignments(turns, indexes);
                LOG.info("本地LLM角色调用成功：模型={}，批次={}/{}，句段数={}，尝试次数={}，耗时毫秒={}",
                        model, batchNumber, batchCount, turns.size(), attempt, elapsedMs(batchStartedAt));
                return assignments;
            } catch (Exception exception) {
                failure = exception;
                if (!shouldRetry(exception) || attempt == retryMaxAttempts) {
                    break;
                }
                LOG.warn("本地LLM角色调用准备重试：模型={}，批次={}/{}，当前尝试={}，HTTP状态={}，异常类型={}",
                        model, batchNumber, batchCount, attempt, httpStatus(exception),
                        exception.getClass().getSimpleName());
                if (!waitForRetry()) {
                    break;
                }
            }
        }

        LOG.warn("本地LLM角色调用已降级：模型={}，批次={}/{}，句段数={}，耗时毫秒={}，HTTP状态={}，异常类型={}",
                model, batchNumber, batchCount, turns.size(), elapsedMs(batchStartedAt), httpStatus(failure),
                failure == null ? "unknown" : failure.getClass().getSimpleName());
        return fallback(indexes);
    }

    private Map<Integer, DashScopeRoleClient.RoleAssignment> requestAssignments(
            List<Map<String, Object>> turns, Set<Integer> indexes) throws JsonProcessingException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", ROLE_ASSIGNMENT_INSTRUCTION + mapper.writeValueAsString(turns))));
        request.put("temperature", 0);
        request.put("response_format", Map.of("type", "json_object"));
        if (disableThinking) {
            // llama.cpp 的 Qwen 系模板默认开启思考，思考 token 会成倍拉长每批耗时（4 句实测 11.8s → 4.2s），
            // 而角色判断只需要 JSON 结果；关闭后若服务端不支持该字段也只是忽略。
            request.put("chat_template_kwargs", Map.of("enable_thinking", false));
        }
        var requestBuilder = client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request);
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        JsonNode response = requestBuilder.retrieve().body(JsonNode.class);
        return parseAssignments(extractContent(response), indexes);
    }

    private Set<Integer> collectIndexes(List<Map<String, Object>> turns) {
        Set<Integer> indexes = new LinkedHashSet<>();
        for (Map<String, Object> turn : turns) {
            Integer index = turn == null ? null : toInteger(turn.get("index"));
            if (index == null || index < 0) {
                continue;
            }
            indexes.add(index);
        }
        return indexes;
    }

    private Integer toInteger(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            int integer = Math.toIntExact(number.longValue());
            return Double.compare(number.doubleValue(), integer) == 0 ? integer : null;
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    /**
     * 取出模型回答里的 JSON 文本。
     *
     * <p>思考块可能被 {@code --reasoning-format} 混进 {@code content}，若不剥离就会解析失败并静默降级为
     * FALLBACK（转写不报错、角色却全是 OTHER），因此统一交给 {@link LlmJsonContent} 处理。
     */
    private String extractContent(JsonNode response) {
        String content = response == null ? "" : response.at("/choices/0/message/content").asText("").strip();
        if (content.isBlank()) {
            throw new InvalidRoleResponseException("本地LLM角色判断未返回有效内容");
        }
        return LlmJsonContent.cleaned(content);
    }

    private Map<Integer, DashScopeRoleClient.RoleAssignment> parseAssignments(String content, Set<Integer> expected)
            throws JsonProcessingException {
        JsonNode parsed = mapper.readTree(content);
        JsonNode items = parsed.path("items");
        if (!items.isArray() || items.size() != expected.size()) {
            throw new InvalidRoleResponseException("本地LLM角色判断返回的 items 无效");
        }

        Map<Integer, DashScopeRoleClient.RoleAssignment> assignments = new LinkedHashMap<>();
        for (JsonNode item : items) {
            if (!item.isObject() || !item.path("index").isIntegralNumber()) {
                throw new InvalidRoleResponseException("本地LLM角色判断返回的索引无效");
            }
            int index = item.path("index").intValue();
            if (!expected.contains(index) || assignments.put(index, parseAssignment(item)) != null) {
                throw new InvalidRoleResponseException("本地LLM角色判断返回的索引集合无效");
            }
        }
        if (!assignments.keySet().equals(expected)) {
            throw new InvalidRoleResponseException("本地LLM角色判断缺少句段结果");
        }
        return assignments;
    }

    private DashScopeRoleClient.RoleAssignment parseAssignment(JsonNode node) {
        String role = node.path("role").asText("").strip().toUpperCase(Locale.ROOT);
        JsonNode confidenceNode = node.path("confidence");
        if (!ALLOWED_ROLES.contains(role)
                || !confidenceNode.isIntegralNumber()
                || confidenceNode.intValue() < MIN_CONFIDENCE
                || confidenceNode.intValue() > MAX_CONFIDENCE) {
            throw new InvalidRoleResponseException("本地LLM角色判断返回的角色或置信度无效");
        }
        return new DashScopeRoleClient.RoleAssignment(role, confidenceNode.intValue(), SOURCE_LLM);
    }

    private Map<Integer, DashScopeRoleClient.RoleAssignment> fallbackAssignments(Set<Integer> indexes) {
        Map<Integer, DashScopeRoleClient.RoleAssignment> fallback = fallback(indexes);
        logAssignment(fallback, true, 0);
        return fallback;
    }

    private Map<Integer, DashScopeRoleClient.RoleAssignment> fallback(Set<Integer> indexes) {
        Map<Integer, DashScopeRoleClient.RoleAssignment> fallback = new LinkedHashMap<>();
        indexes.forEach(index -> fallback.put(index, DashScopeRoleClient.RoleAssignment.fallback()));
        return fallback;
    }

    private boolean shouldRetry(Exception exception) {
        if (exception instanceof ResourceAccessException
                || exception instanceof JsonProcessingException
                || exception instanceof InvalidRoleResponseException) {
            return true;
        }
        if (exception instanceof RestClientResponseException responseException) {
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
            LOG.warn("本地LLM角色调用重试等待被中断");
            return false;
        }
    }

    private void logAssignment(Map<Integer, DashScopeRoleClient.RoleAssignment> assignments, boolean degraded, long durationMs) {
        long lowConfidence = assignments.values().stream()
                .filter(assignment -> assignment.confidence() == null
                        || ROLE_OTHER.equals(assignment.role())
                        || assignment.confidence() < reviewThreshold)
                .count();
        LOG.info("本地LLM角色判断完成：模型={}，句段数={}，待复核句段数={}，是否降级={}，耗时毫秒={}",
                model, assignments.size(), lowConfidence, degraded, durationMs);
    }

    private static RestClient createRestClient(HttpClient httpClient, String endpoint, long readTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMs)));
        return RestClient.builder().baseUrl(endpoint).requestFactory(factory).build();
    }

    private static int clamp(long value, int minimum, int maximum) {
        return (int) Math.max(minimum, Math.min(maximum, value));
    }

    private static String requireText(String propertyName, String value) {
        String trimmed = trimToEmpty(value);
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(propertyName + " 不能为空");
        }
        return trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Integer httpStatus(Exception exception) {
        return exception instanceof RestClientResponseException responseException
                ? responseException.getStatusCode().value()
                : null;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static final class InvalidRoleResponseException extends IllegalStateException {
        private InvalidRoleResponseException(String message) {
            super(message);
        }
    }
}
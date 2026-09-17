package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** 阿里云 DashScope 异步文件转写客户端。 */
@Service
public class DashScopeAsrClient {
    private static final Logger LOG = LoggerFactory.getLogger(DashScopeAsrClient.class);
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;
    private static final String ASR_SUBMIT_PATH = "/api/v1/services/audio/asr/transcription";
    private static final String TASK_QUERY_PATH = "/api/v1/tasks/{taskId}";
    private static final String ASYNC_HEADER = "X-DashScope-Async";
    private static final String ASYNC_ENABLED = "enable";
    private static final String ERROR_CODE_NOT_CONFIGURED = "DASHSCOPE_NOT_CONFIGURED";
    private static final String ERROR_CODE_UNAVAILABLE = "DASHSCOPE_UNAVAILABLE";
    private static final String ERROR_CODE_INVALID_REQUEST = "DASHSCOPE_INVALID_REQUEST";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final boolean diarizationEnabled;

    @Autowired
    public DashScopeAsrClient(
            ObjectMapper objectMapper,
            @Value("${medicalai.dashscope.endpoint:https://dashscope.aliyuncs.com}") String endpoint,
            @Value("${medicalai.dashscope.api-key:}") String apiKey,
            @Value("${medicalai.dashscope.asr-model:qwen-audio-3.0-asr-flash-filetrans}") String model,
            @Value("${medicalai.dashscope.diarization-enabled:true}") boolean diarizationEnabled,
            @Value("${medicalai.dashscope.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${medicalai.dashscope.read-timeout-ms:60000}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.apiKey = trimToEmpty(apiKey);
        this.model = requireText("DASHSCOPE_ASR_MODEL", model);
        this.diarizationEnabled = diarizationEnabled;
        this.client = createClient(requireText("DASHSCOPE_ENDPOINT", endpoint), connectTimeoutMs, readTimeoutMs);
    }

    /** 保持直接构造客户端的兼容性，生产环境应使用可配置超时的 Spring 构造器。 */
    public DashScopeAsrClient(
            ObjectMapper objectMapper,
            String endpoint,
            String apiKey,
            String model,
            boolean diarizationEnabled) {
        this(objectMapper, endpoint, apiKey, model, diarizationEnabled,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /** 提交音频文件转写任务并返回 DashScope 任务编号。 */
    public String submit(String fileUrl) {
        requireApiKey();
        String normalizedFileUrl = requireRequestText("音频文件地址", fileUrl);
        long startedAt = System.nanoTime();
        try {
            LOG.info("公网ASR调用开始：模型={}，是否启用说话人分离={}", model, diarizationEnabled);
            JsonNode response = client.post()
                    .uri(ASR_SUBMIT_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(ASYNC_HEADER, ASYNC_ENABLED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "input", Map.of("file_urls", List.of(normalizedFileUrl)),
                            "parameters", Map.of(
                                    "channel_id", List.of(0),
                                    "diarization_enabled", diarizationEnabled)))
                    .retrieve()
                    .body(JsonNode.class);
            String taskId = text(response == null ? null : response.at("/output/task_id"));
            if (taskId.isBlank()) {
                throw new IllegalStateException("DashScope ASR 响应缺少任务编号");
            }
            LOG.info("公网ASR调用提交成功：模型={}，耗时毫秒={}", model, elapsedMs(startedAt));
            return taskId;
        } catch (Exception exception) {
            throw unavailable("DashScope ASR 提交失败", exception);
        }
    }

    /** 查询 DashScope 异步转写任务的当前状态。 */
    public Task query(String taskId) {
        requireApiKey();
        String normalizedTaskId = requireRequestText("DashScope 任务编号", taskId);
        try {
            JsonNode response = client.get()
                    .uri(TASK_QUERY_PATH, normalizedTaskId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode output = response == null ? null : response.path("output");
            String status = firstText(output, "task_status", "status");
            return new Task(
                    normalizedTaskId,
                    status.toUpperCase(Locale.ROOT),
                    output == null ? objectMapper.createObjectNode() : output,
                    response == null ? objectMapper.createObjectNode() : response);
        } catch (Exception exception) {
            throw unavailable("DashScope ASR 查询失败", exception);
        }
    }

    /** 解析最终转写结果中的句段。 */
    public List<AsrSegment> result(Task task) {
        return resultDetailed(task).segments();
    }

    /** 解析最终转写结果，并保留用于审计的服务商原始响应。 */
    public AsrResult resultDetailed(Task task) {
        requireApiKey();
        if (task == null) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "DASHSCOPE_INVALID_TASK", "DashScope 转写任务不能为空");
        }

        long startedAt = System.nanoTime();
        JsonNode result = task.rawResponse() == null ? task.output() : task.rawResponse();
        String resultUrl = findResultUrl(result);
        if (!resultUrl.isBlank()) {
            result = downloadResult(resultUrl);
        }

        List<AsrSegment> segments = new ArrayList<>();
        collectSegments(result, segments);
        LOG.info("公网ASR结果解析成功：模型={}，是否下载结果文件={}，句段数={}，耗时毫秒={}",
                model, !resultUrl.isBlank(), segments.size(), elapsedMs(startedAt));
        return new AsrResult(segments, result);
    }

    private JsonNode downloadResult(String resultUrl) {
        try {
            URI uri = URI.create(resultUrl);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("DashScope ASR 结果地址必须为 HTTPS 绝对地址");
            }
            // 转写结果地址通常为短期签名 URL，不向其转发 DashScope API 密钥。
            return client.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (Exception exception) {
            throw unavailable("DashScope ASR 结果下载失败", exception);
        }
    }

    private void collectSegments(JsonNode node, List<AsrSegment> segments) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> collectSegments(item, segments));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String segmentText = firstText(node, "text", "sentence", "transcript");
        Integer speakerId = firstInteger(node, "speaker_id", "speakerId");
        Long startMs = firstNumber(node, "begin_time", "start_time", "start_ms", "startMs", "start");
        Long endMs = firstNumber(node, "end_time", "end_time_ms", "end_ms", "endMs", "end");
        if (!segmentText.isBlank() && startMs != null && endMs != null) {
            segments.add(new AsrSegment(
                    segmentText.strip(), Math.max(0, startMs), Math.max(startMs, endMs), speakerId));
            return;
        }
        node.fields().forEachRemaining(entry -> collectSegments(entry.getValue(), segments));
    }

    private String findResultUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isObject()) {
            for (String key : List.of("transcription_url", "transcriptionUrl", "result_url", "resultUrl")) {
                String value = text(node.get(key));
                if (!value.isBlank()) {
                    return value;
                }
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                String value = findResultUrl(values.next());
                if (!value.isBlank()) {
                    return value;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String value = findResultUrl(item);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            String value = text(node.get(name));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Long firstNumber(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isIntegralNumber()) {
                return value.longValue();
            }
            try {
                return Long.parseLong(value.asText().strip());
            } catch (NumberFormatException ignored) {
                // 当前字段不是有效整数，继续匹配下一个兼容字段。
            }
        }
        return null;
    }

    private Integer firstInteger(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            try {
                return value.isIntegralNumber()
                        ? Math.toIntExact(value.longValue())
                        : Integer.valueOf(value.asText().strip());
            } catch (ArithmeticException | NumberFormatException ignored) {
                // 当前字段不是有效整数，继续匹配下一个兼容字段。
            }
        }
        return null;
    }

    private void requireApiKey() {
        if (apiKey.isBlank()) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE, ERROR_CODE_NOT_CONFIGURED, "未配置 DASHSCOPE_API_KEY");
        }
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "" : node.asText("").strip();
    }

    private BusinessException unavailable(String message, Exception cause) {
        if (cause instanceof BusinessException businessException) {
            return businessException;
        }
        if (cause instanceof RestClientResponseException responseException) {
            LOG.warn("DashScope ASR 调用失败：操作={}，HTTP状态={}", message,
                    responseException.getStatusCode().value());
        } else {
            LOG.warn("DashScope ASR 调用失败：操作={}，异常类型={}", message,
                    cause.getClass().getSimpleName());
        }
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ERROR_CODE_UNAVAILABLE, message, cause);
    }

    private static RestClient createClient(String endpoint, long connectTimeoutMs, long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMs)));
        return RestClient.builder().baseUrl(endpoint).requestFactory(factory).build();
    }

    private static String requireText(String propertyName, String value) {
        String trimmed = trimToEmpty(value);
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(propertyName + " 不能为空");
        }
        return trimmed;
    }

    private static String requireRequestText(String fieldName, String value) {
        String trimmed = trimToEmpty(value);
        if (trimmed.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, ERROR_CODE_INVALID_REQUEST, fieldName + "不能为空");
        }
        return trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record Task(String taskId, String status, JsonNode output, JsonNode rawResponse) {
        public Task(String taskId, String status, JsonNode output) {
            this(taskId, status, output, output);
        }
    }
}

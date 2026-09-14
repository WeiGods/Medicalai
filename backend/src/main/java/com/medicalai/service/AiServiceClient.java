package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import java.io.File;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AiServiceClient {
    private static final Logger LOG = LoggerFactory.getLogger(AiServiceClient.class);
    private final RestClient client;
    private final String baseUrl;
    private final boolean mockFallback;

    public AiServiceClient(@Value("${medicalai.ai.base-url:http://127.0.0.1:8000}") String baseUrl,
                           @Value("${medicalai.ai.mock-fallback:true}") boolean mockFallback) {
        this.baseUrl = baseUrl;
        this.mockFallback = mockFallback;
        // JDK HttpClient defaults to HTTP/2 with an h2c upgrade for http:// URLs;
        // uvicorn rejects the upgrade and the multipart body is lost (422).
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    public TranscribeResponse transcribe(MultipartFile file) {
        String fileName = file == null ? "<null>" : safe(file.getOriginalFilename());
        long size = file == null ? -1 : file.getSize();
        LOG.info("ASR request started: endpoint=/internal/asr/transcribe, fileName={}, sizeBytes={}, baseUrl={}",
                fileName, size, baseUrl);
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename() { return file.getOriginalFilename(); }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);
            TranscribeResponse response = client.post().uri("/internal/asr/transcribe")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body).retrieve().body(TranscribeResponse.class);
            LOG.info("ASR request succeeded: endpoint=/internal/asr/transcribe, fileName={}, jobId={}, status={}, utterances={}",
                    fileName, response == null ? null : response.jobId(), response == null ? null : response.status(),
                    response == null || response.utterances() == null ? 0 : response.utterances().size());
            return response;
        } catch (Exception e) {
            logFailure("/internal/asr/transcribe", "fileName=" + fileName + ", sizeBytes=" + size, e);
            if (mockFallback) return fallbackTranscribe(fileName, e, "");
            throw unavailable("转写服务暂不可用", e);
        }
    }

    public TranscribeResponse transcribe(File file) {
        String fileName = file == null ? "<null>" : safe(file.getName());
        long size = file == null || !file.exists() ? -1 : file.length();
        LOG.info("ASR request started: endpoint=/internal/asr/transcribe, fileName={}, sizeBytes={}, baseUrl={}",
                fileName, size, baseUrl);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(file));
            TranscribeResponse response = client.post().uri("/internal/asr/transcribe")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body).retrieve().body(TranscribeResponse.class);
            LOG.info("ASR request succeeded: endpoint=/internal/asr/transcribe, fileName={}, jobId={}, status={}, utterances={}",
                    fileName, response == null ? null : response.jobId(), response == null ? null : response.status(),
                    response == null || response.utterances() == null ? 0 : response.utterances().size());
            return response;
        } catch (Exception e) {
            logFailure("/internal/asr/transcribe", "fileName=" + fileName + ", sizeBytes=" + size, e);
            if (mockFallback) return fallbackTranscribe(fileName, e, "");
            throw unavailable("转写服务暂不可用", e);
        }
    }

    public TranscribeResponse transcribeSample(String patientName) {
        LOG.info("ASR sample request started: endpoint=/internal/asr/sample, patientName={}, baseUrl={}", safe(patientName), baseUrl);
        try {
            TranscribeResponse response = client.get().uri(uri -> uri.path("/internal/asr/sample").queryParam("patient_name", patientName).build())
                    .retrieve().body(TranscribeResponse.class);
            LOG.info("ASR sample request succeeded: jobId={}, status={}, utterances={}",
                    response == null ? null : response.jobId(), response == null ? null : response.status(),
                    response == null || response.utterances() == null ? 0 : response.utterances().size());
            return response;
        } catch (Exception e) {
            logFailure("/internal/asr/sample", "patientName=" + safe(patientName), e);
            if (mockFallback) return fallbackTranscribe("sample", e, patientName);
            throw unavailable("转写服务暂不可用", e);
        }
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

    private BusinessException unavailable(String message, Exception cause) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ASR_UNAVAILABLE",
                message + "，请确认 AI 服务已启动或 AI_SERVICE_BASE_URL 配置正确（当前：" + baseUrl + "）", cause);
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

    private TranscribeResponse fallbackTranscribe(String source, Exception cause, String patientName) {
        LOG.warn("ASR mock fallback used: source={}, baseUrl={}, reason={}",
                safe(source), baseUrl, cause.getClass().getSimpleName());
        String prefix = patientName == null || patientName.isBlank() ? "" : safe(patientName) + "，";
        return new TranscribeResponse(UUID.randomUUID().toString(), "MOCK_FALLBACK", List.of(
                Map.of("role", "DOCTOR", "text", prefix + "您好，今天主要有什么不舒服？", "start_ms", 0, "end_ms", 3000),
                Map.of("role", "PATIENT", "text", "最近三天反复头痛，主要是右侧太阳穴附近，有点胀痛。", "start_ms", 3200, "end_ms", 9000),
                Map.of("role", "DOCTOR", "text", "每次头痛持续多久？有没有恶心、呕吐或者其他症状？", "start_ms", 9300, "end_ms", 14000),
                Map.of("role", "PATIENT", "text", "每次大概半小时到一小时，休息后能好一点。偶尔有点恶心，没有呕吐。", "start_ms", 14200, "end_ms", 21000),
                Map.of("role", "DOCTOR", "text", "之前有类似情况吗？平时有高血压或其他疾病吗？", "start_ms", 21300, "end_ms", 26000),
                Map.of("role", "PATIENT", "text", "以前偶尔也会头痛，最近睡眠不太好。没有高血压，也没有其他慢性病。", "start_ms", 26200, "end_ms", 33000)
        ));
    }

    public record TranscribeResponse(String jobId, String status, List<Map<String, Object>> utterances) {}
    public record GenerateResponse(String jobId, String status, String sourceSnapshotHash,
                                   Map<String, Object> record) {}
}

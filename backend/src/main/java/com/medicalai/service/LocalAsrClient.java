package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicalai.exception.BusinessException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class LocalAsrClient {
    private static final Logger LOG = LoggerFactory.getLogger(LocalAsrClient.class);
    private final RestClient client;
    private final String mode;

    @Autowired
    public LocalAsrClient(@Value("${medicalai.local-asr.base-url:${medicalai.ai.base-url:http://127.0.0.1:8000}}") String baseUrl,
                          @Value("${medicalai.local-asr.connect-timeout-ms:5000}") long connectTimeout,
                          @Value("${medicalai.local-asr.read-timeout-ms:600000}") long readTimeout,
                          @Value("${medicalai.local-asr.mode:offline}") String mode) {
        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeout))).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeout)));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.mode = mode == null || mode.isBlank() ? "offline" : mode.strip().toLowerCase();
    }

    public LocalAsrClient(String baseUrl, long connectTimeout, long readTimeout) {
        this(baseUrl, connectTimeout, readTimeout, "offline");
    }

    public List<AsrSegment> transcribe(Resource audio, String filename, String mimeType) {
        return transcribeDetailed(audio, filename, mimeType).segments();
    }

    public AsrResult transcribeDetailed(Resource audio, String filename, String mimeType) {
        try {
            long startedAt = System.nanoTime();
            var resource = new ByteArrayResource(audio.getContentAsByteArray()) {
                @Override public String getFilename() {
                    return filename == null || filename.isBlank() ? "recording.webm" : filename;
                }
            };
            var headers = new HttpHeaders();
            try { headers.setContentType(MediaType.parseMediaType(mimeType)); }
            catch (IllegalArgumentException e) { headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); }
            var body = new LinkedMultiValueMap<String, Object>();
            body.add("file", new HttpEntity<>(resource, headers));
            JsonNode response = client.post().uri(uriBuilder -> uriBuilder
                            .path("/internal/asr/transcribe")
                            .queryParam("mode", mode)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(JsonNode.class);
            if (response == null || !"SUCCEEDED".equals(response.path("status").asText())) {
                throw new IllegalStateException("本地 ASR 未返回成功状态");
            }
            List<AsrSegment> segments = new ArrayList<>();
            for (JsonNode item : response.path("utterances")) {
                String text = item.path("text").asText("").strip();
                if (text.isEmpty()) continue;
                long start = Math.max(0, item.path("start_ms").asLong());
                long end = Math.max(start, item.path("end_ms").asLong());
                Integer speaker = null;
                String rawSpeaker = item.path("speaker_id").asText("");
                if (rawSpeaker.isBlank()) {
                    String role = item.path("role").asText("");
                    if (role.startsWith("SPEAKER_")) rawSpeaker = role.substring(8);
                }
                try { speaker = Integer.valueOf(rawSpeaker); } catch (NumberFormatException ignored) { }
                segments.add(new AsrSegment(text, start, end, speaker));
            }
            if (segments.isEmpty()) throw new IllegalStateException("本地 ASR 返回空转写结果");
            LOG.info("本地ASR调用成功：音频字节数={}，句段数={}，耗时毫秒={}",
                    resource.contentLength(), segments.size(), elapsedMs(startedAt));
            return new AsrResult(segments, response);
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_ASR_UNAVAILABLE",
                    "本地 ASR 转写失败，请确认容器可访问、模型已加载，或切换模型重试", e);
        }
    }

    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }
}

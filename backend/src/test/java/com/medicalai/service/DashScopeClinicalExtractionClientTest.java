package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 公网提取必须直接请求 DashScope Compatible API，不能经过内网 AI 服务。 */
class DashScopeClinicalExtractionClientTest {
    @Test
    void postsDirectlyToDashScopeCompatibleEndpointWithSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> payload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String field : List.of("chief_complaint", "onset_course", "symptom_characteristics",
                    "associated_symptoms", "past_medical_history", "medication_history", "allergy_history",
                    "family_history", "social_history", "doctor_diagnosis", "doctor_medication", "doctor_followup")) {
                fields.put(field, null);
            }
            byte[] body = mapper.writeValueAsBytes(Map.of("choices", List.of(
                    Map.of("message", Map.of("content", mapper.writeValueAsString(Map.of("fields", fields)))))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DashScopeClinicalExtractionClient client = new DashScopeClinicalExtractionClient(mapper,
                    "http://127.0.0.1:" + port, "test-public-key", "qwen-plus", 1_000, 5_000, 4_096);

            AiServiceClient.ExtractionResponse response = client.extract("snapshot-hash", List.of(
                    Map.of("index", 0, "role", "PATIENT", "text", "我头痛三天", "start_ms", 0, "end_ms", 1000)));

            assertEquals("SUCCEEDED", response.status());
            assertEquals("snapshot-hash", response.sourceSnapshotHash());
            assertEquals("/compatible-mode/v1/chat/completions", path.get());
            assertEquals("Bearer test-public-key", authorization.get());
            assertTrue(payload.get().contains("json_schema"));
            assertTrue(payload.get().contains("chief_complaint"));
        } finally {
            server.stop(0);
        }
    }
}

package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DashScopeAsrClientTest {
    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void submitsAsyncTaskWithExpectedHeadersAndPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> async = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/services/audio/asr/transcription", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            async.set(exchange.getRequestHeaders().getFirst("X-DashScope-Async"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"output\":{\"task_id\":\"task-001\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        String taskId = new DashScopeAsrClient(mapper, endpoint, "test-key", "test-model", true)
                .submit("https://storage.example.test/audio.wav");

        assertEquals("task-001", taskId);
        assertEquals("Bearer test-key", authorization.get());
        assertEquals("enable", async.get());
        assertEquals("test-model", mapper.readTree(requestBody.get()).path("model").asText());
    }

    @Test
    void queriesTaskAndNormalizesStatusAndIdentifier() throws Exception {
        server.createContext("/api/v1/tasks/task-001", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"output\":{\"task_status\":\"succeeded\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        DashScopeAsrClient.Task task = new DashScopeAsrClient(
                new ObjectMapper(), endpoint, "test-key", "test-model", true).query(" task-001 ");

        assertEquals("task-001", task.taskId());
        assertEquals("SUCCEEDED", task.status());
    }

    @Test
    void parsesInlineSegmentsAndNormalizesTimeAndSpeaker() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var response = mapper.readTree("""
                {"output":{"sentences":[
                  {"text":"  你好  ","begin_time":-20,"end_time":100,"speaker_id":"3"},
                  {"text":"复诊","start_ms":110,"end_ms":200,"speakerId":4}
                ]}}
                """);

        AsrResult result = new DashScopeAsrClient(mapper, endpoint, "test-key", "test-model", true)
                .resultDetailed(new DashScopeAsrClient.Task("task-001", "SUCCEEDED", response));

        assertEquals(new AsrSegment("你好", 0, 100, 3), result.segments().get(0));
        assertEquals(new AsrSegment("复诊", 110, 200, 4), result.segments().get(1));
    }

    @Test
    void rejectsNonHttpsResultUrlWithoutSendingARequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/result", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        ObjectMapper mapper = new ObjectMapper();
        var response = mapper.readTree("{\"output\":{\"transcription_url\":\"" + endpoint + "/result\"}}");
        DashScopeAsrClient client = new DashScopeAsrClient(mapper, endpoint, "test-key", "test-model", true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.resultDetailed(new DashScopeAsrClient.Task("task-001", "SUCCEEDED", response)));

        assertEquals("DASHSCOPE_UNAVAILABLE", exception.code());
        assertEquals(0, requests.get());
    }

    @Test
    void rejectsMissingApiKeyBeforeCallingProvider() {
        DashScopeAsrClient client = new DashScopeAsrClient(
                new ObjectMapper(), endpoint, " ", "test-model", true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> client.submit("https://storage.example.test/audio.wav"));

        assertEquals("DASHSCOPE_NOT_CONFIGURED", exception.code());
    }

    @Test
    void rejectsBlankFileUrlAsBadRequest() {
        DashScopeAsrClient client = new DashScopeAsrClient(
                new ObjectMapper(), endpoint, "test-key", "test-model", true);

        BusinessException exception = assertThrows(BusinessException.class, () -> client.submit(" "));

        assertEquals("DASHSCOPE_INVALID_REQUEST", exception.code());
        assertEquals(400, exception.status().value());
    }
}

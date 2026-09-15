package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import com.medicalai.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;

class LocalAsrClientTest {
    HttpServer server;
    String base;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    void respond(int status, String json, long delay) {
        server.createContext("/internal/asr/transcribe", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try { exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
    }
    @Test void sendsHttp11MultipartAndNormalizesSpeakers() {
        var request = new AtomicReference<String>();
        var protocol = new AtomicReference<String>();
        server.createContext("/internal/asr/transcribe", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            protocol.set(exchange.getProtocol());
            byte[] result = """
                {"status":"SUCCEEDED","utterances":[
                  {"text":"你好","start_ms":10,"end_ms":200,"speaker_id":2},
                  {"text":"疼痛","start_ms":210,"end_ms":400,"role":"SPEAKER_1"},
                  {"text":"不确定","start_ms":410,"end_ms":600,"role":"UNKNOWN"}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result); exchange.close();
        });
        var result = new LocalAsrClient(base, 1000, 2000).transcribe(new ByteArrayResource("audio-payload".getBytes()), "visit.wav", "audio/wav");
        assertEquals("HTTP/1.1", protocol.get());
        assertTrue(request.get().contains("name=\"file\""));
        assertTrue(request.get().contains("filename=\"visit.wav\""));
        assertTrue(request.get().contains("Content-Type: audio/wav"));
        assertTrue(request.get().contains("audio-payload"));
        assertEquals(new AsrSegment("你好",10,200,2), result.getFirst());
        assertEquals(1, result.get(1).speakerId());
        assertNull(result.get(2).speakerId());
    }
    @Test void modelNotReadyFails() {
        respond(503, "{\"detail\":\"模型尚未加载\"}", 0);
        assertEquals("LOCAL_ASR_UNAVAILABLE", assertThrows(BusinessException.class, () ->
            new LocalAsrClient(base, 1000, 2000).transcribe(new ByteArrayResource(new byte[]{1}), "a.wav", "audio/wav")).code());
    }
    @Test void emptyResultFails() {
        respond(200, "{\"status\":\"SUCCEEDED\",\"utterances\":[]}", 0);
        assertThrows(BusinessException.class, () -> new LocalAsrClient(base,1000,2000).transcribe(new ByteArrayResource(new byte[]{1}),"a.wav",null));
    }
    @Test void timeoutFails() {
        respond(200, "{}", 250);
        assertThrows(BusinessException.class, () -> new LocalAsrClient(base,1000,30).transcribe(new ByteArrayResource(new byte[]{1}),"a.wav","audio/wav"));
    }
}

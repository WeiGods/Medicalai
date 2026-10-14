package com.medicalai.service;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
class DashScopeRoleClientTest {
    HttpServer server;
    String endpoint;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
        endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void classifiesEachTurnWithConfidenceAndUsesDedicatedTextModel() throws Exception {
        var mapper=new ObjectMapper();var input=new AtomicReference<String>();
        server.createContext("/compatible-mode/v1/chat/completions",e->{
            input.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index",0,"role"," DOCTOR ","confidence",92),
                    Map.of("index",1,"role","PATIENT","confidence",69))));
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)) )));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
        });
        var result=new DashScopeRoleClient(mapper,endpoint,"test-key","qwen-plus",70,40).assignRoles(List.of(
            Map.of("index",0,"speaker_id",0,"text","哪里疼"),Map.of("index",1,"speaker_id",0,"text","头疼")));
        assertEquals(Map.of(0,new DashScopeRoleClient.RoleAssignment("DOCTOR",92,"LLM"),
                1,new DashScopeRoleClient.RoleAssignment("PATIENT",69,"LLM")),result);
        var request=mapper.readTree(input.get());
        assertEquals("qwen-plus",request.path("model").asText());
        assertEquals("json_object",request.path("response_format").path("type").asText());
        assertFalse(request.toString().contains("enable_thinking"));
    }
    @Test void invalidOrMissingAssignmentsFallBackToOtherWithoutConfidence() throws Exception {
        var mapper=new ObjectMapper();
        server.createContext("/compatible-mode/v1/chat/completions",e->{
            e.getRequestBody().readAllBytes();
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index",0,"role","DOCTOR","confidence",101),
                    Map.of("index",1,"role","PATIENT","confidence",80))));
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)) )));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
        });
        var fallback=new DashScopeRoleClient(mapper,endpoint,"test-key","qwen-plus",70,40).assignRoles(List.of(
                Map.of("index",0,"speaker_id",0,"text","医生您好"),Map.of("index",1,"speaker_id",1,"text","头疼")));
        assertEquals(Map.of(0,new DashScopeRoleClient.RoleAssignment("OTHER",null,"FALLBACK"),
                1,new DashScopeRoleClient.RoleAssignment("OTHER",null,"FALLBACK")),fallback);
    }
    @Test void serviceFailurePreservesTranscriptionWithFallbackRoles(){
        var requests = new AtomicInteger();
        server.createContext("/compatible-mode/v1/chat/completions",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();e.sendResponseHeaders(401,-1);e.close();});
        assertEquals(Map.of(0,new DashScopeRoleClient.RoleAssignment("OTHER",null,"FALLBACK")),
                new DashScopeRoleClient(new ObjectMapper(),endpoint,"test-key","qwen-plus",70,40)
                        .assignRoles(List.of(Map.of("index",0,"speaker_id",0,"text","嗯"))));
        assertEquals(1, requests.get());
    }

    @Test void retriesRateLimitThenUsesTheSuccessfulResponse() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new AtomicInteger();
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index", 0, "role", "DOCTOR", "confidence", 90))));
            byte[] output = mapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });

        var result = new DashScopeRoleClient(mapper, endpoint, "test-key", "qwen-plus", 70, 40)
                .assignRoles(List.of(Map.of("index", 0, "speaker_id", 0, "text", "哪里不舒服")));

        assertEquals(2, requests.get());
        assertEquals(new DashScopeRoleClient.RoleAssignment("DOCTOR", 90, "LLM"), result.get(0));
    }

    @Test void batchesLargeRoleRequestsSoOneResponseCannotInvalidateTheWholeRecording() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new AtomicInteger();
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String prompt = request.at("/messages/1/content").asText();
            int index = prompt.contains("TURN_2") ? 2 : prompt.contains("TURN_1") ? 1 : 0;
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index", index, "role", "PATIENT", "confidence", 90))));
            byte[] output = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });

        var result = new DashScopeRoleClient(mapper, endpoint, "test-key", "qwen-plus", 70, 1).assignRoles(List.of(
                Map.of("index", 0, "speaker_id", 0, "text", "TURN_0"),
                Map.of("index", 1, "speaker_id", 0, "text", "TURN_1"),
                Map.of("index", 2, "speaker_id", 0, "text", "TURN_2")));

        assertEquals(3, requests.get());
        assertEquals(Set.of(0, 1, 2), result.keySet());
        assertTrue(result.values().stream().allMatch(assignment -> "LLM".equals(assignment.source())));
    }
}

package com.medicalai.service;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
class LocalLlmRoleClientTest {
    HttpServer server;
    String endpoint;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
        endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void classifiesEachTurnAgainstLocalLlmWithoutAuthHeader() throws Exception {
        var mapper=new ObjectMapper();var input=new AtomicReference<String>();
        server.createContext("/v1/chat/completions",e->{
            input.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            assertNull(e.getRequestHeaders().getFirst("Authorization"),"api-key 为空时不应发送 Authorization 头");
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index",0,"role"," DOCTOR ","confidence",92),
                    Map.of("index",1,"role","PATIENT","confidence",69))));
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)) )));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
        });
        var result=new LocalLlmRoleClient(mapper,endpoint,"","/models/Ternary-Bonsai-2-27B-PQ2_0.gguf",70,40).assignRoles(List.of(
            Map.of("index",0,"speaker_id",0,"text","哪里疼"),Map.of("index",1,"speaker_id",0,"text","头疼")));
        assertEquals(Map.of(0,new DashScopeRoleClient.RoleAssignment("DOCTOR",92,"LLM"),
                1,new DashScopeRoleClient.RoleAssignment("PATIENT",69,"LLM")),result);
        var request=mapper.readTree(input.get());
        assertEquals("/models/Ternary-Bonsai-2-27B-PQ2_0.gguf",request.path("model").asText());
        assertEquals("json_object",request.path("response_format").path("type").asText());
        assertEquals(0,request.path("temperature").asInt());
        assertEquals("你是医疗对话逐句角色分类器。只返回符合要求的 JSON，不输出解释或思考过程。",
                request.at("/messages/0/content").asText());
        assertFalse(request.at("/chat_template_kwargs/enable_thinking").asBoolean(true),
                "角色识别请求必须显式关闭本地模型思考，否则思考 token 成倍拉长每批耗时");
    }
    @Test void sendsBearerHeaderWhenApiKeyConfigured() throws Exception {
        var mapper=new ObjectMapper();var header=new AtomicReference<String>();
        server.createContext("/v1/chat/completions",e->{
            header.set(e.getRequestHeaders().getFirst("Authorization"));
            e.getRequestBody().readAllBytes();
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index",0,"role","PATIENT","confidence",80))));
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)) )));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
        });
        new LocalLlmRoleClient(mapper,endpoint,"local-secret","local-model",70,40).assignRoles(List.of(
                Map.of("index",0,"speaker_id",1,"text","嗯")));
        assertEquals("Bearer local-secret", header.get());
    }
    @Test void invalidOrMissingAssignmentsFallBackToOtherWithoutConfidence() throws Exception {
        var mapper=new ObjectMapper();
        server.createContext("/v1/chat/completions",e->{
            e.getRequestBody().readAllBytes();
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index",0,"role","DOCTOR","confidence",101),
                    Map.of("index",1,"role","PATIENT","confidence",80))));
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)) )));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
        });
        var fallback=new LocalLlmRoleClient(mapper,endpoint,"","local-model",70,40).assignRoles(List.of(
                Map.of("index",0,"speaker_id",0,"text","医生您好"),Map.of("index",1,"speaker_id",1,"text","头疼")));
        assertEquals(Map.of(0,new DashScopeRoleClient.RoleAssignment("OTHER",null,"FALLBACK"),
                1,new DashScopeRoleClient.RoleAssignment("OTHER",null,"FALLBACK")),fallback);
    }
    @Test void serviceFailurePreservesTranscriptionWithFallbackRoles(){
        var requests = new AtomicInteger();
        server.createContext("/v1/chat/completions",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();e.sendResponseHeaders(401,-1);e.close();});
        assertEquals(Map.of(0,new DashScopeRoleClient.RoleAssignment("OTHER",null,"FALLBACK")),
                new LocalLlmRoleClient(new ObjectMapper(),endpoint,"","local-model",70,40)
                        .assignRoles(List.of(Map.of("index",0,"speaker_id",0,"text","嗯"))));
        assertEquals(1, requests.get());
    }
    @Test void retriesRateLimitThenUsesTheSuccessfulResponse() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
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
        var result = new LocalLlmRoleClient(mapper, endpoint, "", "local-model", 70, 40)
                .assignRoles(List.of(Map.of("index", 0, "speaker_id", 0, "text", "哪里不舒服")));
        assertEquals(2, requests.get());
        assertEquals(new DashScopeRoleClient.RoleAssignment("DOCTOR", 90, "LLM"), result.get(0));
    }
    @Test void batchesLargeRoleRequestsSoOneResponseCannotInvalidateTheWholeRecording() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
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
        var result = new LocalLlmRoleClient(mapper, endpoint, "", "local-model", 70, 1).assignRoles(List.of(
                Map.of("index", 0, "speaker_id", 0, "text", "TURN_0"),
                Map.of("index", 1, "speaker_id", 0, "text", "TURN_1"),
                Map.of("index", 2, "speaker_id", 0, "text", "TURN_2")));
        assertEquals(3, requests.get());
        assertEquals(Set.of(0, 1, 2), result.keySet());
        assertTrue(result.values().stream().allMatch(assignment -> "LLM".equals(assignment.source())));
    }
    @Test void disabledClientNeverContactsTheLocalLlm() {
        var requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", e -> { requests.incrementAndGet(); e.sendResponseHeaders(200, -1); e.close(); });
        var client = new LocalLlmRoleClient(new ObjectMapper(), false, endpoint, "",
                "local-model", 70, 40, true, 5000, 120000, 2, 200);
        var result = client.assignRoles(List.of(Map.of("index", 0, "speaker_id", 0, "text", "嗯")));
        assertEquals(Map.of(0, new DashScopeRoleClient.RoleAssignment("OTHER", null, "FALLBACK")), result);
        assertEquals(0, requests.get());
        assertFalse(client.isConfigured());
        assertThrows(IllegalStateException.class,
                () -> client.streamChat(List.of(Map.of("role", "user", "content", "你好")), null));
    }
    @Test void streamChatCollectsSseDeltasAndSendsStreamingRequest() throws Exception {
        var mapper = new ObjectMapper();
        var requestBody = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", e -> {
            byte[] body = e.getRequestBody().readAllBytes();
            requestBody.set(new String(body, StandardCharsets.UTF_8));
            List<String> deltas = List.of(
                    "{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                    "{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
                    "{\"choices\":[{\"delta\":{\"content\":\"，\"}}]}",
                    "{\"choices\":[{\"delta\":{\"content\":\"患者\"}}]}");
            StringBuilder payload = new StringBuilder();
            deltas.forEach(delta -> payload.append("data: ").append(delta).append("\n\n"));
            payload.append("data: [DONE]\n\n");
            byte[] output = payload.toString().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "text/event-stream");
            e.sendResponseHeaders(200, 0); // chunked
            e.getResponseBody().write(output);
            e.close();
        });
        var deltas = new CopyOnWriteArrayList<String>();
        String aggregated = new LocalLlmRoleClient(mapper, endpoint, "", "local-model", 70, 40)
                .streamChat(List.of(Map.of("role", "user", "content", "你好")), deltas::add);
        assertEquals("你好，患者", aggregated);
        assertEquals(List.of("你", "好", "，", "患者"), deltas);
        var request = mapper.readTree(requestBody.get());
        assertTrue(request.path("stream").asBoolean());
        assertEquals("local-model", request.path("model").asText());
        assertEquals("你好", request.at("/messages/0/content").asText());
    }
    @Test void disablesModelThinkingByDefaultAndCanBeTurnedOff() throws Exception {
        var mapper = new ObjectMapper();
        var requests = new CopyOnWriteArrayList<String>();
        server.createContext("/v1/chat/completions", e -> {
            requests.add(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = mapper.writeValueAsString(Map.of("items", List.of(
                    Map.of("index", 0, "role", "DOCTOR", "confidence", 90))));
            byte[] output = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, output.length);
            e.getResponseBody().write(output);
            e.close();
        });
        var turns = List.<Map<String, Object>>of(Map.of("index", 0, "speaker_id", 0, "text", "哪里不舒服"));

        new LocalLlmRoleClient(mapper, true, endpoint, "", "local-model", 70, 40, true, 5000, 120000, 2, 200)
                .assignRoles(turns);
        assertFalse(mapper.readTree(requests.get(0)).at("/chat_template_kwargs/enable_thinking").asBoolean(true),
                "默认应显式关闭本地模型思考");

        new LocalLlmRoleClient(mapper, true, endpoint, "", "local-model", 70, 40, false, 5000, 120000, 2, 200)
                .assignRoles(turns);
        assertTrue(mapper.readTree(requests.get(1)).path("chat_template_kwargs").isMissingNode(),
                "关闭该配置后不应再附带 chat_template_kwargs");
    }
    @Test void toleratesThinkingLeakedIntoContentWhenServerIgnoresReasoningFormat() throws Exception {
        var mapper = new ObjectMapper();
        server.createContext("/v1/chat/completions", e -> {
            e.getRequestBody().readAllBytes();
            String content = "<think>先看 speaker_id 再逐句判断……</think>\n"
                    + "{\"items\":[{\"index\":0,\"role\":\"DOCTOR\",\"confidence\":93}]}";
            byte[] output = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, output.length);
            e.getResponseBody().write(output);
            e.close();
        });
        var result = new LocalLlmRoleClient(mapper, endpoint, "", "local-model", 70, 40)
                .assignRoles(List.of(Map.of("index", 0, "speaker_id", 0, "text", "哪里不舒服")));
        assertEquals(new DashScopeRoleClient.RoleAssignment("DOCTOR", 93, "LLM"), result.get(0));
    }
    @Test void truncatedThinkingBlockIsRejectedInsteadOfBeingGuessed() {
        var mapper = new ObjectMapper();
        server.createContext("/v1/chat/completions", e -> {
            e.getRequestBody().readAllBytes();
            String content = "<think>还没想完就被截断了";
            byte[] output = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, output.length);
            e.getResponseBody().write(output);
            e.close();
        });
        var result = new LocalLlmRoleClient(mapper, endpoint, "", "local-model", 70, 40)
                .assignRoles(List.of(Map.of("index", 0, "speaker_id", 0, "text", "嗯")));
        assertEquals(Map.of(0, new DashScopeRoleClient.RoleAssignment("OTHER", null, "FALLBACK")), result);
    }
}
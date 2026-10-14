package com.medicalai.service;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
class LocalLlmExtractionClientTest {
    HttpServer server;
    String endpoint;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
        endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}

    private LocalLlmExtractionClient client(ObjectMapper mapper, boolean enabled, String apiKey) {
        return new LocalLlmExtractionClient(mapper, enabled, endpoint, apiKey, "local-model", 4096, true,
                5000, 180000, 2, 0);
    }

    private static String answer(String fieldsJson) {
        return "{\"fields\":" + fieldsJson + "}";
    }

    private static Map<String, Object> turn() {
        return Map.of("index", 0, "role", "PATIENT", "text", "右下腹痛三天");
    }

    @Test void extractsFactsThroughOpenAiCompatibleEndpoint() throws Exception {
        var mapper=new ObjectMapper();var body=new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"), "api-key 为空时不应发送 Authorization 头");
            String content=answer("{\"chief_complaint\":{\"value\":\"右下腹痛三天\",\"confidence\":90,"
                    +"\"evidence\":[{\"turn_index\":0,\"quote\":\"右下腹痛三天\"}]},"
                    +"\"onset_course\":null,\"symptom_characteristics\":null,\"associated_symptoms\":null,"
                    +"\"past_medical_history\":null,\"medication_history\":null,\"allergy_history\":null,"
                    +"\"family_history\":null,\"social_history\":null,\"doctor_diagnosis\":null,"
                    +"\"doctor_medication\":null,\"doctor_followup\":null}");
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        var response=client(mapper,true,"").extract("hash-1", List.of(turn()));
        assertEquals("SUCCEEDED", response.status());
        assertEquals("hash-1", response.sourceSnapshotHash());
        assertEquals("local-model", response.model());
        assertTrue(response.extraction().containsKey("fields"));

        JsonNode request=mapper.readTree(body.get());
        assertEquals("local-model", request.path("model").asText());
        assertEquals(0, request.path("temperature").asInt());
        assertEquals(4096, request.path("max_tokens").asInt());
        assertEquals("你是医疗对话逐句角色分类器".substring(0,0) + ClinicalExtractionLlmClient.SYSTEM_PROMPT,
                request.at("/messages/0/content").asText());
        // user 消息必须同时带提取指令与句段原文
        String user=request.at("/messages/1/content").asText();
        assertTrue(user.contains("你是医疗问诊事实提取器"));
        assertTrue(user.contains("右下腹痛三天"));
        // 与公网路由共用同一份严格 JSON Schema
        assertEquals("json_schema", request.path("response_format").path("type").asText());
        assertEquals("clinical_extraction",
                request.at("/response_format/json_schema/name").asText());
        assertTrue(request.at("/response_format/json_schema/strict").asBoolean());
        assertEquals(12, request.at("/response_format/json_schema/schema/properties/fields/required").size());
        assertFalse(request.at("/chat_template_kwargs/enable_thinking").asBoolean(true));
    }

    @Test void sendsBearerHeaderWhenApiKeyConfigured() throws Exception {
        var mapper=new ObjectMapper();var header=new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",
                    Map.of("content",answer("{\"chief_complaint\":null}"))))));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        client(mapper,true,"local-secret").extract("hash", List.of(turn()));
        assertEquals("Bearer local-secret", header.get());
    }

    @Test void disabledClientNeverContactsTheLocalLlm() {
        var requests=new AtomicInteger();
        server.createContext("/v1/chat/completions", e -> {requests.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        var disabled=client(new ObjectMapper(),false,"");
        assertFalse(disabled.isConfigured());
        BusinessException error=assertThrows(BusinessException.class,()->disabled.extract("hash",List.of(turn())));
        assertEquals("LOCAL_LLM_EXTRACTION_DISABLED", error.code());
        assertEquals(0, requests.get());
    }

    @Test void thinkingLeakedIntoContentIsStillParsed() throws Exception {
        var mapper=new ObjectMapper();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String content="<think>先找患者自述……</think>\n"
                    +answer("{\"chief_complaint\":{\"value\":\"头痛\",\"confidence\":88,"
                    +"\"evidence\":[{\"turn_index\":0,\"quote\":\"头痛\"}]}}");
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",content)))));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        var response=client(mapper,true,"").extract("hash", List.of(turn()));
        assertEquals("SUCCEEDED", response.status());
    }

    @Test void invalidJsonIsRetriedThenReportedWithoutTemplateFallback() {
        var mapper=new ObjectMapper();var requests=new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] output;
            try {
                output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",
                        Map.of("content","{\"fields\":\"不是对象\"}")))));
            } catch (Exception error) { throw new IllegalStateException(error); }
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        BusinessException error=assertThrows(BusinessException.class,
                ()->client(mapper,true,"").extract("hash",List.of(turn())));
        assertEquals("EXTRACTION_INVALID_RESPONSE", error.code());
        assertEquals(2, requests.get(), "解析失败应重试一次后放弃");
    }

    @Test void clientErrorIsNotRetriedAndSurfacesAsUnavailable() {
        var mapper=new ObjectMapper();var requests=new AtomicInteger();
        server.createContext("/v1/chat/completions", e -> {
            requests.incrementAndGet();e.getRequestBody().readAllBytes();e.sendResponseHeaders(401,-1);e.close();
        });
        BusinessException error=assertThrows(BusinessException.class,
                ()->client(mapper,true,"").extract("hash",List.of(turn())));
        assertEquals("LOCAL_LLM_EXTRACTION_UNAVAILABLE", error.code());
        assertEquals(1, requests.get());
    }

    /** 400（例如上下文超长）保持快速失败：不把整段提示词换成 json_object 再处理一遍。 */
    @Test void badRequestIsNotDegradedToJsonObject() {
        var mapper=new ObjectMapper();var requests=new AtomicInteger();
        var formats=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            formats.add(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("response_format").path("type").asText());
            requests.incrementAndGet();
            byte[] output="{\"error\":{\"message\":\"the request exceeds the available context size\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(400,output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        assertThrows(BusinessException.class,()->client(mapper,true,"").extract("hash",List.of(turn())));
        assertEquals(1, requests.get());
        assertEquals(List.of("json_schema"), formats);
    }

    /**
     * llama.cpp 语法采样器缺陷（b10772 实测）：strict json_schema + enable_thinking=false 时，
     * 临床文本里的 “/” 会让服务端以 500 返回 "Unexpected empty grammar stack"。
     * 温度 0 下同一报文必然复现，重发 strict 报文没有任何意义，必须换成 json_object 才有救。
     */
    @Test void strictSchemaGrammarFailureDegradesToJsonObjectOnce() throws Exception {
        var mapper=new ObjectMapper();var requests=new AtomicInteger();
        var formats=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var secondRequest=new AtomicReference<JsonNode>();
        String serverError="{\"error\":{\"code\":500,\"message\":\"got exception: "
                +"Unexpected empty grammar stack after accepting piece: / (14)\",\"type\":\"server_error\"}}";
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request=mapper.readTree(exchange.getRequestBody().readAllBytes());
            formats.add(request.path("response_format").path("type").asText());
            String body;int status;
            if (requests.incrementAndGet()==1) {
                status=500;body=serverError;
            } else {
                secondRequest.set(request);status=200;
                body=mapper.writeValueAsString(Map.of("choices",List.of(Map.of("message",
                        Map.of("content",answer("{\"chief_complaint\":null}"))))));
            }
            byte[] output=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        var response=client(mapper,true,"").extract("hash-grammar",List.of(turn()));
        assertEquals("SUCCEEDED", response.status());
        assertEquals(2, requests.get(), "strict schema 遇 5xx 只应换成 json_object 再试一次，不重复同一报文");
        assertEquals(List.of("json_schema","json_object"), formats);
        assertEquals(4096, secondRequest.get().path("max_tokens").asInt(), "降级只改 response_format");
        assertFalse(secondRequest.get().path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
        assertEquals(ClinicalExtractionLlmClient.SYSTEM_PROMPT,
                secondRequest.get().at("/messages/0/content").asText());
    }

    @Test void grammarFailureOnBothFormatsIsReportedWithHttpStatus() {
        var mapper=new ObjectMapper();var requests=new AtomicInteger();
        String serverError="{\"error\":{\"code\":500,\"message\":\"got exception: "
                +"Unexpected empty grammar stack after accepting piece: / (14)\",\"type\":\"server_error\"}}";
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();requests.incrementAndGet();
            byte[] output=serverError.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(500,output.length);exchange.getResponseBody().write(output);exchange.close();
        });
        BusinessException error=assertThrows(BusinessException.class,
                ()->client(mapper,true,"").extract("hash",List.of(turn())));
        assertEquals("LOCAL_LLM_EXTRACTION_UNAVAILABLE", error.code());
        assertTrue(error.getMessage().contains("HTTP 500"));
        assertEquals(2, requests.get(), "json_object 也失败时按重试上限收尾");
    }

    @Test void connectionFailureIsReportedWithEndpointHint() {
        var mapper=new ObjectMapper();
        var unreachable=new LocalLlmExtractionClient(mapper,true,"http://127.0.0.1:1","", "local-model",
                4096,true,200,200,1,0);
        BusinessException error=assertThrows(BusinessException.class,
                ()->unreachable.extract("hash",List.of(turn())));
        assertEquals("LOCAL_LLM_EXTRACTION_UNAVAILABLE", error.code());
        assertTrue(error.getMessage().contains("http://127.0.0.1:1"));
    }
}

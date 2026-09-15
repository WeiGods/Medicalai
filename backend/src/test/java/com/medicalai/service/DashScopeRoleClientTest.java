package com.medicalai.service;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
    @Test void classifiesPublicTurnsAndUsesDedicatedTextModel() throws Exception {
        var mapper=new ObjectMapper();var input=new AtomicReference<String>();
        server.createContext("/compatible-mode/v1/chat/completions",e->{
            input.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] output=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",mapper.writeValueAsString(Map.of("0"," DOCTOR ","-2","PATIENT")))))));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
        });
        var result=new DashScopeRoleClient(mapper,endpoint,"test-key","qwen-plus").assignRoles(List.of(
            Map.of("speaker_id",0,"text","哪里疼"),Map.of("speaker_id",-2,"text","头疼")));
        assertEquals(Map.of(0,"DOCTOR",-2,"PATIENT"),result);
        assertEquals("qwen-plus",mapper.readTree(input.get()).path("model").asText());
    }
    @Test void serviceFailurePreservesTranscriptionWithoutInventingRoles(){
        server.createContext("/compatible-mode/v1/chat/completions",e->{e.getRequestBody().readAllBytes();e.sendResponseHeaders(401,-1);e.close();});
        assertTrue(new DashScopeRoleClient(new ObjectMapper(),endpoint,"test-key","qwen-plus")
            .assignRoles(List.of(Map.of("speaker_id",0,"text","嗯"))).isEmpty());
    }
}

package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalRoleGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private LocalLlmRoleClient roles;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.start();
        var gateway = new AiServiceClient("http://127.0.0.1:" + server.getAddress().getPort(),1000,1000);
        // 直连开关为 false，直连地址故意不可用，验证生产业务只使用网关。
        roles = new LocalLlmRoleClient(gateway,json,false,"http://127.0.0.1:1","","unused-stream-model",70,2,1000,1000,2,0);
    }
    @AfterEach void stop() { server.stop(0); }
    private List<Map<String,Object>> turns(int count) {
        return java.util.stream.IntStream.range(0,count).mapToObj(i -> Map.<String,Object>of("index",i,"text","原文")).toList();
    }

    @Test void sendsBatchesToGatewayWithOriginalIndexes() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/internal/transcript/assign-roles",exchange -> {
            calls.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody());
            List<Map<String,Object>> items = new ArrayList<>();
            request.path("turns").forEach(turn -> items.add(Map.of("index",turn.path("index").asInt(),"role","PATIENT","confidence",92,"source","LLM")));
            byte[] response = json.writeValueAsBytes(Map.of("items",items));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        var result = roles.assignRoles(turns(3));
        assertTrue(roles.isConfigured());
        assertEquals(2,calls.get());
        assertEquals(Set.of(0,1,2),result.keySet());
        assertTrue(result.values().stream().allMatch(a -> "LLM".equals(a.source())));
    }

    @Test void springBusinessInstanceRequiresAndUsesGateway() {
        var gateway = org.mockito.Mockito.mock(AiServiceClient.class);
        org.mockito.Mockito.when(gateway.assignRoleAssignments(org.mockito.ArgumentMatchers.anyList())).thenReturn(
                Map.of(0,new DashScopeRoleClient.RoleAssignment("PATIENT",90,"LLM")));
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> json);
            context.registerBean(AiServiceClient.class, () -> gateway);
            context.register(LocalLlmRoleClient.class);
            context.refresh();
            var client = context.getBean(LocalLlmRoleClient.class);
            assertTrue(client.isConfigured());
            assertEquals("LLM",client.assignRoles(turns(1)).get(0).source());
            org.mockito.Mockito.verify(gateway).assignRoleAssignments(turns(1));
        }
    }

    @Test void gatewayFailureRetriesAndPreservesFallbackWithoutDirectModelCall() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/internal/transcript/assign-roles",exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503,-1); exchange.close();
        });
        var result = roles.assignRoles(turns(1));
        assertEquals(2,calls.get());
        assertEquals(DashScopeRoleClient.RoleAssignment.fallback(),result.get(0));
    }

    @Test void invalidGatewayRoleIsRejected() {
        server.createContext("/internal/transcript/assign-roles",exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = json.writeValueAsBytes(Map.of("items",List.of(Map.of("index",0,"role","INVALID","confidence",80,"source","LLM"))));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        assertEquals(DashScopeRoleClient.RoleAssignment.fallback(),roles.assignRoles(turns(1)).get(0));
    }
}

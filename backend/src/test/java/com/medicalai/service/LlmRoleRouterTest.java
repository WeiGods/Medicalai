package com.medicalai.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.medicalai.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
class LlmRoleRouterTest {
    // 兼容构造器：默认公网路由
    @Test void compatOneArgConstructorDefaultsToDashscope() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LlmRoleRouter router = new LlmRoleRouter(dashscope);
        assertEquals(LlmRoute.DASHSCOPE, router.roleRoute());
        router.assignRoles(List.of(Map.of("index", 0, "text", "嗯")));
        verify(dashscope).assignRoles(anyList());
    }
    @Test void compatTwoArgConstructorDefaultsToDashscope() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, mock(AiServiceClient.class));
        assertEquals(LlmRoute.DASHSCOPE, router.roleRoute());
        assertEquals(dashscope.isConfigured(), router.isConfigured());
    }
    @Test void defaultRouteDelegatesToDashscope() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        var expected = Map.of(0, new DashScopeRoleClient.RoleAssignment("DOCTOR", 90, "LLM"));
        when(dashscope.assignRoles(anyList())).thenReturn(expected);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "DASHSCOPE");
        assertEquals(LlmRoute.DASHSCOPE, router.roleRoute());
        assertSame(expected, router.assignRoles(List.of(Map.of("index", 0, "text", "嗯"))));
        verify(dashscope).assignRoles(anyList());
        verify(local, never()).assignRoles(anyList());
    }
    @Test void localRouteDelegatesToLocalClient() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        var expected = Map.of(0, new DashScopeRoleClient.RoleAssignment("PATIENT", 70, "LLM"));
        when(local.assignRoles(anyList())).thenReturn(expected);
        when(local.isConfigured()).thenReturn(true);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "LOCAL");
        assertEquals(LlmRoute.LOCAL, router.roleRoute());
        assertTrue(router.isConfigured());
        assertSame(expected, router.assignRoles(List.of(Map.of("index", 0, "text", "嗯"))));
        verify(local).assignRoles(anyList());
        verify(dashscope, never()).assignRoles(anyList());
    }
    @Test void localRouteWithDisabledClientFallsBackWithoutContactingDashscope() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        when(local.isConfigured()).thenReturn(false);
        when(local.assignRoles(anyList())).thenReturn(Map.of(
                0, new DashScopeRoleClient.RoleAssignment("OTHER", null, "FALLBACK")));
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "LOCAL");
        assertFalse(router.isConfigured());
        var result = router.assignRoles(List.of(Map.of("index", 0, "text", "嗯")));
        assertEquals("FALLBACK", result.get(0).source());
        verify(local).assignRoles(anyList());
        verify(dashscope, never()).assignRoles(anyList());
    }
    @Test void validateRequestedRouteAllowsBlankOrMatchingRoute() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, mock(LocalLlmRoleClient.class), "DASHSCOPE");
        assertDoesNotThrow(() -> router.validateRequestedRoute(null));
        assertDoesNotThrow(() -> router.validateRequestedRoute("  "));
        assertDoesNotThrow(() -> router.validateRequestedRoute("DASHSCOPE"));
        assertDoesNotThrow(() -> router.validateRequestedRoute("dashscope"));
    }
    @Test void validateRequestedRouteRejectsMismatch() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LlmRoleRouter dashscopeRouter = new LlmRoleRouter(dashscope, mock(LocalLlmRoleClient.class), "DASHSCOPE");
        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> dashscopeRouter.validateRequestedRoute("LOCAL"));
        assertEquals("LLM_ROUTE_MISMATCH", mismatch.code());

        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        LlmRoleRouter localRouter = new LlmRoleRouter(dashscope, local, "LOCAL");
        assertDoesNotThrow(() -> localRouter.validateRequestedRoute("LOCAL"));
        mismatch = assertThrows(BusinessException.class,
                () -> localRouter.validateRequestedRoute("DASHSCOPE"));
        assertEquals("LLM_ROUTE_MISMATCH", mismatch.code());
        mismatch = assertThrows(BusinessException.class,
                () -> localRouter.validateRequestedRoute("UNKNOWN"));
        assertEquals("LLM_ROUTE_MISMATCH", mismatch.code());
    }
}
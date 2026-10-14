package com.medicalai.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.medicalai.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
class LlmRoleRouterTest {
    private static final List<Map<String, Object>> TURNS = List.of(Map.of("index", 0, "text", "嗯"));

    // 兼容构造器：默认公网路由
    @Test void compatOneArgConstructorDefaultsToDashscope() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LlmRoleRouter router = new LlmRoleRouter(dashscope);
        assertEquals(LlmRoute.DASHSCOPE, router.roleRoute());
        router.assignRoles(TURNS);
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
        assertSame(expected, router.assignRoles(TURNS));
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
        assertSame(expected, router.assignRoles(TURNS));
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
        var result = router.assignRoles(TURNS);
        assertEquals("FALLBACK", result.get(0).source());
        verify(local, never()).assignRoles(anyList());
        verify(dashscope, never()).assignRoles(anyList());
    }
    // 显式指定 LOCAL 但内网未启用：只降级，绝不改走公网
    @Test void explicitLocalRouteNeverFallsBackToDashscopeWhenUnavailable() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        when(local.isConfigured()).thenReturn(false);
        when(dashscope.isConfigured()).thenReturn(true);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "DASHSCOPE");
        var result = router.assignRoles(LlmRoute.LOCAL, TURNS);
        assertEquals("FALLBACK", result.get(0).source());
        verify(dashscope, never()).assignRoles(anyList());
    }
    // 能力驱动：可用集合只包含真的能调用的路由
    @Test void availableRoutesReflectConfiguredClients() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "DASHSCOPE");
        assertEquals(List.of(), router.availableRoutes());
        assertNull(router.preferredRoute());

        when(dashscope.isConfigured()).thenReturn(true);
        assertEquals(List.of(LlmRoute.DASHSCOPE), router.availableRoutes());
        assertEquals(LlmRoute.DASHSCOPE, router.preferredRoute());

        when(local.isConfigured()).thenReturn(true);
        assertEquals(List.of(LlmRoute.DASHSCOPE, LlmRoute.LOCAL), router.availableRoutes());
    }
    // 默认路由不可用时，界面默认选中第一条可用路由
    @Test void preferredRouteFallsBackToFirstAvailableWhenDefaultIsMissing() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        when(local.isConfigured()).thenReturn(true);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "DASHSCOPE");
        assertEquals(LlmRoute.LOCAL, router.preferredRoute());
    }
    @Test void resolveRequestedRouteFollowsDefaultWhenBlank() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "LOCAL");
        // 空值跟随默认路由，即使默认路由当前不可用也不在这里报错（保持既有"响亮失败"语义）
        assertEquals(LlmRoute.LOCAL, router.resolveRequestedRoute(null));
        assertEquals(LlmRoute.LOCAL, router.resolveRequestedRoute("  "));
    }
    @Test void resolveRequestedRouteAcceptsBothAvailableRoutes() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        when(dashscope.isConfigured()).thenReturn(true);
        when(local.isConfigured()).thenReturn(true);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "DASHSCOPE");
        assertEquals(LlmRoute.LOCAL, router.resolveRequestedRoute("LOCAL"));
        assertEquals(LlmRoute.LOCAL, router.resolveRequestedRoute("local"));
        assertEquals(LlmRoute.DASHSCOPE, router.resolveRequestedRoute("DASHSCOPE"));
    }
    @Test void resolveRequestedRouteRejectsUnavailableRoute() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient local = mock(LocalLlmRoleClient.class);
        when(local.isConfigured()).thenReturn(false);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, local, "DASHSCOPE");
        BusinessException unavailable = assertThrows(BusinessException.class,
                () -> router.resolveRequestedRoute("LOCAL"));
        assertEquals("LLM_ROUTE_UNAVAILABLE", unavailable.code());
        assertEquals(HttpStatus.CONFLICT, unavailable.status());
    }
    @Test void resolveRequestedRouteRejectsUnknownProvider() {
        DashScopeRoleClient dashscope = mock(DashScopeRoleClient.class);
        LlmRoleRouter router = new LlmRoleRouter(dashscope, mock(LocalLlmRoleClient.class), "DASHSCOPE");
        assertEquals("INVALID_LLM_PROVIDER",
                assertThrows(BusinessException.class, () -> router.resolveRequestedRoute("UNKNOWN")).code());
        assertEquals("INVALID_LLM_PROVIDER",
                assertThrows(BusinessException.class, () -> router.resolveRequestedRoute("MIXED")).code());
    }
}

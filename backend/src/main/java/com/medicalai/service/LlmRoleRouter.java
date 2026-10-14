package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 角色识别 LLM 路由：ASR 的 MOSS 匿名推理结果在传给在线 LLM 做 speaker 识别前的
 * 最后一道路由。ASR 路由只决定文本、时间和声学说话人编号的来源，而角色识别走公网
 * LLM 还是内网本地 LLM，由请求显式指定或 {@code medicalai.llm.role-route} 的默认值决定。
 *
 * <p>路由是**能力驱动**的：界面上只出现当前真的能调用的路由（公网需要配置密钥、内网需要启用
 * 本地适配器）。请求里显式指定的路由不可用时报错，绝不悄悄换一条路；不指定则跟随默认路由，
 * 默认路由不可用时保持既有的"响亮失败 + 降级不丢转写"语义。
 */
@Service
public class LlmRoleRouter {
    private static final Logger LOG = LoggerFactory.getLogger(LlmRoleRouter.class);

    private final DashScopeRoleClient dashscope;
    private final LocalLlmRoleClient local;
    private final LlmRoute defaultRoute;

    @Autowired
    public LlmRoleRouter(
            DashScopeRoleClient dashscope,
            LocalLlmRoleClient local,
            @Value("${medicalai.llm.role-route:DASHSCOPE}") String configuredRoute) {
        this.dashscope = dashscope;
        this.local = local;
        this.defaultRoute = LlmRoute.fromRequested(configuredRoute) == LlmRoute.LOCAL
                ? LlmRoute.LOCAL
                : LlmRoute.DASHSCOPE;
    }

    /** 兼容旧调用方；默认使用公网路由。 */
    public LlmRoleRouter(DashScopeRoleClient dashscope) {
        this(dashscope, null, "DASHSCOPE");
    }

    /** 兼容旧调用方；忽略遗留的内网网关客户端，默认使用公网路由。 */
    public LlmRoleRouter(DashScopeRoleClient dashscope, AiServiceClient ignoredInternalClient) {
        this(dashscope, null, "DASHSCOPE");
    }

    /** 后端配置的默认角色识别路由（{@code LLM_ROLE_ROUTE}）。 */
    public LlmRoute roleRoute() {
        return defaultRoute;
    }

    /** 默认路由当前是否可用（沿用旧语义：LOCAL 需启用内网适配器，公网需配置密钥）。 */
    public boolean isConfigured() {
        return isAvailable(defaultRoute);
    }

    /** 指定路由当前是否可以真正发出调用。 */
    public boolean isAvailable(LlmRoute route) {
        if (route == null) {
            return false;
        }
        return switch (route) {
            case DASHSCOPE -> dashscope != null && dashscope.isConfigured();
            case LOCAL -> local != null && local.isConfigured();
            default -> false;
        };
    }

    /** 当前真正可用的角色识别路由；前端据此渲染"公网 / 内网"按钮。 */
    public List<LlmRoute> availableRoutes() {
        List<LlmRoute> routes = new ArrayList<>();
        for (LlmRoute route : List.of(LlmRoute.DASHSCOPE, LlmRoute.LOCAL)) {
            if (isAvailable(route)) {
                routes.add(route);
            }
        }
        return List.copyOf(routes);
    }

    /**
     * 下发给界面的推荐路由：默认路由可用就用默认，否则退到第一条可用路由。
     * 只影响界面默认选中项，不改变 {@link #resolveRequestedRoute} 与 {@link #assignRoles} 的语义。
     */
    public LlmRoute preferredRoute() {
        if (isAvailable(defaultRoute)) {
            return defaultRoute;
        }
        List<LlmRoute> routes = availableRoutes();
        return routes.isEmpty() ? null : routes.get(0);
    }

    /**
     * 解析本次操作实际使用的角色识别路由。
     *
     * <p>null / 空白表示"跟随默认路由"；显式指定的路由必须是当前可用路由，否则抛出
     * {@code LLM_ROUTE_UNAVAILABLE}（409），避免医生以为切到了内网、实际却发出去了公网请求。
     */
    public LlmRoute resolveRequestedRoute(String requestedProvider) {
        if (requestedProvider == null || requestedProvider.isBlank()) {
            return defaultRoute;
        }
        LlmRoute requested = LlmRoute.fromRequested(requestedProvider);
        if (!requested.isCallable()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LLM_PROVIDER",
                    "角色识别路由只支持 DASHSCOPE 或 LOCAL");
        }
        if (!isAvailable(requested)) {
            throw BusinessException.conflict("LLM_ROUTE_UNAVAILABLE", unavailableMessage(requested));
        }
        return requested;
    }

    /** 说清"是哪条路不可用、要改哪个开关"，不要让人误以为是另一条路的凭据问题。 */
    private static String unavailableMessage(LlmRoute route) {
        return route == LlmRoute.LOCAL
                ? "内网 LLM 当前不可用：请在启动后端时设置 LOCAL_LLM_ENABLED=true"
                        + "（或 --medicalai.local-llm.enabled=true），并确认 LOCAL_LLM_ENDPOINT 可访问"
                : "公网 LLM 当前不可用：请配置 DASHSCOPE_API_KEY 后重启后端";
    }

    public Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoles(List<Map<String, Object>> turns) {
        return assignRoles(defaultRoute, turns);
    }

    /**
     * 按指定路由执行角色识别；LOCAL 不可用时只返回降级角色，绝不改走公网。
     */
    public Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoles(
            LlmRoute route, List<Map<String, Object>> turns) {
        LlmRoute effective = route == null || !route.isCallable() ? defaultRoute : route;
        if (effective == LlmRoute.LOCAL) {
            if (local == null || !local.isConfigured()) {
                LOG.warn("角色识别路由为 LOCAL 但内网本地 LLM 未启用，返回降级角色（不会改走公网 LLM）");
                return fallback(turns);
            }
            return local.assignRoles(turns);
        }
        return dashscope.assignRoles(turns);
    }

    private Map<Integer, DashScopeRoleClient.RoleAssignment> fallback(List<Map<String, Object>> turns) {
        Map<Integer, DashScopeRoleClient.RoleAssignment> result = new LinkedHashMap<>();
        if (turns == null) {
            return result;
        }
        for (Map<String, Object> turn : turns) {
            Object index = turn == null ? null : turn.get("index");
            if (index instanceof Number number) {
                result.put(number.intValue(), DashScopeRoleClient.RoleAssignment.fallback());
            }
        }
        return result;
    }
}

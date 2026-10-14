package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 按选定的 LLM 路由分发结构化提取请求；这里没有跨公网/内网的兜底逻辑。
 *
 * <p>路由是**能力驱动**的：界面上只出现当前真的能调用的路由（公网需要配置密钥、内网需要启用
 * 本地提取适配器）。显式指定的路由不可用时直接报错，不会悄悄改走另一条路；
 * 不指定则跟随 {@code medicalai.llm.extraction-route}（默认公网，保持既有行为）。
 */
@Service
public class ClinicalExtractionRouter {
    private final DashScopeClinicalExtractionClient dashscope;
    private final LocalLlmExtractionClient local;
    private final LlmRoute defaultRoute;

    public ClinicalExtractionRouter(
            DashScopeClinicalExtractionClient dashscope,
            LocalLlmExtractionClient local,
            @Value("${medicalai.llm.extraction-route:DASHSCOPE}") String configuredRoute) {
        this.dashscope = dashscope;
        this.local = local;
        this.defaultRoute = LlmRoute.fromRequested(configuredRoute) == LlmRoute.LOCAL
                ? LlmRoute.LOCAL
                : LlmRoute.DASHSCOPE;
    }

    /** 后端配置的默认提取路由（{@code LLM_EXTRACTION_ROUTE}）。 */
    public LlmRoute defaultRoute() {
        return defaultRoute;
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

    /** 当前真正可用的提取路由；前端据此渲染"公网 / 内网"按钮。 */
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
     * 只影响界面默认选中项，不改变 {@link #resolveRoute} 的分发语义。
     */
    public LlmRoute preferredRoute() {
        if (isAvailable(defaultRoute)) {
            return defaultRoute;
        }
        List<LlmRoute> routes = availableRoutes();
        return routes.isEmpty() ? null : routes.get(0);
    }

    /** 供 VO 使用的路由描述（来源 + 可用集合 + 是否需要医生选择）。 */
    public LlmRouting routing() {
        List<LlmRoute> routes = availableRoutes();
        LlmRoute preferred = preferredRoute();
        return new LlmRouting(preferred == null ? defaultRoute : preferred, routes, routes.size() > 1);
    }

    /**
     * 解析本次提取实际使用的路由。
     *
     * <p>null / 空白表示跟随默认路由（默认路由不可用时由客户端抛出明确的配置错误，保持既有行为）；
     * 显式指定的路由必须是当前可用路由，否则报错。
     */
    public LlmRoute resolveRoute(String requestedProvider) {
        if (requestedProvider == null || requestedProvider.isBlank()) {
            return defaultRoute;
        }
        LlmRoute requested = LlmRoute.fromRequested(requestedProvider);
        if (!requested.isCallable()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LLM_PROVIDER",
                    "信息提取路由只支持 DASHSCOPE 或 LOCAL");
        }
        if (!isAvailable(requested)) {
            throw BusinessException.conflict("LLM_ROUTE_UNAVAILABLE", unavailableMessage(requested));
        }
        return requested;
    }

    /** 说清"是哪条路不可用、要改哪个开关"。 */
    private static String unavailableMessage(LlmRoute route) {
        return route == LlmRoute.LOCAL
                ? "内网 LLM 当前不可用：请设置 LOCAL_LLM_EXTRACTION_ENABLED=true"
                        + "（未单独设置时跟随 LOCAL_LLM_ENABLED），并确认 LOCAL_LLM_ENDPOINT 可访问"
                : "公网 LLM 当前不可用：请配置 DASHSCOPE_API_KEY 后重启后端";
    }

    public AiServiceClient.ExtractionResponse extract(LlmRoute route, String snapshotHash,
                                                      List<Map<String, Object>> turns) {
        return switch (route) {
            case DASHSCOPE -> dashscope.extract(snapshotHash, turns);
            case LOCAL -> local.extract(snapshotHash, turns);
            default -> throw new IllegalArgumentException("不可调用的 LLM 路由：" + route);
        };
    }
}

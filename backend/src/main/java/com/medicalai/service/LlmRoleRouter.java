package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 角色识别 LLM 路由：ASR 的 MOSS 匿名推理结果在传给在线 LLM 做 speaker 识别前的
 * 最后一道路由。ASR 路由只决定文本、时间和声学说话人编号的来源，而角色识别走公网
 * LLM 还是内网本地 LLM，由 {@code medicalai.llm.role-route} 决定。
 */
@Service
public class LlmRoleRouter {
    private static final Logger LOG = LoggerFactory.getLogger(LlmRoleRouter.class);

    private final DashScopeRoleClient dashscope;
    private final LocalLlmRoleClient local;
    private final LlmRoute roleRoute;

    @Autowired
    public LlmRoleRouter(
            DashScopeRoleClient dashscope,
            LocalLlmRoleClient local,
            @Value("${medicalai.llm.role-route:DASHSCOPE}") String configuredRoute) {
        this.dashscope = dashscope;
        this.local = local;
        this.roleRoute = LlmRoute.fromRequested(configuredRoute) == LlmRoute.LOCAL
                ? LlmRoute.LOCAL
                : LlmRoute.DASHSCOPE;
    }

    /** 兼容旧调用方；默认使用公网路由。 */
    public LlmRoleRouter(DashScopeRoleClient dashscope) {
        this(dashscope, null, "DASHSCOPE");
    }

    /** 兼容旧调用方；角色识别走 {@code medicalai.llm.role-route} 配置的路由。 */
    public LlmRoleRouter(DashScopeRoleClient dashscope, AiServiceClient ignoredInternalClient) {
        this(dashscope, null, "DASHSCOPE");
    }

    public LlmRoute roleRoute() {
        return roleRoute;
    }

    public boolean isConfigured() {
        return roleRoute == LlmRoute.LOCAL ? local.isConfigured() : dashscope.isConfigured();
    }

    /**
     * 校验重分类请求指定的路由与当前角色识别路由一致。
     *
     * <p>null / 空白表示"跟随当前路由"直接放行；显式指定的路由必须等于 {@link #roleRoute()}，
     * 否则抛出冲突异常（例如当前为公网路由却请求 LOCAL，或反之）。
     */
    public void validateRequestedRoute(String requestedProvider) {
        if (requestedProvider == null || requestedProvider.isBlank()) {
            return;
        }
        LlmRoute requested = LlmRoute.fromRequested(requestedProvider);
        if (requested != roleRoute) {
            throw BusinessException.conflict("LLM_ROUTE_MISMATCH",
                    "角色识别当前路由为 " + LlmRouteResolver.label(roleRoute)
                            + "，无法按 " + LlmRouteResolver.label(requested) + " 路由执行");
        }
    }

    public Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoles(List<Map<String, Object>> turns) {
        if (roleRoute == LlmRoute.LOCAL) {
            if (!local.isConfigured()) {
                LOG.warn("角色识别路由为 LOCAL 但内网本地 LLM 未启用，返回降级角色（不会改走公网 LLM）");
            }
            return local.assignRoles(turns);
        }
        return dashscope.assignRoles(turns);
    }
}
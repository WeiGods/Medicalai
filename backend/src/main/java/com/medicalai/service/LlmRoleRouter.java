package com.medicalai.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 角色判断与结构化提取使用同一来源路由，防止本地 ASR 文本意外发送至公网。 */
@Service
public class LlmRoleRouter {
    private final DashScopeRoleClient dashscope;
    private final AiServiceClient internal;

    public LlmRoleRouter(DashScopeRoleClient dashscope, AiServiceClient internal) {
        this.dashscope = dashscope;
        this.internal = internal;
    }

    public Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoles(
            LlmRoute route, List<Map<String, Object>> turns) {
        return switch (route) {
            case DASHSCOPE -> dashscope.assignRoles(turns);
            case LOCAL -> internal.assignRoleAssignments(turns);
            default -> throw new IllegalArgumentException("不可调用的角色判断路由：" + route);
        };
    }
}

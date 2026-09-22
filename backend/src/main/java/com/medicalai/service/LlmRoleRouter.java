package com.medicalai.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 角色识别统一汇入公网 LLM；ASR 路由只决定文本、时间和声学说话人编号的来源。 */
@Service
public class LlmRoleRouter {
    private final DashScopeRoleClient dashscope;

    @org.springframework.beans.factory.annotation.Autowired
    public LlmRoleRouter(DashScopeRoleClient dashscope) {
        this.dashscope = dashscope;
    }

    /** 兼容旧调用方；角色识别已统一使用公网客户端。 */
    public LlmRoleRouter(DashScopeRoleClient dashscope, AiServiceClient ignoredInternalClient) {
        this(dashscope);
    }

    public LlmRoute roleRoute() {
        return LlmRoute.DASHSCOPE;
    }

    public boolean isConfigured() {
        return dashscope.isConfigured();
    }

    public Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoles(List<Map<String, Object>> turns) {
        return dashscope.assignRoles(turns);
    }
}

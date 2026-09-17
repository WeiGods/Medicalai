package com.medicalai.service;

import java.util.List;

/** 当前快照的来源路由说明，供 API 和前端展示，实际调用仍由服务端再次校验。 */
public record LlmRouting(LlmRoute sourceRoute, List<LlmRoute> availableRoutes,
                         boolean selectionRequired) {
    public LlmRouting {
        availableRoutes = List.copyOf(availableRoutes);
    }
}

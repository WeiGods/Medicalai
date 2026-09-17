package com.medicalai.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 根据已校验的快照路由分发提取请求；这里没有跨公网/内网的兜底逻辑。 */
@Service
public class ClinicalExtractionRouter {
    private final DashScopeClinicalExtractionClient dashscope;
    private final AiServiceClient internal;

    public ClinicalExtractionRouter(DashScopeClinicalExtractionClient dashscope, AiServiceClient internal) {
        this.dashscope = dashscope;
        this.internal = internal;
    }

    public AiServiceClient.ExtractionResponse extract(LlmRoute route, String snapshotHash,
                                                      List<Map<String, Object>> turns) {
        return switch (route) {
            case DASHSCOPE -> dashscope.extract(snapshotHash, turns);
            case LOCAL -> internal.extract(snapshotHash, turns);
            default -> throw new IllegalArgumentException("不可调用的 LLM 路由：" + route);
        };
    }
}

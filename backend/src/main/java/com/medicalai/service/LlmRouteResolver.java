package com.medicalai.service;

import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.RecordingMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 从不可变快照的来源句段反查 ASR 路由。
 *
 * <p>路由不从前端、最近任务或录音默认值推断，避免公网转写已完成后又误调用内网服务。
 */
@Service
public class LlmRouteResolver {
    private final RecordingMapper recordings;

    public LlmRouteResolver(RecordingMapper recordings) {
        this.recordings = recordings;
    }

    public LlmRouting routing(DialogueSnapshot snapshot) {
        return routing(snapshot.id());
    }

    public LlmRouting routing(UUID snapshotId) {
        Set<LlmRoute> routes = new LinkedHashSet<>();
        for (String value : recordings.snapshotAsrRoutes(snapshotId)) {
            routes.add(LlmRoute.fromAsrRoute(value));
        }
        if (routes.isEmpty() || routes.contains(LlmRoute.UNKNOWN)) {
            return new LlmRouting(LlmRoute.UNKNOWN, List.of(), false);
        }
        if (routes.size() == 1) {
            LlmRoute route = routes.iterator().next();
            return new LlmRouting(route, List.of(route), false);
        }
        return new LlmRouting(LlmRoute.MIXED, List.of(LlmRoute.DASHSCOPE, LlmRoute.LOCAL), true);
    }

    /** 只有混合来源允许医生显式选择；单一路由不能被请求参数覆盖。 */
    public LlmRoute resolve(DialogueSnapshot snapshot, String requestedProvider) {
        LlmRouting routing = routing(snapshot);
        if (routing.sourceRoute() == LlmRoute.UNKNOWN) {
            throw BusinessException.conflict("LLM_ROUTE_UNKNOWN", "当前快照无法识别公网或内网来源，请重新转写后再进行 AI 分析");
        }
        if (routing.sourceRoute() == LlmRoute.MIXED) {
            LlmRoute requested = LlmRoute.fromRequested(requestedProvider);
            if (!requested.isCallable()) {
                throw BusinessException.conflict("LLM_ROUTE_SELECTION_REQUIRED",
                        "当前快照同时包含公网和内网转写，请明确选择本次 AI 分析路由");
            }
            return requested;
        }
        if (requestedProvider != null && !requestedProvider.isBlank()
                && LlmRoute.fromRequested(requestedProvider) != routing.sourceRoute()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LLM_ROUTE_MISMATCH",
                    "当前快照只能使用" + label(routing.sourceRoute()) + "，不能切换到其他路由");
        }
        return routing.sourceRoute();
    }

    public static String label(LlmRoute route) {
        return route == LlmRoute.DASHSCOPE ? "公网 LLM（DashScope）" : "内网 LLM";
    }
}

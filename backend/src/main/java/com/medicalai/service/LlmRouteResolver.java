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

    public LlmRouting analysisRouting(DialogueSnapshot snapshot) {
        return routing(snapshot);
    }

    public LlmRouting routing(UUID snapshotId) {
        Set<LlmRoute> routes = new LinkedHashSet<>();
        List<String> sources = recordings.snapshotAsrRoutes(snapshotId);
        boolean missingRoute = sources.stream().anyMatch(value -> value == null || value.isBlank());
        for (String value : sources) {
            if (value != null && !value.isBlank()) routes.add(LlmRoute.fromAsrRoute(value));
        }
        // 老版本公网录音可能没有 asr_route；只有无本地/未知来源的历史数据可按公网兼容。
        if (missingRoute && (routes.isEmpty() || routes.equals(Set.of(LlmRoute.DASHSCOPE)))) {
            routes.add(LlmRoute.DASHSCOPE);
        } else if (missingRoute) {
            routes.add(LlmRoute.UNKNOWN);
        }
        if (routes.isEmpty() || routes.contains(LlmRoute.UNKNOWN)) {
            return new LlmRouting(LlmRoute.UNKNOWN, List.of(), false);
        }
        if (routes.size() == 1) {
            LlmRoute route = routes.iterator().next();
            return new LlmRouting(route, List.of(route), false);
        }
        return new LlmRouting(LlmRoute.MIXED, List.of(), false);
    }

    /** 单一路由跟随快照；混合或未知来源禁止把整份快照交给任意一路模型。 */
    public LlmRoute resolve(DialogueSnapshot snapshot, String requestedProvider) {
        LlmRouting routing = routing(snapshot);
        if (routing.sourceRoute() == LlmRoute.UNKNOWN) {
            throw BusinessException.conflict("LLM_ROUTE_UNKNOWN", "当前快照来源无法确认，请统一当前接诊的 ASR 路由并重新转写");
        }
        if (routing.sourceRoute() == LlmRoute.MIXED) {
            throw BusinessException.conflict("LLM_ROUTE_MIXED",
                    "当前快照同时包含公网和本地来源，请统一当前接诊的 ASR 路由并重新转写");
        }
        if (requestedProvider != null && !requestedProvider.isBlank()
                && LlmRoute.fromRequested(requestedProvider) != routing.sourceRoute()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LLM_ROUTE_MISMATCH",
                    "当前快照只能使用" + label(routing.sourceRoute()) + "，不能切换到其他路由");
        }
        return routing.sourceRoute();
    }

    public LlmRoute resolveAnalysis(DialogueSnapshot snapshot, String requestedProvider) {
        return resolve(snapshot, requestedProvider);
    }

    public static String label(LlmRoute route) {
        return route == LlmRoute.DASHSCOPE ? "公网 LLM（DashScope）" : "内网 LLM";
    }
}

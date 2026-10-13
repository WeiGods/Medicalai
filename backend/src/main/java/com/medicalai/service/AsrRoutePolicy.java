package com.medicalai.service;

import com.medicalai.domain.Visit;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.VisitMapper;

/** Shared encounter boundary for HTTP submissions and leased workers. */
public final class AsrRoutePolicy {
    private AsrRoutePolicy() {}

    public static LlmRoute locked(Visit visit, VisitMapper visits) {
        String value = visit.processingRoute();
        if (value == null) value = visit.recoveryRoute();
        if (value == null) value = visits.historicalProcessingRoute(visit.id());
        if (value == null) return null;
        LlmRoute route = LlmRoute.fromAsrRoute(value);
        if (!route.isCallable()) throw BusinessException.conflict("ASR_ROUTE_UNKNOWN",
                "历史接诊来源无法确认，请使用整份转写恢复入口");
        return route;
    }

    public static String resolve(Visit visit, VisitMapper visits, String requested) {
        LlmRoute locked = locked(visit, visits);
        LlmRoute route = requested == null || requested.isBlank()
                ? (locked == null ? LlmRoute.DASHSCOPE : locked) : LlmRoute.fromAsrRoute(requested);
        if (!route.isCallable()) throw BusinessException.conflict("ASR_ROUTE_UNKNOWN", "不支持的 ASR 路由");
        if (locked != null && locked != route) throw BusinessException.conflict("ASR_ROUTE_MISMATCH",
                "本次接诊已锁定为" + (locked == LlmRoute.LOCAL ? "本地" : "公网") + " ASR，不能切换路由");
        return route.name();
    }
}

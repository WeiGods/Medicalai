package com.medicalai.service;

import java.util.Locale;

/** 公网与内网 LLM 的受控路由。MIXED/UNKNOWN 只能用于描述快照来源，不能直接调用模型。 */
public enum LlmRoute {
    DASHSCOPE,
    LOCAL,
    MIXED,
    UNKNOWN;

    public static LlmRoute fromAsrRoute(String value) {
        String route = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return switch (route) {
            case "DASHSCOPE", "DASHSCOPE_ROLE_V2" -> DASHSCOPE;
            case "LOCAL" -> LOCAL;
            default -> UNKNOWN;
        };
    }

    public static LlmRoute fromRequested(String value) {
        String route = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        return switch (route) {
            case "DASHSCOPE" -> DASHSCOPE;
            case "LOCAL" -> LOCAL;
            default -> UNKNOWN;
        };
    }

    public boolean isCallable() {
        return this == DASHSCOPE || this == LOCAL;
    }
}

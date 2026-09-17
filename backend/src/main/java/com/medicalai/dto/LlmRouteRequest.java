package com.medicalai.dto;

import jakarta.validation.constraints.Pattern;

/** 混合来源快照由医生明确选择本次分析位置；单一路由忽略空值并拒绝不一致值。 */
public record LlmRouteRequest(@Pattern(regexp = "DASHSCOPE|LOCAL") String provider) {}

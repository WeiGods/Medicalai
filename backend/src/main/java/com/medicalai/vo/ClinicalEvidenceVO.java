package com.medicalai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 单条结构化事实在当前对话快照中的可追溯证据。 */
public record ClinicalEvidenceVO(
        @JsonProperty("turn_index") int turnIndex,
        @JsonProperty("start_ms") long startMs,
        @JsonProperty("end_ms") long endMs,
        String role,
        String quote) {}

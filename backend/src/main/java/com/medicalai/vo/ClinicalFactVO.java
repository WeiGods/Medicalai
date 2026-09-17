package com.medicalai.vo;

import java.util.List;

/** 一个标准问诊字段；空值表示当前对话没有足够的原文依据。 */
public record ClinicalFactVO(String value, Integer confidence, List<ClinicalEvidenceVO> evidence) {}

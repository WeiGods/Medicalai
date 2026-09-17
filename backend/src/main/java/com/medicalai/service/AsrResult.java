package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 解析后的 ASR 句段及生成这些句段的服务商响应。 */
public record AsrResult(List<AsrSegment> segments, JsonNode rawResponse) {}

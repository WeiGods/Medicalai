package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Parsed ASR segments together with the provider payload used to produce them. */
public record AsrResult(List<AsrSegment> segments, JsonNode rawResponse) {}

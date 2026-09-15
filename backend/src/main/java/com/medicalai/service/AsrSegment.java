package com.medicalai.service;

/** Provider-independent sentence; timestamps are milliseconds. */
public record AsrSegment(String text, long startMs, long endMs, Integer speakerId) {}

package com.medicalai.service;

/** 与服务商无关的转写句段，时间戳单位为毫秒。 */
public record AsrSegment(String text, long startMs, long endMs, Integer speakerId) {}

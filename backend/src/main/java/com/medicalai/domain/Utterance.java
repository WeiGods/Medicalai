package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record Utterance(UUID id, UUID recordingId, UUID sessionId, String utteranceId,
                        int revision, String resultType, String text, String role,
                        long startMs, long endMs, boolean current, Instant createdAt, Integer speakerId) {}

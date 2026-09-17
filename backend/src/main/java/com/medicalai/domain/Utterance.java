package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record Utterance(UUID id, UUID recordingId, UUID sessionId, String utteranceId,
                        int revision, String resultType, String text, String role,
                        long startMs, long endMs, boolean current, Instant createdAt, Integer speakerId,
                        String roleSource, Integer roleConfidence, String roleProviderRoute) {
    public Utterance(UUID id, UUID recordingId, UUID sessionId, String utteranceId,
                     int revision, String resultType, String text, String role,
                     long startMs, long endMs, boolean current, Instant createdAt, Integer speakerId,
                     String roleSource, Integer roleConfidence) {
        this(id, recordingId, sessionId, utteranceId, revision, resultType, text, role, startMs, endMs,
                current, createdAt, speakerId, roleSource, roleConfidence, "UNKNOWN");
    }
}

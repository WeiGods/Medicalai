package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record DialogueSnapshot(UUID id, UUID visitId, UUID recordingId, UUID sessionId,
                               int snapshotVersion, String snapshotHash, String authorityStatus,
                               Instant createdAt) {}

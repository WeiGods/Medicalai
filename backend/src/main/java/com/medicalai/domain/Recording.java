package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record Recording(UUID id, UUID visitId, String recordingNo, String sourceType,
                        String objectKey, String fileName, String mimeType, Long sizeBytes,
                        Long durationMs, String status, String errorMessage, Instant createdAt) {}

package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record MedicalRecordVersion(UUID id, UUID recordId, int versionNo, UUID sourceSnapshotId,
                                   String sourceSnapshotHash, String contentJson, String editedContentJson,
                                   String generationStatus, String generatedBy, Instant createdAt) {}

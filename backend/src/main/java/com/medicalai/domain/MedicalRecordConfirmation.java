package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record MedicalRecordConfirmation(UUID id, UUID recordId, UUID versionId, UUID doctorId,
                                        boolean declaration, Instant confirmedAt) {}

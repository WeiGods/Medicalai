package com.medicalai.vo;

import java.time.Instant;
import java.util.UUID;

public record MedicalRecordVO(UUID recordId, UUID visitId, int versionNo, String status,
                              String generationStatus, MedicalRecordContentVO content,
                              boolean confirmed, Instant confirmedAt, String confirmedByName,
                              boolean sourceDirty) {}

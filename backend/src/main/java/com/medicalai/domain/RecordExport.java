package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record RecordExport(UUID id, UUID recordId, int versionNo, String format, String status,
                           String doctorName, Instant createdAt) {}

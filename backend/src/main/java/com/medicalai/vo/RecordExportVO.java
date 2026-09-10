package com.medicalai.vo;

import java.time.Instant;
import java.util.UUID;

public record RecordExportVO(UUID id, int versionNo, String format, String status,
                             String doctorName, Instant createdAt) {}

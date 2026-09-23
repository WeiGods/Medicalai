package com.medicalai.vo;

import java.time.Instant;
import java.util.UUID;

public record RecordExportVO(UUID id, int versionNo, UUID templateId, UUID templateRevisionId,
                             String templateName, int templateRevisionNo, String format, String status,
                             String doctorName, Instant createdAt) {
    /** Compatibility constructor for already compiled API callers. */
    public RecordExportVO(UUID id, int versionNo, int templateVersion, String format, String status,
                          String doctorName, Instant createdAt) {
        this(id, versionNo, null, null, "模板 v" + templateVersion, templateVersion, format, status, doctorName, createdAt);
    }

    public int templateVersion() { return templateRevisionNo; }
}

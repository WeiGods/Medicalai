package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record RecordExport(UUID id, UUID recordId, int versionNo, UUID templateId, UUID templateRevisionId,
                           String templateName, int templateRevisionNo, String format, String status,
                           String doctorName, Instant createdAt) {
    /** Source compatibility for older test fixtures and records before the one-time cleanup. */
    public RecordExport(UUID id, UUID recordId, int versionNo, int templateVersion, String format,
                        String status, String doctorName, Instant createdAt) {
        this(id, recordId, versionNo, null, null, "模板 v" + templateVersion, templateVersion,
                format, status, doctorName, createdAt);
    }

    public int templateVersion() { return templateRevisionNo; }
}

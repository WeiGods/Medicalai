package com.medicalai.vo;

import com.medicalai.domain.Recording;
import java.time.Instant;
import java.util.UUID;

public record RecordingVO(UUID id, UUID visitId, String recordingNo, String sourceType,
                          String fileName, String mimeType, Long sizeBytes, Long durationMs,
                          String status, String audioUrl, Instant createdAt) {
    public static RecordingVO from(Recording r) {
        return new RecordingVO(r.id(), r.visitId(), r.recordingNo(), r.sourceType(), r.fileName(),
                r.mimeType(), r.sizeBytes(), r.durationMs(), r.status(),
                r.objectKey() == null && !"SAMPLE".equals(r.sourceType()) ? null : "/api/v1/recordings/" + r.id() + "/audio",
                r.createdAt());
    }
}

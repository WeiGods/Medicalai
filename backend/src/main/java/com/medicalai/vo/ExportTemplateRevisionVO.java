package com.medicalai.vo;

import com.medicalai.domain.ExportTemplateRevision;
import java.time.Instant;
import java.util.UUID;

public record ExportTemplateRevisionVO(UUID id, UUID templateId, int revisionNo, String definitionJson,
                                       UUID createdBy, Instant createdAt) {
    public static ExportTemplateRevisionVO from(ExportTemplateRevision value) {
        return new ExportTemplateRevisionVO(value.id(), value.templateId(), value.revisionNo(), value.definitionJson(),
                value.createdBy(), value.createdAt());
    }
}

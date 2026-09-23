package com.medicalai.vo;

import com.medicalai.domain.ExportTemplateWithRevision;
import java.time.Instant;
import java.util.UUID;

public record ExportTemplateVO(UUID id, String templateKey, String name, String description, String status,
                               boolean defaultTemplate, UUID currentRevisionId, int currentRevisionNo,
                               String definitionJson, Instant updatedAt) {
    public static ExportTemplateVO from(ExportTemplateWithRevision item, boolean includeDefinition) {
        return new ExportTemplateVO(item.template().id(), item.template().templateKey(), item.template().name(),
                item.template().description(), item.template().status(), item.template().defaultTemplate(),
                item.revision().id(), item.revision().revisionNo(), includeDefinition ? item.revision().definitionJson() : null,
                item.revision().createdAt());
    }
}

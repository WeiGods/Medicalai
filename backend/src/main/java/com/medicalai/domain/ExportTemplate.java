package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

/** The mutable pointer and visibility state of a global export template. */
public record ExportTemplate(UUID id, String templateKey, String name, String description, String status,
                             boolean defaultTemplate, UUID currentRevisionId, Instant createdAt, UUID createdBy) {}

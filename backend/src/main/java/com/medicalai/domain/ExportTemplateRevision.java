package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable structured layout definition used by a particular export. */
public record ExportTemplateRevision(UUID id, UUID templateId, int revisionNo, String definitionJson,
                                     UUID createdBy, Instant createdAt) {}

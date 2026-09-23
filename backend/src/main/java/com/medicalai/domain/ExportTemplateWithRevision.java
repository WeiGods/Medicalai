package com.medicalai.domain;

/** A template joined to its selected revision, used by export and management APIs. */
public record ExportTemplateWithRevision(ExportTemplate template, ExportTemplateRevision revision) {}

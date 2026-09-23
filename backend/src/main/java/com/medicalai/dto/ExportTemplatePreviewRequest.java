package com.medicalai.dto;

import jakarta.validation.constraints.Pattern;

public record ExportTemplatePreviewRequest(@Pattern(regexp = "DOCX|PDF", message = "预览格式无效") String format) {
    public String resolvedFormat() { return format == null ? "PDF" : format; }
}

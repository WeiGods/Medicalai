package com.medicalai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A transient, manager-only template definition used solely for preview rendering. */
public record ExportTemplateDraftPreviewRequest(
        @Pattern(regexp = "DOCX|PDF", message = "预览格式无效") String format,
        @NotBlank(message = "模板定义不能为空") @Size(max = 65535, message = "模板定义过长") String definitionJson) {
    public String resolvedFormat() { return format == null ? "PDF" : format; }
}

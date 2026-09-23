package com.medicalai.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record ExportMedicalRecordRequest(
        @NotBlank(message = "导出格式不能为空")
        @Pattern(regexp = "DOCX|PDF", message = "导出格式无效") String format,
        @NotNull(message = "导出模板修订不能为空") UUID templateRevisionId) {
    /** Kept only for in-process legacy tests; HTTP clients must send template_revision_id. */
    public ExportMedicalRecordRequest(String format) {
        this(format, UUID.fromString("00000000-0000-0000-0000-000000000106"));
    }
}

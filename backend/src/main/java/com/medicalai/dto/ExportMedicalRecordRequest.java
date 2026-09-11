package com.medicalai.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;

public record ExportMedicalRecordRequest(
        @NotBlank(message = "导出格式不能为空")
        @Pattern(regexp = "DOCX|PDF", message = "导出格式无效") String format) {}

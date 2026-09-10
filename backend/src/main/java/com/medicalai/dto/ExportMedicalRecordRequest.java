package com.medicalai.dto;

import jakarta.validation.constraints.Pattern;

public record ExportMedicalRecordRequest(@Pattern(regexp = "DOCX|PDF", message = "导出格式无效") String format) {}

package com.medicalai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;

public record ExportMedicalRecordRequest(
        @NotBlank(message = "导出格式不能为空")
        @Pattern(regexp = "DOCX|PDF", message = "导出格式无效") String format,
        @NotNull(message = "导出模板版本不能为空")
        @Min(value = 1, message = "导出模板版本无效")
        @Max(value = 999, message = "导出模板版本无效") Integer templateVersion) {
    /** 兼容旧客户端：未传模板版本时采用当前模板。 */
    public ExportMedicalRecordRequest(String format) {
        this(format, 6);
    }
}

package com.medicalai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SaveExportTemplateRevisionRequest(@NotNull(message = "当前模板修订不能为空") UUID currentRevisionId,
                                                @NotBlank(message = "模板定义不能为空") String definitionJson) {}

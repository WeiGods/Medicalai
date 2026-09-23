package com.medicalai.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateExportTemplateRequest(@NotBlank(message = "模板名称不能为空") String name,
                                          String description,
                                          @NotBlank(message = "模板定义不能为空") String definitionJson) {}

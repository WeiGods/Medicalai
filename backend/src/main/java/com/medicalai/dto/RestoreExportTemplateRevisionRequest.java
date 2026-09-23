package com.medicalai.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RestoreExportTemplateRevisionRequest(@NotNull(message = "当前模板修订不能为空") UUID currentRevisionId) {}

package com.medicalai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AiRouteSettingsRequest(
        @NotBlank @Pattern(regexp = "DASHSCOPE|LOCAL") String route) {
}

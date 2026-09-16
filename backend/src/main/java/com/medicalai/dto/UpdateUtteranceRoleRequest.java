package com.medicalai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateUtteranceRoleRequest(
        @NotBlank @Pattern(regexp = "DOCTOR|PATIENT|OTHER") String role) {}

package com.medicalai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveTranscriptRequest(@NotBlank @Size(max = 20000) String transcript) {}

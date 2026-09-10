package com.medicalai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record SaveMedicalRecordRequest(
        @Size(max = 128) String name,
        @Size(max = 16) String gender,
        @Min(0) @Max(150) Integer age,
        @Size(max = 64) String phone,
        @Size(max = 2000) String chief,
        @Size(max = 8000) String present,
        @Size(max = 8000) String past,
        @Size(max = 8000) String opinion,
        @Size(max = 8000) String medication,
        @Size(max = 8000) String followup,
        @Size(max = 128) String doctor,
        @Size(max = 32) String date) {}

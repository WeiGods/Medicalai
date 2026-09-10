package com.medicalai.dto;

import jakarta.validation.constraints.AssertTrue;

public record ConfirmMedicalRecordRequest(@AssertTrue(message = "请勾选医生核对声明") Boolean declaration) {}

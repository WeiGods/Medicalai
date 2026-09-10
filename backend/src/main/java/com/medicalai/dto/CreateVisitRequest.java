package com.medicalai.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateVisitRequest(@NotNull(message = "请选择患者") UUID patientId,
                                 @Size(max = 2000) String chiefComplaint) {}

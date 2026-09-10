package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record Visit(UUID id, String visitNo, UUID patientId, UUID doctorId,
                    String status, String departmentName, String patientNameSnapshot,
                    String patientGenderSnapshot, Integer patientAgeSnapshot,
                    String doctorNameSnapshot, String chiefComplaint, int version, Instant createdAt) {}

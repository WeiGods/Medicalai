package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

public record Visit(UUID id, String visitNo, UUID patientId, UUID doctorId,
                    String status, String departmentName, String patientNameSnapshot,
                    String patientGenderSnapshot, Integer patientAgeSnapshot,
                    String doctorNameSnapshot, String chiefComplaint, int version, Instant startedAt,
                    Instant completedAt, Instant createdAt) {
    public Visit(UUID id, String visitNo, UUID patientId, UUID doctorId,
                 String status, String departmentName, String patientNameSnapshot,
                 String patientGenderSnapshot, Integer patientAgeSnapshot,
                 String doctorNameSnapshot, String chiefComplaint, int version, Instant createdAt) {
        this(id, visitNo, patientId, doctorId, status, departmentName, patientNameSnapshot,
                patientGenderSnapshot, patientAgeSnapshot, doctorNameSnapshot, chiefComplaint, version,
                null, null, createdAt);
    }

    public Instant lastActivityAt() {
        if (completedAt != null) return completedAt;
        if (startedAt != null) return startedAt;
        return createdAt;
    }
}

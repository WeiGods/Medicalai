package com.medicalai.vo;

import com.medicalai.domain.Visit;
import java.time.Instant;
import java.util.UUID;

public record VisitVO(UUID id, String visitNo, UUID patientId, UUID doctorId,
                      String status, String departmentName, String patientName,
                      String patientGender, Integer patientAge, String doctorName,
                      String chiefComplaint, int version, Instant createdAt, Instant lastActivityAt) {
    public static VisitVO from(Visit v) {
        return new VisitVO(v.id(), v.visitNo(), v.patientId(), v.doctorId(), v.status(),
                v.departmentName(), v.patientNameSnapshot(), v.patientGenderSnapshot(),
                v.patientAgeSnapshot(), v.doctorNameSnapshot(), v.chiefComplaint(), v.version(), v.createdAt(),
                v.lastActivityAt());
    }
}

package com.medicalai.vo;

import com.medicalai.domain.Visit;
import java.time.Instant;
import java.util.UUID;

public record VisitVO(UUID id, String visitNo, UUID patientId, UUID doctorId,
                      String status, String departmentName, String patientName,
                      String patientGender, Integer patientAge, String doctorName,
                      String chiefComplaint, int version, Instant createdAt, Instant lastActivityAt,
                      String processingRoute, String recoveryRoute) {
    public VisitVO(UUID id, String visitNo, UUID patientId, UUID doctorId,
                   String status, String departmentName, String patientName,
                   String patientGender, Integer patientAge, String doctorName,
                   String chiefComplaint, int version, Instant createdAt, Instant lastActivityAt,
                   String processingRoute) {
        this(id, visitNo, patientId, doctorId, status, departmentName, patientName, patientGender,
                patientAge, doctorName, chiefComplaint, version, createdAt, lastActivityAt, processingRoute, null);
    }
    public VisitVO(UUID id, String visitNo, UUID patientId, UUID doctorId,
                   String status, String departmentName, String patientName,
                   String patientGender, Integer patientAge, String doctorName,
                   String chiefComplaint, int version, Instant createdAt, Instant lastActivityAt) {
        this(id, visitNo, patientId, doctorId, status, departmentName, patientName, patientGender,
                patientAge, doctorName, chiefComplaint, version, createdAt, lastActivityAt, null, null);
    }
    public static VisitVO from(Visit v) {
        return new VisitVO(v.id(), v.visitNo(), v.patientId(), v.doctorId(), v.status(),
                v.departmentName(), v.patientNameSnapshot(), v.patientGenderSnapshot(),
                v.patientAgeSnapshot(), v.doctorNameSnapshot(), v.chiefComplaint(), v.version(), v.createdAt(),
                v.lastActivityAt(), v.processingRoute(), v.recoveryRoute());
    }
}

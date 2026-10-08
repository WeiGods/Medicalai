package com.medicalai.vo;

import java.time.Instant;
import java.util.UUID;

public record AuditLogVO(UUID id, String action, String result, String operatorName,
                         String patientName, String patientNo, String visitNo, String detail, String clientIp,
                         Instant createdAt) {
    public AuditLogVO(UUID id, String action, String result, String operatorName,
                      String patientName, String visitNo, String detail, String clientIp, Instant createdAt) {
        this(id, action, result, operatorName, patientName, null, visitNo, detail, clientIp, createdAt);
    }
}

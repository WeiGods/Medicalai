package com.medicalai.domain;

import java.time.Instant;
import java.util.UUID;

/** 审计日志只保存业务定位信息，不保存病历正文、音频内容或身份凭据。 */
public record AuditLog(UUID id, UUID doctorId, UUID visitId, AuditAction action, UUID resourceId,
                       AuditResourceType resourceType, AuditResult result, String detail, String clientIp,
                       Instant createdAt) {
}

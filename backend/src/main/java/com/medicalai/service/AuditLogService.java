package com.medicalai.service;

import com.medicalai.domain.AuditAction;
import com.medicalai.domain.AuditLog;
import com.medicalai.domain.AuditResourceType;
import com.medicalai.domain.AuditResult;
import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.AuditLogQueryRequest;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.AuditLogMapper;
import com.medicalai.vo.AuditLogPageVO;
import com.medicalai.vo.AuditOperatorVO;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {
    private static final int DETAIL_MAX_LENGTH = 512;
    private static final int CLIENT_IP_MAX_LENGTH = 64;
    private final AuditLogMapper mapper;

    public AuditLogService(AuditLogMapper mapper) {
        this.mapper = mapper;
    }

    public void recordLogin(Doctor doctor, String clientIp) {
        record(doctor.id(), null, AuditAction.LOGIN, null, AuditResourceType.LOGIN, AuditResult.SUCCESS,
                "登录系统", clientIp);
    }

    public void recordRecordingUploaded(UUID doctorId, UUID visitId, UUID recordingId, String fileName) {
        record(doctorId, visitId, AuditAction.RECORDING_UPLOADED, recordingId, AuditResourceType.RECORDING,
                AuditResult.SUCCESS, "上传录音：" + safeFileName(fileName), null);
    }

    public void recordRecordingDeleted(UUID doctorId, UUID visitId, UUID recordingId, String fileName) {
        record(doctorId, visitId, AuditAction.RECORDING_DELETED, recordingId, AuditResourceType.RECORDING,
                AuditResult.SUCCESS, "删除录音：" + safeFileName(fileName), null);
    }

    public void recordMedicalRecordConfirmed(UUID doctorId, UUID visitId, UUID recordId, int versionNo) {
        record(doctorId, visitId, AuditAction.MEDICAL_RECORD_CONFIRMED, recordId, AuditResourceType.MEDICAL_RECORD,
                AuditResult.SUCCESS, "确认病历版本 v" + versionNo, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMedicalRecordExport(UUID doctorId, UUID visitId, UUID exportId, String format,
                                          AuditResult result) {
        record(doctorId, visitId, AuditAction.MEDICAL_RECORD_EXPORT, exportId, AuditResourceType.RECORD_EXPORT, result,
                "导出病历：" + format, null);
    }

    public AuditLogPageVO list(AuthenticatedDoctor current, AuditLogQueryRequest request) {
        requireAuditPermission(current.doctor());
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AUDIT_DATE_RANGE_INVALID", "开始日期不能晚于结束日期");
        }
        return new AuditLogPageVO(mapper.list(request), mapper.count(request), request.normalizedPage(),
                request.normalizedPageSize());
    }

    public List<AuditOperatorVO> operators(AuthenticatedDoctor current) {
        requireAuditPermission(current.doctor());
        return mapper.listOperators();
    }

    private void record(UUID doctorId, UUID visitId, AuditAction action, UUID resourceId, AuditResourceType resourceType,
                        AuditResult result, String detail, String clientIp) {
        mapper.insert(new AuditLog(UUID.randomUUID(), doctorId, visitId, action, resourceId, resourceType, result,
                abbreviate(detail, DETAIL_MAX_LENGTH), abbreviate(clientIp, CLIENT_IP_MAX_LENGTH), null));
    }

    private void requireAuditPermission(Doctor doctor) {
        if (!DoctorRole.from(doctor.role()).canReadAuditLog()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "AUDIT_ACCESS_DENIED", "当前账号无权查看日志审计");
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String safeFileName(String fileName) {
        String normalized = fileName == null ? "" : fileName.strip().replace('\\', '/');
        int separatorIndex = normalized.lastIndexOf('/');
        String baseName = separatorIndex >= 0 ? normalized.substring(separatorIndex + 1) : normalized;
        return baseName.replaceAll("\\p{Cntrl}", " ");
    }
}

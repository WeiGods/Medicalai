package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.AuditAction;
import com.medicalai.domain.AuditLog;
import com.medicalai.domain.AuditResourceType;
import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.dto.AuditLogQueryRequest;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.AuditLogMapper;
import com.medicalai.vo.AuditLogVO;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditLogServiceTest {
    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditLogService service = new AuditLogService(mapper);

    @Test
    void doctorCannotQueryAuditLogs() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.list(
                authenticated("DOCTOR"), new AuditLogQueryRequest(null, null, null, null, 1, 20)));

        assertEquals("AUDIT_ACCESS_DENIED", exception.code());
    }

    @Test
    void departmentHeadCanQueryPagedAuditLogs() {
        AuditLogQueryRequest request = new AuditLogQueryRequest(LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), UUID.randomUUID(), AuditAction.LOGIN, 2, 20);
        AuditLogVO log = new AuditLogVO(UUID.randomUUID(), "LOGIN", "SUCCESS", "科室长",
                null, null, "登录系统", "127.0.0.1", Instant.now());
        when(mapper.list(request)).thenReturn(List.of(log));
        when(mapper.count(request)).thenReturn(21L);

        var page = service.list(authenticated("DEPARTMENT_HEAD"), request);

        assertEquals(List.of(log), page.items());
        assertEquals(21L, page.total());
        assertEquals(2, page.page());
        verify(mapper).list(request);
        verify(mapper).count(request);
    }

    @Test
    void administratorCanQueryAuditLogs() {
        AuditLogQueryRequest request = new AuditLogQueryRequest(null, null, null, null, 1, 20);
        when(mapper.list(request)).thenReturn(List.of());
        when(mapper.count(request)).thenReturn(0L);

        var page = service.list(authenticated("ADMIN"), request);

        assertEquals(0L, page.total());
    }

    @Test
    void uploadAuditContainsOnlySafeFileDetail() {
        UUID doctorId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();

        service.recordRecordingUploaded(doctorId, visitId, recordingId, "C:\\temporary\\门诊录音.wav\n");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertEquals(AuditAction.RECORDING_UPLOADED, log.action());
        assertEquals("上传录音：门诊录音.wav", log.detail());
        assertEquals(recordingId, log.resourceId());
        assertEquals(AuditResourceType.RECORDING, log.resourceType());
    }

    @Test
    void exportAuditKeepsPatientSnapshotIfDeletionAlreadyRemovedTheVisit() {
        UUID doctorId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        UUID exportId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        service.recordMedicalRecordExport(doctorId, visitId, exportId, "PDF", com.medicalai.domain.AuditResult.SUCCESS,
                patientId, "测试患者", "P-001", "V-001");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(mapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertEquals(null, log.visitId());
        assertEquals(patientId, log.patientIdSnapshot());
        assertEquals("测试患者", log.patientNameSnapshot());
        assertEquals("P-001", log.patientNoSnapshot());
        assertEquals("V-001", log.visitNoSnapshot());
    }

    @Test
    void rejectsInvertedDateRange() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.list(
                authenticated("ADMIN"), new AuditLogQueryRequest(LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 1), null, null, 1, 20)));

        assertEquals("AUDIT_DATE_RANGE_INVALID", exception.code());
    }

    private AuthenticatedDoctor authenticated(String role) {
        Doctor doctor = new Doctor(UUID.randomUUID(), "LOCAL_DEMO", "demo", "测试人员", null,
                "神经内科", role, "ACTIVE");
        return new AuthenticatedDoctor(UUID.randomUUID(), doctor);
    }
}

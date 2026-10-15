package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.Patient;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.PatientDeletionMapper;
import com.medicalai.mapper.PatientMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PatientDeletionTransactionTest {
    private final PatientMapper patients = org.mockito.Mockito.mock(PatientMapper.class);
    private final PatientDeletionMapper deletions = org.mockito.Mockito.mock(PatientDeletionMapper.class);
    private final AuditLogService auditLogs = org.mockito.Mockito.mock(AuditLogService.class);
    private final PatientDeletionTransaction transaction = new PatientDeletionTransaction(
            patients, deletions, auditLogs, new ObjectMapper());
    private final UUID patientId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final Patient patient = new Patient(patientId, "P-100", "测试患者", "女", null,
            "138****0000", "", "内科", "ACTIVE", doctorId, "医生");

    @BeforeEach
    void setUp() {
        when(patients.findByIdForUpdate(patientId)).thenReturn(Optional.of(patient));
        when(deletions.lockVisits(patientId)).thenReturn(List.of(new PatientDeletionMapper.VisitRef(visitId, "V-100")));
        when(deletions.hasOpenSession(List.of(visitId))).thenReturn(false);
        when(deletions.lockExportAssets(List.of(visitId))).thenReturn(List.of(
                new PatientDeletionMapper.ExportAsset(UUID.randomUUID(), "PDF", "exports/visit/export.pdf", visitId)));
    }

    @Test
    void doctorsAndAdminsCannotStartPatientDeletion() {
        for (String role : List.of("DOCTOR", "ADMIN")) {
            Doctor doctor = doctor(role);
            BusinessException error = assertThrows(BusinessException.class,
                    () -> transaction.delete(patientId, doctor, "127.0.0.1"));
            assertEquals("PATIENT_DELETE_FORBIDDEN", error.code());
        }

        verify(patients, never()).findByIdForUpdate(any());
        verify(deletions, never()).deletePatientChain(any(), any());
        verify(auditLogs, never()).recordPatientDeleted(any(), any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void refusesOpenRecordingSessionBeforeWritingCleanupOrAudit() {
        when(deletions.hasOpenSession(List.of(visitId))).thenReturn(true);

        BusinessException error = assertThrows(BusinessException.class,
                () -> transaction.delete(patientId, doctor("DEPARTMENT_HEAD"), "127.0.0.1"));

        assertEquals("RECORDING_OPEN", error.code());
        verify(deletions, never()).insertCleanupTask(any(), any(), any(), any());
        verify(deletions, never()).detachAuditLogs(any(), any(), any());
        verify(auditLogs, never()).recordPatientDeleted(any(), any(), any(), any(), any(Integer.class), any());
        verify(deletions, never()).deletePatientChain(any(), any());
    }

    @Test
    void archivesFileTargetsAndWritesDeleteAuditBeforePurgingBusinessRows() {
        transaction.delete(patientId, doctor("DEPARTMENT_HEAD"), "127.0.0.1");

        ArgumentCaptor<String> visitIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> exportKeys = ArgumentCaptor.forClass(String.class);
        verify(deletions).insertCleanupTask(any(UUID.class), visitIds.capture(), exportKeys.capture(), eq(doctorId));
        assertTrue(visitIds.getValue().contains(visitId.toString()));
        assertTrue(exportKeys.getValue().contains("exports/visit/export.pdf"));
        assertTrue(exportKeys.getValue().contains("exports/"));
        verify(deletions).snapshotAuditVisit(patientId, patient.name(), patient.patientNo(), visitId, "V-100");
        verify(deletions).detachAuditLogs(patientId, patient.name(), patient.patientNo());
        verify(auditLogs).recordPatientDeleted(any(), eq(patientId), eq(patient.name()), eq(patient.patientNo()), eq(1), eq("127.0.0.1"));
        verify(deletions).deletePatientChain(patientId, List.of(visitId));
    }

    private Doctor doctor(String role) {
        return new Doctor(doctorId, "test", "user-1", "科室长", null, "内科", role, "ACTIVE");
    }
}

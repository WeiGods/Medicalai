package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.Doctor;
import com.medicalai.dto.PatientDeletionResult;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.PatientDeletionMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatientDeletionServiceTest {
    private final PatientDeletionTransaction transaction = org.mockito.Mockito.mock(PatientDeletionTransaction.class);
    private final PatientDeletionMapper deletions = org.mockito.Mockito.mock(PatientDeletionMapper.class);
    private final AudioStorageService audioStorage = org.mockito.Mockito.mock(AudioStorageService.class);
    private final ExportFileService exportFiles = org.mockito.Mockito.mock(ExportFileService.class);
    private final PatientDeletionService service = new PatientDeletionService(transaction, deletions,
            audioStorage, exportFiles, new ObjectMapper());
    private final UUID deletionId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private final Doctor departmentHead = new Doctor(doctorId, "head", "head-1", "科室长", null,
            "内科", "DEPARTMENT_HEAD", "ACTIVE");

    @Test
    void immediatelyRemovesVisitAndExportFilesAndReturnsCompleted() {
        when(transaction.delete(any(), eq(departmentHead), eq("127.0.0.1"))).thenReturn(deletionId);
        when(deletions.claimCleanupTask(deletionId)).thenReturn(Optional.of(new PatientDeletionMapper.CleanupTask(
                deletionId, "[\"" + visitId + "\"]", "[\"exports/" + visitId + "/record.pdf\"]", 1)));
        when(deletions.cleanupStatus(deletionId)).thenReturn(Optional.of("COMPLETED"));

        PatientDeletionResult result = service.delete(UUID.randomUUID(), departmentHead, "127.0.0.1");

        assertEquals(deletionId, result.deletionId());
        assertEquals("COMPLETED", result.cleanupStatus());
        verify(audioStorage).deleteVisitAssets(visitId);
        verify(exportFiles).deleteStoredExport("exports/" + visitId + "/record.pdf");
        verify(deletions).completeCleanupTask(deletionId);
        verify(deletions, never()).retryCleanupTask(any(), any(Integer.class), any());
    }

    @Test
    void schedulesRetryAndReturnsPendingWhenExternalCleanupFails() {
        when(transaction.delete(any(), eq(departmentHead), eq("127.0.0.1"))).thenReturn(deletionId);
        when(deletions.claimCleanupTask(deletionId)).thenReturn(Optional.of(new PatientDeletionMapper.CleanupTask(
                deletionId, "[\"" + visitId + "\"]", "[]", 2)));
        doThrow(new IllegalStateException("MinIO unavailable")).when(audioStorage).deleteVisitAssets(visitId);
        when(deletions.retryCleanupTask(deletionId, 2, "MinIO unavailable")).thenReturn(true);
        when(deletions.cleanupStatus(deletionId)).thenReturn(Optional.of("PENDING"));

        PatientDeletionResult result = service.delete(UUID.randomUUID(), departmentHead, "127.0.0.1");

        assertEquals("PENDING", result.cleanupStatus());
        verify(deletions).retryCleanupTask(deletionId, 2, "MinIO unavailable");
        verify(deletions, never()).completeCleanupTask(deletionId);
    }

    @Test
    void onlyDepartmentHeadsCanQueryCleanupStatus() {
        Doctor admin = new Doctor(UUID.randomUUID(), "admin", "admin-1", "管理员", null,
                "内科", "ADMIN", "ACTIVE");

        BusinessException error = assertThrows(BusinessException.class, () -> service.status(deletionId, admin));

        assertEquals("PATIENT_DELETE_FORBIDDEN", error.code());
        verify(deletions, never()).cleanupStatus(any());
    }
}

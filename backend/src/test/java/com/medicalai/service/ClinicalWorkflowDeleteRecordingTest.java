package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.Recording;
import com.medicalai.domain.Visit;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class ClinicalWorkflowDeleteRecordingTest {
    VisitMapper visits = mock(VisitMapper.class);
    PatientMapper patients = mock(PatientMapper.class);
    DoctorMapper doctors = mock(DoctorMapper.class);
    RecordingMapper recordings = mock(RecordingMapper.class);
    MedicalRecordMapper records = mock(MedicalRecordMapper.class);
    ClinicalExtractionService extractions = mock(ClinicalExtractionService.class);
    AiServiceClient ai = mock(AiServiceClient.class);
    AudioStorageService storage = mock(AudioStorageService.class);
    AuditLogService auditLogs = mock(AuditLogService.class);
    ClinicalWorkflowService workflow = new ClinicalWorkflowService(visits, patients, doctors, recordings,
            records, extractions, ai, storage, new ObjectMapper(), Clock.systemUTC(),
            mock(TranscriptRoleReclassificationStore.class), new LlmRouteResolver(recordings),
            new LlmRoleRouter(mock(DashScopeRoleClient.class)), auditLogs);
    UUID visitId = UUID.randomUUID(), doctorId = UUID.randomUUID(), recordingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var visit = new Visit(visitId, "V1", UUID.randomUUID(), doctorId, "ACTIVE",
                null, null, null, null, null, null, 1, Instant.now());
        when(visits.find(eq(visitId), eq(doctorId), anyBoolean())).thenReturn(Optional.of(visit));
        when(records.findRecordId(visitId)).thenReturn(Optional.empty());
    }

    Recording recording(String status) {
        return new Recording(recordingId, visitId, "R1", "UPLOAD", "recordings/key.wav",
                "a.wav", "audio/wav", 1L, 100L, status, null, Instant.now());
    }

    void stubDeletable() {
        when(recordings.find(recordingId, doctorId)).thenReturn(Optional.of(recording("FAILED")));
        when(recordings.delete(recordingId)).thenReturn(1);
    }

    @Test
    void failedRecordingDetachesJobBeforeRowDeleteAndCleansObject() {
        stubDeletable();
        workflow.deleteRecording(visitId, recordingId, doctorId);
        InOrder order = inOrder(recordings, storage, auditLogs, extractions);
        order.verify(recordings).detachAsrJobs(recordingId);
        order.verify(recordings).delete(recordingId);
        order.verify(extractions).invalidate(visitId);
        order.verify(auditLogs).recordRecordingDeleted(doctorId, visitId, recordingId, "a.wav");
        order.verify(storage).delete("recordings/key.wav");
    }

    @Test
    void processingRecordingRejectedBeforeAnyDeletion() {
        when(recordings.find(recordingId, doctorId)).thenReturn(Optional.of(recording("PROCESSING")));
        var error = assertThrows(BusinessException.class,
                () -> workflow.deleteRecording(visitId, recordingId, doctorId));
        assertEquals("RECORDING_PROCESSING", error.code());
        verify(recordings, never()).detachAsrJobs(any());
        verify(recordings, never()).delete(any());
        verifyNoInteractions(storage);
    }

    @Test
    void transcribedRecordingPurgesDerivedDataAndDeletesAudioWithoutAuditLog() {
        when(recordings.find(recordingId, doctorId)).thenReturn(Optional.of(recording("DONE")));
        when(recordings.hasActiveAsrJob(visitId)).thenReturn(false);
        when(records.hasAnyConfirmation(visitId)).thenReturn(false);
        when(recordings.delete(recordingId)).thenReturn(1);

        workflow.deleteRecording(visitId, recordingId, doctorId);

        InOrder order = inOrder(recordings, storage);
        order.verify(recordings).purgeVisitDerivedData(visitId);
        order.verify(recordings).requeueRemainingRecordings(visitId);
        order.verify(recordings).detachAsrJobs(recordingId);
        order.verify(recordings).delete(recordingId);
        order.verify(storage).delete("recordings/key.wav");
        verifyNoInteractions(auditLogs);
    }

    @Test
    void concurrentStateChangeReportedWhenRowDeleteMisses() {
        stubDeletable();
        when(recordings.delete(recordingId)).thenReturn(0);
        var error = assertThrows(BusinessException.class,
                () -> workflow.deleteRecording(visitId, recordingId, doctorId));
        assertEquals("RECORDING_STATE_CHANGED", error.code());
        verifyNoInteractions(storage);
    }

    @Test
    void streamSessionForeignKeyConflictBecomesReadableError() {
        stubDeletable();
        when(recordings.delete(recordingId)).thenThrow(new DataIntegrityViolationException("fk"));
        var error = assertThrows(BusinessException.class,
                () -> workflow.deleteRecording(visitId, recordingId, doctorId));
        assertEquals("RECORDING_HAS_STREAM_DATA", error.code());
        assertEquals(HttpStatus.CONFLICT, error.status());
        verifyNoInteractions(storage);
    }

    @Test
    void recordingFromAnotherVisitOrDoctorIsNotFound() {
        when(recordings.find(recordingId, doctorId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(BusinessException.class,
                () -> workflow.deleteRecording(visitId, recordingId, doctorId)).status());
        verifyNoInteractions(storage);
    }
}

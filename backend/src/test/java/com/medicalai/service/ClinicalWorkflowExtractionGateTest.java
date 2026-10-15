package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.domain.Recording;
import com.medicalai.domain.Visit;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.PatientMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClinicalWorkflowExtractionGateTest {
    @Test
    void medicalRecordGenerationIsBlockedWithoutConfirmedExtraction() {
        UUID visitId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        VisitMapper visits = mock(VisitMapper.class);
        RecordingMapper recordings = mock(RecordingMapper.class);
        MedicalRecordMapper records = mock(MedicalRecordMapper.class);
        ClinicalExtractionService extraction = mock(ClinicalExtractionService.class);
        ClinicalWorkflowService workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class),
                mock(DoctorMapper.class), recordings, records, extraction, mock(AiServiceClient.class),
                mock(AudioStorageService.class), new ObjectMapper(), Clock.systemUTC(), mock(DashScopeRoleClient.class),
                mock(TranscriptRoleReclassificationStore.class));
        Visit visit = new Visit(visitId, "V-001", UUID.randomUUID(), doctorId, "ACTIVE", null,
                null, null, null, null, null, 1, Instant.now());
        DialogueSnapshot snapshot = new DialogueSnapshot(snapshotId, visitId, recordingId, UUID.randomUUID(),
                1, "snapshot-hash", "ADOPTED", Instant.now());
        Recording recording = new Recording(recordingId, visitId, "REC-001", "UPLOAD", "key", "test.wav",
                "audio/wav", 1L, 100L, "DONE", null, Instant.now());
        when(visits.find(eq(visitId), eq(doctorId), anyBoolean())).thenReturn(Optional.of(visit));
        when(records.findRecordId(visitId)).thenReturn(Optional.empty());
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(snapshot));
        when(recordings.list(visitId)).thenReturn(List.of(recording));
        when(recordings.transcript(visitId)).thenReturn(Optional.of(new RecordingMapper.TurnState(snapshotId,
                "患者：我头痛三天", false, Instant.now())));
        when(extraction.requireConfirmedFields(visitId, snapshot)).thenThrow(
                BusinessException.conflict("CLINICAL_EXTRACTION_REQUIRED", "请先生成并整体确认当前转写的信息提取结果"));

        BusinessException error = assertThrows(BusinessException.class, () -> workflow.generate(visitId, doctorId));

        assertEquals("CLINICAL_EXTRACTION_REQUIRED", error.code());
    }

    @Test
    void extractionDoesNotLockVisitWhileWaitingForRemoteLlm() {
        UUID visitId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        VisitMapper visits = mock(VisitMapper.class);
        RecordingMapper recordings = mock(RecordingMapper.class);
        MedicalRecordMapper records = mock(MedicalRecordMapper.class);
        ClinicalExtractionService extraction = mock(ClinicalExtractionService.class);
        ClinicalWorkflowService workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class),
                mock(DoctorMapper.class), recordings, records, extraction, mock(AiServiceClient.class),
                mock(AudioStorageService.class), new ObjectMapper(), Clock.systemUTC(), mock(DashScopeRoleClient.class),
                mock(TranscriptRoleReclassificationStore.class));
        Visit visit = new Visit(visitId, "V-001", UUID.randomUUID(), doctorId, "ACTIVE", null,
                null, null, null, null, null, 1, Instant.now());
        DialogueSnapshot snapshot = new DialogueSnapshot(snapshotId, visitId, UUID.randomUUID(), UUID.randomUUID(),
                1, "snapshot-hash", "ADOPTED", Instant.now());
        when(visits.find(eq(visitId), eq(doctorId), anyBoolean())).thenReturn(Optional.of(visit));
        when(records.findRecordId(visitId)).thenReturn(Optional.empty());
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(snapshot));
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("DASHSCOPE"));
        when(extraction.generate(eq(visitId), eq(doctorId), eq(snapshot), eq(LlmRoute.DASHSCOPE))).thenThrow(
                BusinessException.conflict("TEST_STOP", "stop after routing"));

        assertThrows(BusinessException.class, () -> workflow.generateClinicalExtraction(visitId, doctorId));

        verify(visits).find(visitId, doctorId, false);
    }
}

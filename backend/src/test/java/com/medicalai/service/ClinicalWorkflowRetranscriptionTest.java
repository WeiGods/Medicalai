package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.Recording;
import com.medicalai.domain.Visit;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.PatientMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClinicalWorkflowRetranscriptionTest {
    @Test
    void queuesAnExistingTranscribedRecordingWithTheSelectedAsrProvider() {
        UUID visitId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        VisitMapper visits = mock(VisitMapper.class);
        RecordingMapper recordings = mock(RecordingMapper.class);
        MedicalRecordMapper records = mock(MedicalRecordMapper.class);
        ClinicalExtractionService extractions = mock(ClinicalExtractionService.class);
        ClinicalWorkflowService workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class),
                mock(DoctorMapper.class), recordings, records, extractions, mock(AiServiceClient.class),
                mock(AudioStorageService.class), new ObjectMapper(), Clock.systemUTC(),
                mock(TranscriptRoleReclassificationStore.class), new LlmRouteResolver(recordings),
                new LlmRoleRouter(mock(DashScopeRoleClient.class)), mock(AuditLogService.class));
        Visit visit = new Visit(visitId, "V-001", UUID.randomUUID(), doctorId, "ACTIVE", null,
                null, null, null, null, null, 1, Instant.now());
        Recording recording = new Recording(recordingId, visitId, "REC-001", "UPLOAD", "recordings/a.wav",
                "a.wav", "audio/wav", 1L, 100L, "DONE", null, Instant.now());
        RecordingMapper.AsrJob job = new RecordingMapper.AsrJob(jobId, visitId, null, null,
                "PENDING", 0, null, null, "LOCAL");
        when(visits.find(eq(visitId), eq(doctorId), anyBoolean())).thenReturn(Optional.of(visit));
        when(records.findRecordId(visitId)).thenReturn(Optional.empty());
        when(recordings.find(recordingId, doctorId)).thenReturn(Optional.of(recording));
        when(recordings.hasActiveAsrJob(visitId)).thenReturn(false);
        when(recordings.requeueTranscribedRecording(recordingId)).thenReturn(true);
        when(recordings.createAsrJob(visitId, "LOCAL", recordingId)).thenReturn(jobId);
        when(recordings.asrJob(jobId, visitId)).thenReturn(Optional.of(job));

        var result = workflow.retranscribeRecording(visitId, recordingId, doctorId, "LOCAL");

        assertEquals(jobId, result.jobId());
        assertEquals("LOCAL", result.providerRoute());
        verify(recordings).requeueTranscribedRecording(recordingId);
        verify(recordings).createAsrJob(visitId, "LOCAL", recordingId);
        verify(extractions).invalidate(visitId);
    }
}

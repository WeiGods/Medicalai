package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.domain.Recording;
import com.medicalai.domain.Utterance;
import com.medicalai.domain.Visit;
import com.medicalai.dto.UpdateUtteranceRoleRequest;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClinicalWorkflowRoleUpdateTest {
    private final VisitMapper visits = mock(VisitMapper.class);
    private final RecordingMapper recordings = mock(RecordingMapper.class);
    private final MedicalRecordMapper records = mock(MedicalRecordMapper.class);
    private final UUID visitId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private final UUID recordingId = UUID.randomUUID();
    private final UUID utteranceId = UUID.randomUUID();
    private final UUID sourceSnapshotId = UUID.randomUUID();
    private final UUID updatedSnapshotId = UUID.randomUUID();
    private final Recording recording = new Recording(recordingId, visitId, "REC-001", "UPLOAD", "key",
            "test.wav", "audio/wav", 1L, 100L, "DONE", null, Instant.now());
    private final DialogueSnapshot sourceSnapshot = new DialogueSnapshot(sourceSnapshotId, visitId, recordingId,
            UUID.randomUUID(), 1, "source-hash", "ADOPTED", Instant.now());
    private ClinicalWorkflowService workflow;

    @BeforeEach
    void setup() {
        workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class), mock(DoctorMapper.class), recordings,
                records, mock(AiServiceClient.class), mock(AudioStorageService.class), new ObjectMapper(), Clock.systemUTC(),
                mock(DashScopeRoleClient.class), mock(TranscriptRoleReclassificationStore.class));
        Visit visit = new Visit(visitId, "V-001", UUID.randomUUID(), doctorId, "ACTIVE", null,
                null, null, null, null, null, 1, Instant.now());
        when(visits.find(eq(visitId), eq(doctorId), anyBoolean())).thenReturn(Optional.of(visit));
        when(records.status(visitId)).thenReturn("DRAFT");
        when(recordings.list(visitId)).thenReturn(List.of(recording));
    }

    @Test
    void manualRoleUpdateRebuildsSnapshotAndClearsAiConfidence() {
        Utterance automatic = utterance("OTHER", "FALLBACK", null);
        Utterance manual = utterance("DOCTOR", "MANUAL", null);
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(sourceSnapshot), Optional.of(
                new DialogueSnapshot(updatedSnapshotId, visitId, recordingId, sourceSnapshot.sessionId(), 2,
                        "updated-hash", "ADOPTED", Instant.now())));
        when(recordings.utterance(visitId, utteranceId)).thenReturn(Optional.of(automatic));
        when(recordings.listByRecording(recordingId)).thenReturn(List.of(automatic), List.of(manual), List.of(manual));
        when(recordings.transcript(visitId)).thenReturn(
                Optional.of(new RecordingMapper.TurnState(sourceSnapshotId, "说话人 1：请坐", false, Instant.now())),
                Optional.of(new RecordingMapper.TurnState(updatedSnapshotId, "医生：请坐", true, Instant.now())));
        when(recordings.updateRole(visitId, utteranceId, "DOCTOR")).thenReturn(true);
        var capturedTurns = new AtomicReference<List<RecordingMapper.Turn>>();
        when(recordings.createEditedSnapshot(eq(visitId), eq(sourceSnapshot), anyList(), anyString(), eq(doctorId)))
                .thenAnswer(invocation -> {
                    capturedTurns.set(invocation.getArgument(2));
                    return updatedSnapshotId;
                });

        var result = workflow.updateUtteranceRole(visitId, doctorId, utteranceId,
                new UpdateUtteranceRoleRequest("DOCTOR"));

        verify(recordings).updateRole(visitId, utteranceId, "DOCTOR");
        verify(recordings).createEditedSnapshot(eq(visitId), eq(sourceSnapshot), anyList(), anyString(), eq(doctorId));
        assertEquals("DOCTOR", capturedTurns.get().getFirst().role());
        assertEquals("MANUAL", capturedTurns.get().getFirst().roleSource());
        verify(recordings).saveTranscript(visitId, updatedSnapshotId, "医生：请坐", true);
        assertFalse(result.turns().getFirst().roleReviewRequired());
        assertEquals("MANUAL", result.turns().getFirst().roleSource());
    }

    @Test
    void manualRoleUpdateDoesNotRequireAGeneratedMedicalRecord() {
        when(records.status(visitId)).thenReturn("DRAFT");
        Utterance automatic = utterance("OTHER", "FALLBACK", null);
        Utterance manual = utterance("PATIENT", "MANUAL", null);
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(sourceSnapshot), Optional.of(sourceSnapshot));
        when(recordings.utterance(visitId, utteranceId)).thenReturn(Optional.of(automatic));
        when(recordings.listByRecording(recordingId)).thenReturn(List.of(automatic), List.of(manual), List.of(manual));
        when(recordings.transcript(visitId)).thenReturn(
                Optional.of(new RecordingMapper.TurnState(sourceSnapshotId, "说话人 1：请坐", false, Instant.now())),
                Optional.of(new RecordingMapper.TurnState(sourceSnapshotId, "患者：请坐", true, Instant.now())));
        when(recordings.updateRole(visitId, utteranceId, "PATIENT")).thenReturn(true);
        when(recordings.createEditedSnapshot(eq(visitId), eq(sourceSnapshot), anyList(), anyString(), eq(doctorId)))
                .thenReturn(updatedSnapshotId);

        workflow.updateUtteranceRole(visitId, doctorId, utteranceId, new UpdateUtteranceRoleRequest("PATIENT"));

        verify(records).status(visitId);
        verify(recordings).updateRole(visitId, utteranceId, "PATIENT");
    }

    @Test
    void manualRoleUpdateRejectsWhenFullTranscriptWasEdited() {
        Utterance automatic = utterance("OTHER", "FALLBACK", null);
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(sourceSnapshot));
        when(recordings.utterance(visitId, utteranceId)).thenReturn(Optional.of(automatic));
        when(recordings.listByRecording(recordingId)).thenReturn(List.of(automatic));
        when(recordings.transcript(visitId)).thenReturn(Optional.of(
                new RecordingMapper.TurnState(sourceSnapshotId, "医生：这是一段已手工改写的文本", true, Instant.now())));

        BusinessException error = assertThrows(BusinessException.class, () -> workflow.updateUtteranceRole(
                visitId, doctorId, utteranceId, new UpdateUtteranceRoleRequest("DOCTOR")));

        assertEquals("TRANSCRIPT_TEXT_EDITED", error.code());
        verify(recordings, never()).updateRole(any(), any(), any());
    }

    private Utterance utterance(String role, String source, Integer confidence) {
        return new Utterance(utteranceId, recordingId, sourceSnapshot.sessionId(), "u-1", 0, "CANONICAL",
                "请坐", role, 0, 100, true, Instant.now(), 1, source, confidence);
    }
}

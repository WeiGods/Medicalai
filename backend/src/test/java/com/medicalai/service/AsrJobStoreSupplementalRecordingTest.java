package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.Recording;
import com.medicalai.domain.Utterance;
import com.medicalai.mapper.ClinicalExtractionMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AsrJobStoreSupplementalRecordingTest {
    @Test
    void supplementalCompletionBuildsOneSnapshotFromExistingAndNewRecordings() {
        RecordingMapper recordings = mock(RecordingMapper.class);
        ClinicalExtractionMapper extractions = mock(ClinicalExtractionMapper.class);
        VisitMapper visits = mock(VisitMapper.class);
        AsrJobStore store = new AsrJobStore(recordings, mock(MedicalRecordMapper.class),
                visits, extractions);
        UUID visitId = UUID.randomUUID();
        UUID oldRecordingId = UUID.randomUUID();
        UUID newRecordingId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID newSessionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        Recording oldRecording = recording(oldRecordingId, visitId, "REC-001");
        Recording newRecording = recording(newRecordingId, visitId, "REC-002");
        RecordingMapper.AsrJob job = new RecordingMapper.AsrJob(jobId, visitId, newRecordingId,
                null, "RUNNING", 1, null, Instant.now(), "LOCAL");
        RecordingMapper.Turn newTurn = turn("DOCTOR", "补充病情");
        when(visits.find(visitId, false)).thenReturn(Optional.of(new com.medicalai.domain.Visit(
                visitId,"V1",UUID.randomUUID(),UUID.randomUUID(),"ACTIVE",null,null,null,null,null,null,1,Instant.now())));
        when(visits.lockProcessingRoute(visitId,"LOCAL")).thenReturn(true);

        when(recordings.lockAsrLease(jobId, token)).thenReturn(true);
        when(recordings.asrJob(jobId, visitId)).thenReturn(Optional.of(job));
        when(recordings.createSession(visitId, newRecordingId)).thenReturn(newSessionId);
        when(recordings.insertUtterances(eq(visitId), eq(newRecordingId), eq(newSessionId), anyList()))
                .thenReturn(List.of(UUID.randomUUID()));
        when(recordings.list(visitId)).thenReturn(List.of(oldRecording, newRecording));
        when(recordings.listByRecording(oldRecordingId)).thenReturn(List.of(utterance(oldRecordingId, "PATIENT", "原始主诉")));
        when(recordings.listByRecording(newRecordingId)).thenReturn(List.of(utterance(newRecordingId, "DOCTOR", "补充病情")));
        when(recordings.createSnapshot(eq(visitId), eq(newRecordingId), eq(newSessionId), anyList(), anyList(), any()))
                .thenReturn(snapshotId);

        store.complete(job, token, newRecordingId, List.of(newTurn));

        ArgumentCaptor<List<RecordingMapper.Turn>> turns = ArgumentCaptor.forClass(List.class);
        verify(recordings).createSnapshot(eq(visitId), eq(newRecordingId), eq(newSessionId), turns.capture(), anyList(), any());
        assertEquals(List.of("原始主诉", "补充病情"), turns.getValue().stream().map(RecordingMapper.Turn::text).toList());
        verify(recordings).saveTranscript(visitId, snapshotId, "患者：原始主诉\n\n医生：补充病情", false);
        verify(extractions).markCurrentStale(visitId);
        verify(recordings).markAsrSucceeded(jobId);
    }

    private Recording recording(UUID id, UUID visitId, String no) {
        return new Recording(id, visitId, no, "UPLOAD", "recordings/" + no, no + ".wav", "audio/wav",
                100L, 1000L, "DONE", null, Instant.now());
    }

    private Utterance utterance(UUID recordingId, String role, String text) {
        return new Utterance(UUID.randomUUID(), recordingId, UUID.randomUUID(), "u-1", 0, "CANONICAL",
                text, role, 0, 1000, true, Instant.now(), null, "LLM", 95, "LOCAL");
    }

    private RecordingMapper.Turn turn(String role, String text) {
        return new RecordingMapper.Turn(role, text, 0, 1000, null, "LLM", 95, "LOCAL");
    }
}

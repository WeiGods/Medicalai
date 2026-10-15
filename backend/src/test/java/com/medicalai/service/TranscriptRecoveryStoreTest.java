package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.medicalai.domain.*;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranscriptRecoveryStoreTest {
    UUID visitId = UUID.randomUUID(), doctorId = UUID.randomUUID(), snapshotId = UUID.randomUUID();
    VisitMapper visits = mock(VisitMapper.class);
    RecordingMapper recordings = mock(RecordingMapper.class);
    MedicalRecordMapper records = mock(MedicalRecordMapper.class);
    TranscriptRecoveryStore store = new TranscriptRecoveryStore(visits, recordings, records);
    Recording audio = new Recording(UUID.randomUUID(), visitId, "R1", "UPLOAD", "retained.wav", "a.wav", "audio/wav", 1L, 100L, "DONE", null, Instant.now());
    DialogueSnapshot snapshot = new DialogueSnapshot(snapshotId, visitId, audio.id(), UUID.randomUUID(), 1, "mixed-hash", "ADOPTED", Instant.now());

    Visit visit(String locked, String recovery) {
        return new Visit(visitId, "V", UUID.randomUUID(), doctorId, "ACTIVE", null, null, null, null, null, null,
                1, null, null, Instant.now(), locked, recovery);
    }
    @BeforeEach void setup() {
        when(visits.find(visitId, doctorId, true)).thenReturn(Optional.of(visit(null, null)));
        when(visits.historicalProcessingRoute(visitId)).thenReturn("UNKNOWN");
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(snapshot));
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("LOCAL", "DASHSCOPE"));
        when(recordings.list(visitId)).thenReturn(List.of(audio));
    }

    @Test void mixedEncounterRebuildsAllAudioAndPinsRecoveryWithoutPrematureSuccessLock() {
        var prepared = store.prepare(visitId, doctorId, "LOCAL", snapshotId, true);
        UUID jobId = UUID.randomUUID();
        when(recordings.createRebuildJob(visitId, snapshotId, "LOCAL")).thenReturn(jobId);
        assertEquals(jobId, store.rebuild(visitId, doctorId, snapshotId, prepared));
        var order = inOrder(recordings, visits);
        order.verify(recordings).purgeVisitDerivedData(visitId);
        order.verify(recordings).requeueRemainingRecordings(visitId);
        order.verify(recordings).resetRecordingRoutes(visitId);
        order.verify(visits).setRecoveryRoute(visitId, "LOCAL");
        order.verify(recordings).createRebuildJob(visitId, snapshotId, "LOCAL");
        verify(visits, never()).lockProcessingRoute(any(), anyString());
        verify(recordings, never()).delete(any());
    }

    @Test void repeatRequestReturnsExistingJobWithoutClearingAgainEvenAfterFailure() {
        var job = new RecordingMapper.AsrJob(UUID.randomUUID(), visitId, audio.id(), null, "FAILED", 1, "offline", Instant.now(), "LOCAL");
        when(recordings.rebuildJob(visitId, snapshotId)).thenReturn(Optional.of(job));
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.empty());
        assertEquals(job, store.prepare(visitId, doctorId, "LOCAL", snapshotId, true).existing());
        verify(recordings, never()).purgeVisitDerivedData(any());
        assertEquals("ASR_ROUTE_MISMATCH", assertThrows(BusinessException.class,
                () -> store.prepare(visitId, doctorId, "DASHSCOPE", snapshotId, true)).code());
    }

    @Test void confirmationIsRequiredAndAnySignatureHistoryBlocksRecovery() {
        assertEquals("REBUILD_CONFIRMATION_REQUIRED", assertThrows(BusinessException.class,
                () -> store.prepare(visitId, doctorId, "LOCAL", snapshotId, false)).code());
        when(records.hasAnyConfirmation(visitId)).thenReturn(true);
        assertEquals("RECORD_CONFIRMED", assertThrows(BusinessException.class,
                () -> store.prepare(visitId, doctorId, "LOCAL", snapshotId, true)).code());
        verify(recordings, never()).purgeVisitDerivedData(any());
    }

    @Test void changedAudioOrSnapshotBlocksCommitAfterPreflight() {
        var prepared = store.prepare(visitId, doctorId, "LOCAL", snapshotId, true);
        when(recordings.list(visitId)).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> store.rebuild(visitId, doctorId, snapshotId, prepared));
        when(recordings.list(visitId)).thenReturn(List.of(audio));
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(new DialogueSnapshot(UUID.randomUUID(),visitId,audio.id(),snapshot.sessionId(),2,"new","ADOPTED",Instant.now())));
        assertEquals("SOURCE_CHANGED", assertThrows(BusinessException.class, () -> store.rebuild(visitId,doctorId,snapshotId,prepared)).code());
        verify(recordings, never()).purgeVisitDerivedData(any());
    }

    @Test void lockedRouteAndInFlightJobsOrSessionsCannotBeBypassed() {
        when(visits.find(visitId, doctorId, true)).thenReturn(Optional.of(visit("LOCAL", null)));
        assertEquals("ASR_ROUTE_MISMATCH", assertThrows(BusinessException.class,
                () -> store.prepare(visitId,doctorId,"DASHSCOPE",snapshotId,true)).code());
        when(recordings.hasActiveAsrJob(visitId)).thenReturn(true);
        assertEquals("RECORDING_PROCESSING", assertThrows(BusinessException.class,
                () -> store.prepare(visitId,doctorId,"LOCAL",snapshotId,true)).code());
        when(recordings.hasActiveAsrJob(visitId)).thenReturn(false);
        when(visits.hasOpenSession(visitId)).thenReturn(true);
        assertEquals("RECORDING_PROCESSING", assertThrows(BusinessException.class,
                () -> store.prepare(visitId,doctorId,"LOCAL",snapshotId,true)).code());
        verify(recordings, never()).purgeVisitDerivedData(any());
    }

    @Test void normalSingleSourceEncounterUsesExistingWorkflow() {
        when(visits.historicalProcessingRoute(visitId)).thenReturn("LOCAL");
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("LOCAL"));
        assertEquals("REBUILD_NOT_REQUIRED", assertThrows(BusinessException.class,
                () -> store.prepare(visitId,doctorId,"LOCAL",snapshotId,true)).code());
    }

    @Test void missingAudioNeverClearsDraftsOrQueuesTask() {
        var storage = mock(AudioStorageService.class);
        var workflow = new ClinicalWorkflowService(visits,mock(PatientMapper.class),mock(DoctorMapper.class),recordings,records,
                mock(AiServiceClient.class),storage,new com.fasterxml.jackson.databind.ObjectMapper(),java.time.Clock.systemUTC(),
                mock(DashScopeRoleClient.class),mock(TranscriptRoleReclassificationStore.class));
        org.springframework.test.util.ReflectionTestUtils.setField(workflow,"transcriptRecoveryStore",store);
        doThrow(BusinessException.conflict("AUDIO_NOT_FOUND","audio missing")).when(storage).assertAudioAvailable(audio.objectKey());
        assertEquals("AUDIO_NOT_FOUND",assertThrows(BusinessException.class,
                () -> workflow.rebuildTranscript(visitId,doctorId,"LOCAL",snapshotId,true)).code());
        verify(recordings,never()).purgeVisitDerivedData(any());
        verify(recordings,never()).createRebuildJob(any(),any(),anyString());
        verify(visits,never()).setRecoveryRoute(any(),anyString());
    }
}

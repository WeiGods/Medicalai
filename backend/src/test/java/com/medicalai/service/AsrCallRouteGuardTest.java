package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.medicalai.domain.Visit;
import com.medicalai.mapper.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AsrCallRouteGuardTest {
    @ParameterizedTest
    @CsvSource({"PENDING,DASHSCOPE_ROLE_V2,LOCAL", "RUNNING,DASHSCOPE_ROLE_V2,LOCAL",
            "PENDING,DASHSCOPE,LOCAL", "RUNNING,DASHSCOPE,LOCAL",
            "PENDING,LOCAL,DASHSCOPE", "RUNNING,LOCAL,DASHSCOPE"})
    void legacyConflictingTasksFailBeforeAnyNetworkCall(String status, String taskRoute, String locked) {
        checkRejected(status, taskRoute, locked, false);
    }

    @Test void unpinnedEncounterWithTwoActiveRoutesNeverCallsModel() {
        checkRejected("PENDING", "LOCAL", null, true);
    }

    private void checkRejected(String status, String taskRoute, String locked, boolean conflicting) {
        UUID visitId = UUID.randomUUID(), jobId = UUID.randomUUID(), recordingId = UUID.randomUUID();
        RecordingMapper recordings = mock(RecordingMapper.class);
        VisitMapper visits = mock(VisitMapper.class);
        AsrJobStore store = new AsrJobStore(recordings, mock(MedicalRecordMapper.class), visits);
        var job = new RecordingMapper.AsrJob(jobId, visitId, recordingId, "old-task", status, 1, null, Instant.now(), taskRoute);
        when(recordings.claimNextAsrJob(any(), anyString())).thenReturn(Optional.of(job));
        when(recordings.lockAsrLease(eq(jobId), any())).thenReturn(true);
        when(recordings.asrJob(jobId, visitId)).thenReturn(Optional.of(job));
        when(visits.find(visitId, false)).thenReturn(Optional.of(new Visit(visitId, "V", UUID.randomUUID(),
                UUID.randomUUID(), "ACTIVE", null, null, null, null, null, null, 1, null, null, Instant.now(), locked)));
        when(recordings.hasConflictingAsrJob(eq(visitId), eq(jobId), anyString())).thenReturn(conflicting);
        var storage = mock(AudioStorageService.class);
        var cloud = mock(DashScopeAsrClient.class);
        var local = mock(LocalAsrClient.class);
        var router = mock(LlmRoleRouter.class);
        var heartbeat = Executors.newSingleThreadScheduledExecutor();
        try {
            var worker = new AsrJobWorker(store, storage, cloud, local, router, heartbeat, 600000);
            if ("LOCAL".equals(taskRoute)) worker.processLocal(); else worker.processPublic();
            verifyNoInteractions(storage, cloud, local, router);
            verify(recordings).markAsrFailed(eq(jobId), anyString());
            verify(recordings, never()).beginAsrRecording(any(), any(), anyString());
            verify(recordings, never()).updateStatus(any(), anyString(), any());
        } finally { heartbeat.shutdownNow(); }
    }
}

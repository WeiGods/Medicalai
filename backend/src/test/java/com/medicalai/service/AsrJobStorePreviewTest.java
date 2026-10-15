package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class AsrJobStorePreviewTest {
    @Test
    void storesRoleNeutralPreviewAndLeavesItAfterRoleStageFailure() {
        RecordingMapper recordings = mock(RecordingMapper.class);
        VisitMapper visits = mock(VisitMapper.class);
        AsrJobStore store = new AsrJobStore(recordings, mock(MedicalRecordMapper.class),
                visits, mock(ClinicalExtractionMapper.class));
        UUID jobId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var job = new RecordingMapper.AsrJob(jobId, visitId, recordingId, null, "RUNNING", 1,
                null, Instant.now(), "LOCAL");
        when(visits.find(visitId, false)).thenReturn(Optional.of(new com.medicalai.domain.Visit(
                visitId,"V1",UUID.randomUUID(),UUID.randomUUID(),"ACTIVE",null,null,null,null,null,null,1,Instant.now())));
        List<AsrSegment> asrText = List.of(new AsrSegment("原文一", 0, 200, 1),
                new AsrSegment("原文二", 210, 500, null));
        when(recordings.lockAsrLease(jobId, token)).thenReturn(true);
        when(recordings.asrJob(jobId, visitId)).thenReturn(Optional.of(job));
        when(recordings.createSession(visitId, recordingId)).thenReturn(sessionId);

        store.savePreview(job, token, recordingId, asrText);
        store.fail(job, token, "角色判断失败");

        ArgumentCaptor<List<RecordingMapper.Turn>> captured = ArgumentCaptor.forClass(List.class);
        verify(recordings).insertPreviewUtterances(eq(visitId), eq(recordingId), eq(sessionId), captured.capture());
        assertEquals(List.of("原文一", "原文二"), captured.getValue().stream().map(RecordingMapper.Turn::text).toList());
        assertEquals(List.of("UNKNOWN", "UNKNOWN"), captured.getValue().stream().map(RecordingMapper.Turn::role).toList());
        assertEquals(List.of("UNKNOWN", "UNKNOWN"), captured.getValue().stream().map(RecordingMapper.Turn::roleSource).toList());
        verify(recordings).failOwnedRecording(jobId, "角色判断失败");
        verify(recordings).markAsrFailed(jobId, "角色判断失败");
        // savePreview 原子替换旧原文时清理一次；角色失败路径不会再删除这份新预览。
        verify(recordings, times(1)).clearPreview(recordingId);
        verify(recordings, never()).insertUtterances(any(), any(), any(), anyList());
    }
}

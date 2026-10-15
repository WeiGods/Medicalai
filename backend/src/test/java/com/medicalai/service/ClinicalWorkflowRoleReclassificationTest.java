package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.domain.Recording;
import com.medicalai.domain.Utterance;
import com.medicalai.domain.Visit;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.PatientMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class ClinicalWorkflowRoleReclassificationTest {
    @ParameterizedTest
    @EnumSource(value=LlmRoute.class,names={"DASHSCOPE","LOCAL"})
    void reclassifiesBySnapshotRouteButPreservesManualTurns(LlmRoute route) {
        UUID visitId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        UUID automaticId = UUID.randomUUID();
        UUID manualId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        VisitMapper visits = mock(VisitMapper.class);
        RecordingMapper recordings = mock(RecordingMapper.class);
        MedicalRecordMapper records = mock(MedicalRecordMapper.class);
        DashScopeRoleClient roles = mock(DashScopeRoleClient.class);
        LocalLlmRoleClient localRoles = mock(LocalLlmRoleClient.class);
        AiServiceClient internal = mock(AiServiceClient.class);
        LlmRouteResolver routeResolver = mock(LlmRouteResolver.class);
        TranscriptRoleReclassificationStore store = mock(TranscriptRoleReclassificationStore.class);
        ClinicalWorkflowService workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class),
                mock(DoctorMapper.class), recordings, records, null, internal,
                mock(AudioStorageService.class), new ObjectMapper(), Clock.systemUTC(), store, routeResolver,
                new LlmRoleRouter(roles, localRoles, route == LlmRoute.LOCAL ? "DASHSCOPE" : "LOCAL"));
        Visit visit = new Visit(visitId, "V-001", UUID.randomUUID(), doctorId, "ACTIVE", null,
                null, null, null, null, null, 1, Instant.now());
        Recording recording = new Recording(recordingId, visitId, "REC-001", "UPLOAD", "key", "test.wav",
                "audio/wav", 1L, 100L, "DONE", null, Instant.now());
        DialogueSnapshot snapshot = new DialogueSnapshot(snapshotId, visitId, recordingId, UUID.randomUUID(), 1,
                "snapshot-hash", "ADOPTED", Instant.now());
        Utterance automatic = utterance(automaticId, recordingId, snapshot.sessionId(), "AUTO", null, "请描述症状", 7);
        Utterance manual = utterance(manualId, recordingId, snapshot.sessionId(), "MANUAL", null, "我头疼三天", 7);
        when(visits.find(visitId, doctorId, false)).thenReturn(Optional.of(visit));
        when(records.findRecordId(visitId)).thenReturn(Optional.empty());
        when(records.status(visitId)).thenReturn("DRAFT");
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(snapshot));
        when(routeResolver.resolve(snapshot, null)).thenReturn(route);
        when(routeResolver.routing(snapshot)).thenReturn(new LlmRouting(
                route, List.of(route), false));
        when(recordings.list(visitId)).thenReturn(List.of(recording));
        when(recordings.listByRecording(recordingId)).thenReturn(List.of(automatic, manual));
        when(recordings.transcript(visitId)).thenReturn(Optional.of(new RecordingMapper.TurnState(snapshotId,
                "医生：请描述症状\n\n患者：我头疼三天", false, Instant.now())));
        var prepared = new TranscriptRoleReclassificationStore.Prepared(snapshot, route, List.of(automatic, manual),
                recordings.transcript(visitId).orElseThrow());
        when(store.prepare(visitId, doctorId, null)).thenReturn(prepared);
        RoleLlmClient selected = route == LlmRoute.LOCAL ? localRoles : roles;
        when(selected.assignRoles(anyList())).thenReturn(Map.of(0,
                new DashScopeRoleClient.RoleAssignment("DOCTOR", 94, "LLM")));

        workflow.reclassifyTranscriptRoles(visitId, doctorId);

        ArgumentCaptor<List<Map<String, Object>>> inputs = ArgumentCaptor.forClass(List.class);
        verify(selected).assignRoles(inputs.capture());
        verifyNoInteractions(route == LlmRoute.LOCAL ? roles : localRoles);
        assertEquals(1, inputs.getValue().size());
        assertEquals(0, inputs.getValue().getFirst().get("index"));
        assertEquals("请描述症状", inputs.getValue().getFirst().get("text"));
        assertEquals(7, inputs.getValue().getFirst().get("speaker_id"));
        verify(store).apply(eq(visitId), eq(doctorId), eq(prepared), argThat(updates -> updates.size() == 1
                && automaticId.equals(updates.getFirst().utteranceId())
                && "DOCTOR".equals(updates.getFirst().role())
                && "LLM".equals(updates.getFirst().source())
                && Integer.valueOf(94).equals(updates.getFirst().confidence())
                && route.name().equals(updates.getFirst().providerRoute())));
        verify(store, never()).apply(eq(visitId), eq(doctorId), eq(prepared), argThat(updates -> updates.stream()
                .anyMatch(update -> manualId.equals(update.utteranceId()))));
    }

    private Utterance utterance(UUID id, UUID recordingId, UUID sessionId, String source, Integer confidence,
                                String text, Integer speakerId) {
        return new Utterance(id, recordingId, sessionId, "u-1", 0, "CANONICAL", text,
                "OTHER", 0, 100, true, Instant.now(), speakerId, source, confidence);
    }
}

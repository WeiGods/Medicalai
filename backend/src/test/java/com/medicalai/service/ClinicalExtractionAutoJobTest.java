package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import com.medicalai.vo.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ClinicalExtractionAutoJobTest {
    UUID visit = UUID.randomUUID(), doctor = UUID.randomUUID(), snapshotId = UUID.randomUUID();
    DialogueSnapshot snapshot = new DialogueSnapshot(snapshotId, visit, UUID.randomUUID(), UUID.randomUUID(),
            1, "hash-1", "ADOPTED", Instant.now());
    ClinicalExtractionMapper facts = mock(ClinicalExtractionMapper.class);
    ClinicalExtractionJobMapper jobs = mock(ClinicalExtractionJobMapper.class);
    RecordingMapper recordings = mock(RecordingMapper.class);
    ClinicalExtractionRouter router = mock(ClinicalExtractionRouter.class);
    ClinicalExtractionService service = new ClinicalExtractionService(facts, recordings,
            mock(MedicalRecordMapper.class), router, new ObjectMapper(), 70);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "jobs", jobs);
        when(router.resolveRoute("LOCAL")).thenReturn(LlmRoute.LOCAL);
        when(router.routing()).thenReturn(new LlmRouting(LlmRoute.LOCAL, List.of(LlmRoute.LOCAL), false));
        when(facts.insertForJob(eq(visit), eq(snapshotId), eq("hash-1"), anyString(), anyString(), anyString(),
                eq("LOCAL"), anyString(), eq(doctor), any(), any())).thenAnswer(call -> new ClinicalExtractionMapper.Version(
                    UUID.randomUUID(), UUID.randomUUID(), 1, snapshotId, "hash-1", call.getArgument(3),
                    call.getArgument(4), call.getArgument(5), "LOCAL", "test", Instant.now(), null));
    }

    @Test void lowConfidenceRoleSavesFailureWithoutCallingModel() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of("""
                [{"role":"PATIENT","text":"头痛三天","startMs":0,"endMs":2000,"role_source":"LLM","role_confidence":45}]
                """));
        var result = service.generateForJob(visit, doctor, snapshot, "LOCAL", UUID.randomUUID(), UUID.randomUUID());
        assertEquals("FAILED", result.status());
        assertTrue(result.qualityIssues().getFirst().contains("说话角色"));
        verify(router, never()).extract(any(), anyString(), anyList());
    }

    @Test void eligibleSnapshotReusesStructuredExtractionAndFencesItsWriteWithJobToken() {
        UUID job = UUID.randomUUID(), token = UUID.randomUUID();
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of("""
                [{"role":"PATIENT","text":"头痛三天","startMs":0,"endMs":2000,"role_source":"LLM","role_confidence":95}]
                """));
        Map<String,Object> fields = new LinkedHashMap<>();
        for (String key : ClinicalExtractionLlmClient.FIELDS) fields.put(key, null);
        fields.put("chief_complaint", Map.of("value", "头痛三天", "confidence", 95,
                "evidence", List.of(Map.of("turn_index", 0, "quote", "头痛三天"))));
        when(router.extract(eq(LlmRoute.LOCAL), eq("hash-1"), anyList())).thenReturn(
                new AiServiceClient.ExtractionResponse("job", "SUCCEEDED", "hash-1", "local-model", Map.of("fields", fields)));
        var result = service.generateForJob(visit, doctor, snapshot, "LOCAL", job, token);
        assertEquals("GENERATED", result.status());
        assertEquals("头痛三天", result.fields().get("chief_complaint").value());
        verify(facts).insertForJob(eq(visit), eq(snapshotId), eq("hash-1"), eq("GENERATED"), anyString(),
                eq("[]"), eq("LOCAL"), eq("local-model"), eq(doctor), eq(job), eq(token));
    }

    @Test void staleWriteIsDiscardedInsteadOfReplacingCurrentResultWithFailure() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of("""
                [{"role":"PATIENT","text":"头痛","startMs":0,"endMs":1,"role_source":"FALLBACK"}]
                """));
        when(facts.insertForJob(any(), any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), any())).thenThrow(BusinessException.conflict("SOURCE_CHANGED", "changed"));
        var error = assertThrows(BusinessException.class, () -> service.generateForJob(visit, doctor, snapshot,
                "LOCAL", UUID.randomUUID(), UUID.randomUUID()));
        assertEquals("SOURCE_CHANGED", error.code());
        verify(router, never()).extract(any(), anyString(), anyList());
    }

    @Test void manualRetryQueuesOnceAndReturnsPersistentRunningStateWithoutCallingModel() {
        when(jobs.current(visit, "hash-1")).thenReturn(Optional.of(new ClinicalExtractionJobMapper.Job(
                UUID.randomUUID(), visit, "hash-1", "LOCAL", "PENDING", null)));
        var result = service.request(visit, doctor, snapshot, "LOCAL");
        assertEquals("QUEUED", result.status());
        verify(jobs).enqueue(visit, snapshotId, "LOCAL");
        verify(router, never()).extract(any(), anyString(), anyList());
    }

    @Test void legacyEmptyGeneratedVersionCanBeRetriedInsteadOfReturningTheSameInvalidResult() {
        when(facts.current(visit)).thenReturn(Optional.of(new ClinicalExtractionMapper.Version(UUID.randomUUID(),
                UUID.randomUUID(), 1, snapshotId, "hash-1", "GENERATED", "{}", "[]", "LOCAL", "model", Instant.now(), null)));
        when(jobs.current(visit, "hash-1")).thenReturn(Optional.of(new ClinicalExtractionJobMapper.Job(
                UUID.randomUUID(), visit, "hash-1", "LOCAL", "PENDING", null)));
        assertEquals("QUEUED", service.request(visit, doctor, snapshot, "LOCAL").status());
        verify(jobs).enqueue(visit, snapshotId, "LOCAL");
    }

    @Test void workerCancelsAnOutdatedSnapshotAndDoesNotRunExtraction() {
        var visits = mock(VisitMapper.class);
        var extraction = mock(ClinicalExtractionService.class);
        var heartbeat = mock(ScheduledExecutorService.class);
        var job = new ClinicalExtractionJobMapper.Job(UUID.randomUUID(), visit, "old-hash", "LOCAL", "RUNNING", null);
        when(jobs.claim(eq("LOCAL"), any())).thenReturn(Optional.of(job));
        when(recordings.latestSnapshot(visit)).thenReturn(Optional.of(snapshot));
        var renewal = mock(java.util.concurrent.ScheduledFuture.class);
        doReturn(renewal).when(heartbeat).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        new ClinicalExtractionJobWorker(jobs, recordings, visits, extraction, heartbeat).processLocal();
        verify(jobs).finish(eq(job.id()), any(), eq("CANCELLED"), isNull());
        verifyNoInteractions(extraction);
        verify(renewal).cancel(false);
    }
}

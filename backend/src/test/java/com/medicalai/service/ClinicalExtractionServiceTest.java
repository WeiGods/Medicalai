package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.ClinicalExtractionMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.vo.ClinicalExtractionVO;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class ClinicalExtractionServiceTest {
    private final UUID visitId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final String snapshotHash = "traceable-snapshot-hash";
    private final ClinicalExtractionMapper extractions = org.mockito.Mockito.mock(ClinicalExtractionMapper.class);
    private final RecordingMapper recordings = org.mockito.Mockito.mock(RecordingMapper.class);
    private final MedicalRecordMapper records = org.mockito.Mockito.mock(MedicalRecordMapper.class);
    private final ClinicalExtractionRouter clients = org.mockito.Mockito.mock(ClinicalExtractionRouter.class);
    private final LlmRouteResolver routes = org.mockito.Mockito.mock(LlmRouteResolver.class);
    private final ClinicalExtractionService service = new ClinicalExtractionService(extractions, recordings, records,
            clients, routes, new ObjectMapper(), 70);
    private final DialogueSnapshot snapshot = new DialogueSnapshot(snapshotId, visitId, UUID.randomUUID(),
            UUID.randomUUID(), 1, snapshotHash, "ADOPTED", Instant.now());

    @BeforeEach
    void saveReturnedVersion() {
        when(routes.analysisRouting(snapshot)).thenReturn(new LlmRouting(LlmRoute.DASHSCOPE, List.of(LlmRoute.DASHSCOPE), false));
        when(routes.resolveAnalysis(snapshot, null)).thenReturn(LlmRoute.DASHSCOPE);
        Answer<ClinicalExtractionMapper.Version> answer = invocation -> new ClinicalExtractionMapper.Version(
                UUID.randomUUID(), UUID.randomUUID(), 1, snapshotId, snapshotHash,
                invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                invocation.getArgument(6), invocation.getArgument(7), Instant.now(), null);
        when(extractions.insert(eq(visitId), eq(snapshotId), eq(snapshotHash), anyString(), anyString(), anyString(),
                anyString(), anyString(), eq(doctorId))).thenAnswer(answer);
    }

    @Test
    void publicSnapshotCallsOnlyDashScopeWithoutInternalService() {
        var dashscope = org.mockito.Mockito.mock(DashScopeClinicalExtractionClient.class);
        var internal = org.mockito.Mockito.mock(AiServiceClient.class);
        var routed = new ClinicalExtractionService(extractions,recordings,records,
                new ClinicalExtractionRouter(dashscope,internal),new LlmRouteResolver(recordings),new ObjectMapper(),70);
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("DASHSCOPE_ROLE_V2"));
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(dashscope.extract(eq(snapshotHash),any())).thenReturn(new AiServiceClient.ExtractionResponse(
                "public-job","SUCCEEDED",snapshotHash,"public-model",response("我头痛三天",1)));

        var result = routed.generate(visitId,doctorId,snapshot);

        assertEquals("GENERATED",result.status());
        assertEquals("DASHSCOPE",result.providerRoute());
        assertEquals("DASHSCOPE",result.sourceRoute());
        org.mockito.Mockito.verifyNoInteractions(internal);
    }

    @Test
    void localSnapshotUsesInternalGatewayAndReportsActualRoute() {
        var dashscope = org.mockito.Mockito.mock(DashScopeClinicalExtractionClient.class);
        var internal = org.mockito.Mockito.mock(AiServiceClient.class);
        var routed = new ClinicalExtractionService(extractions,recordings,records,
                new ClinicalExtractionRouter(dashscope,internal),new LlmRouteResolver(recordings),new ObjectMapper(),70);
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("LOCAL"));
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(internal.extract(eq(snapshotHash),any())).thenReturn(new AiServiceClient.ExtractionResponse(
                "local-job","SUCCEEDED",snapshotHash,"local-model",response("我头痛三天",1)));

        var result = routed.generate(visitId,doctorId,snapshot);

        assertEquals("GENERATED",result.status());
        assertEquals("LOCAL",result.providerRoute());
        assertEquals("LOCAL",result.sourceRoute());
        org.mockito.Mockito.verifyNoInteractions(dashscope);
        verify(internal).extract(eq(snapshotHash),any());
    }

    @Test
    void localGatewayFailureIsSavedAsFailedWithoutPublicFallback() {
        var dashscope = org.mockito.Mockito.mock(DashScopeClinicalExtractionClient.class);
        var internal = org.mockito.Mockito.mock(AiServiceClient.class);
        var routed = new ClinicalExtractionService(extractions,recordings,records,
                new ClinicalExtractionRouter(dashscope,internal),new LlmRouteResolver(recordings),new ObjectMapper(),70);
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("LOCAL"));
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(internal.extract(eq(snapshotHash),any())).thenThrow(BusinessException.conflict("AI_SERVICE_OFFLINE","本地服务不可用"));

        var result = routed.generate(visitId,doctorId,snapshot);

        assertEquals("FAILED",result.status());
        assertEquals("LOCAL",result.providerRoute());
        assertEquals("LOCAL",result.sourceRoute());
        org.mockito.Mockito.verifyNoInteractions(dashscope);
    }

    @Test
    void explicitPublicRouteCannotBypassLocalSnapshot() {
        when(routes.analysisRouting(snapshot)).thenReturn(new LlmRouting(LlmRoute.LOCAL,List.of(LlmRoute.LOCAL),false));
        assertEquals("LLM_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> service.generate(visitId,doctorId,snapshot,LlmRoute.DASHSCOPE)).code());
        org.mockito.Mockito.verifyNoInteractions(clients);
        verify(extractions,never()).insert(any(),any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }

    @Test
    void acceptsPatientFactOnlyWhenQuoteExistsInCurrentPatientTurn() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(clients.extract(eq(LlmRoute.DASHSCOPE), eq(snapshotHash), any())).thenReturn(new AiServiceClient.ExtractionResponse("job", "SUCCEEDED",
                snapshotHash, "test-model", response("我头痛三天", 1)));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("GENERATED", result.status());
        assertEquals("我头痛三天", result.fields().get("chief_complaint").value());
        assertEquals("PATIENT", result.fields().get("chief_complaint").evidence().getFirst().role());
    }

    @Test void expiredSuccessfulResultIsNotConvertedIntoFailedCurrentVersion() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(clients.extract(eq(LlmRoute.DASHSCOPE),eq(snapshotHash),any())).thenReturn(new AiServiceClient.ExtractionResponse(
                "job","SUCCEEDED",snapshotHash,"model",response("我头痛三天",1)));
        when(extractions.insert(eq(visitId),eq(snapshotId),eq(snapshotHash),eq("GENERATED"),anyString(),anyString(),anyString(),anyString(),eq(doctorId)))
                .thenThrow(BusinessException.conflict("SOURCE_CHANGED","new snapshot"));
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,()->service.generate(visitId,doctorId,snapshot)).code());
        verify(extractions,never()).insert(any(),any(),anyString(),eq("FAILED"),anyString(),anyString(),anyString(),anyString(),any());
    }

    @Test void expiredModelFailureCannotBeSavedAgainstReplacementSnapshot() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(clients.extract(eq(LlmRoute.DASHSCOPE),eq(snapshotHash),any())).thenThrow(BusinessException.conflict("AI_SERVICE_OFFLINE","offline"));
        when(extractions.insert(eq(visitId),eq(snapshotId),eq(snapshotHash),eq("FAILED"),anyString(),anyString(),anyString(),anyString(),eq(doctorId)))
                .thenThrow(BusinessException.conflict("SOURCE_CHANGED","new snapshot"));
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,()->service.generate(visitId,doctorId,snapshot)).code());
    }

    @Test
    void extractsFromEvidenceTurnInsteadOfPatientTurnPosition() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of("""
                [
                  {"role":"PATIENT","text":"嗯","startMs":0,"endMs":200,"role_source":"LLM","role_confidence":95},
                  {"role":"DOCTOR","text":"主要哪里不舒服？","startMs":300,"endMs":1000,"role_source":"LLM","role_confidence":95},
                  {"role":"PATIENT","text":"我头痛三天，伴恶心","startMs":1100,"endMs":2000,"role_source":"LLM","role_confidence":95}
                ]
                """));
        when(clients.extract(eq(LlmRoute.DASHSCOPE), eq(snapshotHash), any())).thenReturn(new AiServiceClient.ExtractionResponse("job", "SUCCEEDED",
                snapshotHash, "test-model", response("我头痛三天", 2)));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("GENERATED", result.status());
        assertEquals(2, result.fields().get("chief_complaint").evidence().getFirst().turnIndex());
    }

    @Test
    void rejectsQuoteThatIsNotAnExactCurrentSnapshotSubstring() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(clients.extract(eq(LlmRoute.DASHSCOPE), eq(snapshotHash), any())).thenReturn(new AiServiceClient.ExtractionResponse("job", "SUCCEEDED",
                snapshotHash, "test-model", response("患者头痛三天", 1)));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("FAILED", result.status());
        verify(extractions).insert(eq(visitId), eq(snapshotId), eq(snapshotHash), eq("FAILED"), anyString(),
                anyString(), anyString(), anyString(), eq(doctorId));
    }

    @Test
    void rejectsDoctorTurnAsPatientFactEvidence() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(clients.extract(eq(LlmRoute.DASHSCOPE), eq(snapshotHash), any())).thenReturn(new AiServiceClient.ExtractionResponse("job", "SUCCEEDED",
                snapshotHash, "test-model", response("请描述不适", 0)));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("FAILED", result.status());
    }

    @Test
    void discardsOptionalFieldWithInvalidEvidenceButKeepsValidatedFacts() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        Map<String, Object> modelResponse = response("我头痛三天", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) modelResponse.get("fields");
        fields.put("symptom_characteristics", Map.of("value", "持续头痛", "confidence", 92,
                "evidence", List.of(Map.of("turn_index", 1, "quote", "持续头痛"))));
        when(clients.extract(eq(LlmRoute.DASHSCOPE), eq(snapshotHash), any())).thenReturn(
                new AiServiceClient.ExtractionResponse("job", "SUCCEEDED", snapshotHash, "test-model", modelResponse));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("GENERATED", result.status());
        assertEquals("我头痛三天", result.fields().get("chief_complaint").value());
        assertNull(result.fields().get("symptom_characteristics").value());
        assertTrue(result.fields().get("symptom_characteristics").evidence().isEmpty());
    }

    @Test
    void rejectsNonStandardFieldTypesInsteadOfCoercingThem() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        Map<String, Object> invalid = response("我头痛三天", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) invalid.get("fields");
        fields.put("chief_complaint", Map.of("value", "我头痛三天", "confidence", "96",
                "evidence", List.of(Map.of("turn_index", 1, "quote", "我头痛三天"))));
        when(clients.extract(eq(LlmRoute.DASHSCOPE), eq(snapshotHash), any())).thenReturn(new AiServiceClient.ExtractionResponse("job", "SUCCEEDED",
                snapshotHash, "test-model", invalid));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("FAILED", result.status());
    }

    @Test
    void savesAllNullModelOutputAsFailedInsteadOfConfirmableDraft() {
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of(validTurns()));
        when(clients.extract(eq(LlmRoute.DASHSCOPE), eq(snapshotHash), any())).thenReturn(
                new AiServiceClient.ExtractionResponse("job", "SUCCEEDED", snapshotHash, "test-model",
                        Map.of("fields", allNullFields())));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("FAILED", result.status());
        assertTrue(result.qualityIssues().stream().anyMatch(issue -> issue.contains("患者主诉")));
        verify(extractions).insert(eq(visitId), eq(snapshotId), eq(snapshotHash), eq("FAILED"), anyString(),
                anyString(), anyString(), anyString(), eq(doctorId));
    }

    @Test
    void exposesLegacyGeneratedVersionWithoutChiefComplaintAsFailed() {
        ClinicalExtractionMapper.Version legacy = new ClinicalExtractionMapper.Version(UUID.randomUUID(), UUID.randomUUID(),
                3, snapshotId, snapshotHash, "GENERATED", "{}", "[]", "DASHSCOPE", "qwen-plus", Instant.now(), null);
        when(extractions.current(visitId)).thenReturn(Optional.of(legacy));

        ClinicalExtractionVO result = service.current(visitId, snapshot);

        assertEquals("FAILED", result.status());
        assertTrue(result.qualityIssues().stream().anyMatch(issue -> issue.contains("不能整体确认")));
    }

    @Test
    void blocksLongAndLegacyUnreviewedTurnsBeforeCallingModel() {
        String longText = "症".repeat(601);
        when(recordings.snapshotTurnsJson(snapshotId)).thenReturn(Optional.of("""
                [{"role":"PATIENT","text":"%s","startMs":0,"endMs":46000}]
                """.formatted(longText)));

        ClinicalExtractionVO result = service.generate(visitId, doctorId, snapshot);

        assertEquals("FAILED", result.status());
        assertFalse(result.qualityIssues().isEmpty());
        assertFalse(result.qualityIssues().stream().noneMatch(issue -> issue.contains("过长")));
        assertFalse(result.qualityIssues().stream().noneMatch(issue -> issue.contains("角色尚未完成可信核对")));
        verify(clients, never()).extract(any(), anyString(), any());
    }

    @Test
    void requiresConfirmedExtractionBeforeMedicalRecordGeneration() {
        when(extractions.confirmedForSnapshot(visitId, snapshotId, snapshotHash)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requireConfirmedFields(visitId, snapshot));

        assertEquals("CLINICAL_EXTRACTION_REQUIRED", error.code());
    }

    @Test
    void rejectsLegacyChiefComplaintWithoutEvidenceBeforeConfirmation() {
        ClinicalExtractionMapper.Version legacy = new ClinicalExtractionMapper.Version(UUID.randomUUID(), UUID.randomUUID(),
                3, snapshotId, snapshotHash, "GENERATED", """
                {"chief_complaint":{"value":"我头痛三天","confidence":96,"evidence":[]}}
                """, "[]", "DASHSCOPE", "qwen-plus", Instant.now(), null);
        when(extractions.current(visitId)).thenReturn(Optional.of(legacy));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.confirm(visitId, doctorId, snapshot));

        assertEquals("CLINICAL_EXTRACTION_CHIEF_REQUIRED", error.code());
    }

    private String validTurns() {
        return """
                [
                  {"role":"DOCTOR","text":"请描述不适","startMs":0,"endMs":900,"role_source":"LLM","role_confidence":96},
                  {"role":"PATIENT","text":"我头痛三天，伴恶心","startMs":1000,"endMs":1800,"role_source":"LLM","role_confidence":96}
                ]
                """;
    }

    private Map<String, Object> response(String quote, int turnIndex) {
        Map<String, Object> fields = allNullFields();
        fields.put("chief_complaint", Map.of("value", "我头痛三天", "confidence", 96,
                "evidence", List.of(Map.of("turn_index", turnIndex, "quote", quote))));
        return Map.of("fields", fields);
    }

    private Map<String, Object> allNullFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String key : List.of("chief_complaint", "onset_course", "symptom_characteristics", "associated_symptoms",
                "past_medical_history", "medication_history", "allergy_history", "family_history", "social_history",
                "doctor_diagnosis", "doctor_medication", "doctor_followup")) {
            fields.put(key, null);
        }
        return fields;
    }
}

package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.RecordingMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

/** 快照来源而非前端参数决定公网/内网处理边界。 */
class LlmRouteResolverTest {
    private final RecordingMapper recordings = mock(RecordingMapper.class);
    private final LlmRouteResolver resolver = new LlmRouteResolver(recordings);

    @Test
    void mapsCurrentAndLegacyPublicAsrRoutesToDashScope() {
        DialogueSnapshot snapshot = snapshot();
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("DASHSCOPE", "DASHSCOPE_ROLE_V2"));

        LlmRouting routing = resolver.routing(snapshot);

        assertEquals(LlmRoute.DASHSCOPE, routing.sourceRoute());
        assertFalse(routing.selectionRequired());
        assertEquals(LlmRoute.DASHSCOPE, resolver.resolve(snapshot, null));
    }

    @Test
    void resolvesLocalOnlySnapshotToInternalRoute() {
        DialogueSnapshot snapshot = snapshot();
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("LOCAL"));

        assertEquals(LlmRoute.LOCAL, resolver.resolve(snapshot, null));
    }

    @Test
    void rejectsMixedSnapshotEvenWithExplicitRoute() {
        DialogueSnapshot snapshot = snapshot();
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("DASHSCOPE", "LOCAL"));

        BusinessException missing = assertThrows(BusinessException.class, () -> resolver.resolve(snapshot, null));
        assertEquals("LLM_ROUTE_MIXED", missing.code());
        for (String provider : List.of("LOCAL", "DASHSCOPE")) {
            assertEquals("LLM_ROUTE_MIXED", assertThrows(BusinessException.class,
                    () -> resolver.resolveAnalysis(snapshot, provider)).code());
        }
        assertFalse(resolver.analysisRouting(snapshot).selectionRequired());
        assertEquals(List.of(), resolver.analysisRouting(snapshot).availableRoutes());
    }

    @Test
    void rejectsUnknownAndSingleRouteMismatch() {
        DialogueSnapshot snapshot = snapshot();
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("UNKNOWN"));
        assertEquals("LLM_ROUTE_UNKNOWN", assertThrows(BusinessException.class,
                () -> resolver.resolve(snapshot, null)).code());

        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("LOCAL"));
        assertEquals("LLM_ROUTE_MISMATCH", assertThrows(BusinessException.class,
                () -> resolver.resolve(snapshot, "DASHSCOPE")).code());
    }
    @Test void historicalMissingRoutesRemainPublicOnlyWithoutLocalEvidence() {
        DialogueSnapshot snapshot = snapshot();
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("", "DASHSCOPE_ROLE_V2"));
        assertEquals(LlmRoute.DASHSCOPE, resolver.resolveAnalysis(snapshot, null));
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of(""));
        assertEquals(LlmRoute.DASHSCOPE, resolver.resolve(snapshot, null));
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("", "LOCAL"));
        assertEquals("LLM_ROUTE_UNKNOWN", assertThrows(BusinessException.class,
                () -> resolver.resolveAnalysis(snapshot, null)).code());
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of());
        assertEquals("LLM_ROUTE_UNKNOWN", assertThrows(BusinessException.class,
                () -> resolver.resolve(snapshot, null)).code());
    }
    @Test void extractionFollowsLocalSnapshotAndRejectsPublicOverride() {
        DialogueSnapshot snapshot = snapshot();
        when(recordings.snapshotAsrRoutes(snapshot.id())).thenReturn(List.of("LOCAL"));
        assertEquals(LlmRoute.LOCAL, resolver.resolveAnalysis(snapshot, null));
        assertEquals(LlmRoute.LOCAL, resolver.resolveAnalysis(snapshot, "LOCAL"));
        assertEquals("LLM_ROUTE_MISMATCH", assertThrows(BusinessException.class,
                () -> resolver.resolveAnalysis(snapshot, "DASHSCOPE")).code());
    }

    private DialogueSnapshot snapshot() {
        return new DialogueSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, "snapshot-hash", "ADOPTED", Instant.now());
    }
}

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

class RoleReclassificationSourceGuardTest {
    UUID visitId = UUID.randomUUID(), doctorId = UUID.randomUUID(), recordingId = UUID.randomUUID(), snapshotId = UUID.randomUUID(), sessionId = UUID.randomUUID();
    VisitMapper visits = mock(VisitMapper.class);
    RecordingMapper recordings = mock(RecordingMapper.class);
    MedicalRecordMapper records = mock(MedicalRecordMapper.class);
    ClinicalExtractionMapper extractions = mock(ClinicalExtractionMapper.class);
    TranscriptRoleReclassificationStore store = new TranscriptRoleReclassificationStore(visits,recordings,records,extractions);
    Utterance automatic = utterance("AUTO", "待分类文本");
    Utterance manual = utterance("MANUAL", "已人工核对文本");
    DialogueSnapshot snapshot;

    Utterance utterance(String source, String text) {
        return new Utterance(UUID.randomUUID(),recordingId,sessionId,"u",0,"CANONICAL",text,"OTHER",0,100,true,Instant.now(),1,source,null,"LOCAL");
    }
    RecordingMapper.Turn turn(Utterance u) {
        return new RecordingMapper.Turn(u.role(),u.text(),u.startMs(),u.endMs(),u.speakerId(),u.roleSource(),u.roleConfidence(),u.roleProviderRoute());
    }
    @BeforeEach void setup() {
        when(visits.find(visitId,doctorId,true)).thenReturn(Optional.of(new Visit(visitId,"V",UUID.randomUUID(),doctorId,"ACTIVE",null,null,null,null,null,null,1,Instant.now())));
        when(recordings.list(visitId)).thenReturn(List.of(new Recording(recordingId,visitId,"R","UPLOAD","key","a.wav","audio/wav",1L,100L,"DONE",null,Instant.now())));
        when(recordings.listByRecording(recordingId)).thenReturn(List.of(automatic,manual));
        snapshot = new DialogueSnapshot(snapshotId,visitId,recordingId,sessionId,1,
                DialogueSnapshotHasher.hash(visitId,List.of(turn(automatic),turn(manual))),"ADOPTED",Instant.now());
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(snapshot));
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("LOCAL"));
        when(recordings.transcript(visitId)).thenReturn(Optional.of(new RecordingMapper.TurnState(snapshotId,"说话人 1：待分类文本\n\n说话人 1：已人工核对文本",false,Instant.now())));
    }
    List<RecordingMapper.RoleUpdate> updates() {
        return List.of(new RecordingMapper.RoleUpdate(automatic.id(),"PATIENT","LLM",90,"LOCAL"));
    }
    void assertNoWrite() {
        verify(recordings,never()).updateRoleAssignments(any(),anyList());
        verify(recordings,never()).createEditedSnapshot(any(),any(),anyList(),anyString(),any());
        verifyNoInteractions(extractions);
    }

    @Test void freezesCanonicalInputAndPreservesManualTurns() {
        var prepared = store.prepare(visitId,doctorId,null);
        assertEquals(LlmRoute.LOCAL,prepared.route());
        assertEquals(List.of(automatic),prepared.candidates());
        when(recordings.updateRoleAssignments(visitId,updates())).thenReturn(1);
        store.apply(visitId,doctorId,prepared,updates());
        verify(recordings).updateRoleAssignments(visitId,updates());
        verify(extractions).markCurrentStale(visitId);
    }

    @Test void replacingOrRemovingSnapshotDuringModelCallRejectsWholeResult() {
        var prepared = store.prepare(visitId,doctorId,null);
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.of(new DialogueSnapshot(UUID.randomUUID(),visitId,recordingId,sessionId,2,"new","ADOPTED",Instant.now())));
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,()->store.apply(visitId,doctorId,prepared,updates())).code());
        when(recordings.latestSnapshot(visitId)).thenReturn(Optional.empty());
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,()->store.apply(visitId,doctorId,prepared,updates())).code());
        assertNoWrite();
    }

    @Test void manualRoleOrTextMutationCannotSlipIntoOldSnapshot() {
        var prepared = store.prepare(visitId,doctorId,null);
        when(recordings.listByRecording(recordingId)).thenReturn(List.of(utterance("MANUAL","已改变的文本"),manual));
        assertThrows(BusinessException.class,()->store.apply(visitId,doctorId,prepared,updates()));
        assertNoWrite();
    }

    @Test void canonicalIdsChangedEvenWithIdenticalSnapshotHashRejectsResult() {
        var prepared = store.prepare(visitId,doctorId,null);
        when(recordings.listByRecording(recordingId)).thenReturn(List.of(utterance("AUTO",automatic.text()),manual));
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,()->store.apply(visitId,doctorId,prepared,updates())).code());
        assertNoWrite();
    }

    @Test void confirmedRecordOrInFlightAsrBlocksWriteback() {
        var prepared = store.prepare(visitId,doctorId,null);
        when(records.findRecordId(visitId)).thenReturn(Optional.of(UUID.randomUUID()));
        when(records.status(visitId)).thenReturn("CONFIRMED");
        assertEquals("RECORD_CONFIRMED",assertThrows(BusinessException.class,()->store.apply(visitId,doctorId,prepared,updates())).code());
        when(records.status(visitId)).thenReturn("DRAFT");
        when(recordings.hasActiveAsrJob(visitId)).thenReturn(true);
        assertEquals("RECORDING_PROCESSING",assertThrows(BusinessException.class,()->store.apply(visitId,doctorId,prepared,updates())).code());
        assertNoWrite();
    }

    @Test void mixedSourcesAndWrongProviderCannotPrepareNetworkInput() {
        assertEquals("LLM_ROUTE_MISMATCH",assertThrows(BusinessException.class,()->store.prepare(visitId,doctorId,"DASHSCOPE")).code());
        when(recordings.snapshotAsrRoutes(snapshotId)).thenReturn(List.of("LOCAL","DASHSCOPE"));
        assertEquals("LLM_ROUTE_MIXED",assertThrows(BusinessException.class,()->store.prepare(visitId,doctorId,null)).code());
        assertNoWrite();
    }
}

package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AsrPreviewWorkflowTest {
    @Test
    void readsPreviewSegmentsGroupedByRecordingWithoutReadingCanonicalTranscript() {
        VisitMapper visits = mock(VisitMapper.class);
        RecordingMapper recordings = mock(RecordingMapper.class);
        UUID visitId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID recordingId = UUID.randomUUID();
        var visit = new Visit(visitId, "V-1", UUID.randomUUID(), doctorId, "ACTIVE", null,
                null, null, null, null, null, 1, Instant.now());
        var recording = new Recording(recordingId, visitId, "REC-001", "UPLOAD", "key", "visit.wav",
                "audio/wav", 12L, 3000L, "PROCESSING", null, Instant.now());
        var preview = new Utterance(UUID.randomUUID(), recordingId, UUID.randomUUID(), "u-1", 0, "PREVIEW",
                "我头疼", "UNKNOWN", 100L, 800L, false, Instant.now(), 2, "UNKNOWN", null, "UNKNOWN");
        when(visits.find(visitId, doctorId, false)).thenReturn(Optional.of(visit));
        when(recordings.list(visitId)).thenReturn(List.of(recording));
        when(recordings.listPreviewByRecording(recordingId)).thenReturn(List.of(preview));
        var workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class), mock(DoctorMapper.class),
                recordings, mock(MedicalRecordMapper.class), mock(AiServiceClient.class),
                mock(AudioStorageService.class), new ObjectMapper(), Clock.systemUTC(),
                mock(DashScopeRoleClient.class), mock(TranscriptRoleReclassificationStore.class));

        var previews = workflow.asrPreviews(visitId, doctorId, com.medicalai.domain.DoctorRole.DOCTOR);

        assertEquals(1, previews.size());
        assertEquals(recordingId, previews.getFirst().recordingId());
        assertEquals("REC-001", previews.getFirst().recordingNo());
        assertEquals("PROCESSING", previews.getFirst().status());
        assertEquals("我头疼", previews.getFirst().segments().getFirst().text());
        assertEquals(2, previews.getFirst().segments().getFirst().speakerId());
        verify(recordings).listPreviewByRecording(recordingId);
        verify(recordings, never()).listByRecording(recordingId);
    }
}

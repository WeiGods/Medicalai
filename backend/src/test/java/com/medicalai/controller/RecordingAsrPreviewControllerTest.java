package com.medicalai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.service.AudioStorageService;
import com.medicalai.service.ClinicalWorkflowService;
import com.medicalai.vo.AsrPreviewRecordingVO;
import com.medicalai.vo.AsrJobVO;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingAsrPreviewControllerTest {
    @Test
    void forwardsAuthenticatedVisitAndReadOnlyRoleToPreviewQuery() {
        ClinicalWorkflowService workflow = mock(ClinicalWorkflowService.class);
        var controller = new RecordingController(workflow, mock(com.medicalai.mapper.RecordingMapper.class),
                mock(AudioStorageService.class));
        var doctor = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(UUID.randomUUID(), "LOCAL", "doctor",
                "医生", null, "内科", "DOCTOR", "ACTIVE"));
        UUID visitId = UUID.randomUUID();
        var response = List.of(new AsrPreviewRecordingVO(UUID.randomUUID(), "REC-001", "visit.wav",
                "PROCESSING", null, List.of()));
        when(workflow.asrPreviews(visitId, doctor.doctor().id(), DoctorRole.DOCTOR)).thenReturn(response);

        var actual = controller.asrPreviews(visitId, doctor);

        assertEquals(response, actual);
        verify(workflow).asrPreviews(visitId, doctor.doctor().id(), DoctorRole.DOCTOR);
    }

    @Test
    void forwardsAuthenticatedVisitAndReadOnlyRoleToActiveTaskQuery() {
        ClinicalWorkflowService workflow = mock(ClinicalWorkflowService.class);
        var controller = new RecordingController(workflow, mock(com.medicalai.mapper.RecordingMapper.class),
                mock(AudioStorageService.class));
        var doctor = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(UUID.randomUUID(), "LOCAL", "doctor",
                "医生", null, "内科", "DOCTOR", "ACTIVE"));
        UUID visitId = UUID.randomUUID();
        var response = new AsrJobVO(UUID.randomUUID(), "PENDING", 1, 0, null, null, "LOCAL");
        when(workflow.activeAsrJob(visitId, doctor.doctor().id(), DoctorRole.DOCTOR)).thenReturn(response);

        var actual = controller.activeTranscriptionStatus(visitId, doctor);

        assertEquals(response, actual);
        verify(workflow).activeAsrJob(visitId, doctor.doctor().id(), DoctorRole.DOCTOR);
    }
}

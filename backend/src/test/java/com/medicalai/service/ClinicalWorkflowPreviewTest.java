package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.DoctorRole;
import com.medicalai.domain.ExportTemplate;
import com.medicalai.domain.ExportTemplateRevision;
import com.medicalai.domain.ExportTemplateWithRevision;
import com.medicalai.domain.MedicalRecordVersion;
import com.medicalai.domain.Visit;
import com.medicalai.dto.ExportMedicalRecordRequest;
import com.medicalai.exception.BusinessException;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ClinicalWorkflowPreviewTest {
    @Test
    void departmentHeadPreviewsAnotherDoctorsSignedRecordWithoutCreatingAnExport() throws Exception {
        Fixture fixture = new Fixture();
        UUID headId = UUID.randomUUID();
        when(fixture.visits.find(fixture.visitId, false)).thenReturn(Optional.of(fixture.visit));

        byte[] result = fixture.workflow.previewExport(fixture.visitId, headId, DoctorRole.DEPARTMENT_HEAD, fixture.request);

        assertArrayEquals(fixture.pdf, result);
        verify(fixture.visits, never()).find(fixture.visitId, headId, false);
        ArgumentCaptor<MedicalRecordMapper.ExportPayload> payload = ArgumentCaptor.forClass(MedicalRecordMapper.ExportPayload.class);
        verify(fixture.files).render(payload.capture());
        assertEquals(fixture.visitId, payload.getValue().visitId());
        assertEquals(fixture.version.id(), payload.getValue().versionId());
        assertEquals(fixture.version.contentJson(), payload.getValue().contentJson());
        assertEquals(fixture.definition, payload.getValue().templateDefinitionJson());
        verify(fixture.files, never()).generate(any());
        verify(fixture.records, never()).createExportJob(any(), any(), any());
        verify(fixture.records, never()).saveDraft(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void doctorCanStillPreviewOwnSignedRecord() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.visits.find(fixture.visitId, fixture.doctorId, false)).thenReturn(Optional.of(fixture.visit));

        assertArrayEquals(fixture.pdf, fixture.workflow.previewExport(fixture.visitId, fixture.doctorId, fixture.request));

        verify(fixture.visits, never()).find(fixture.visitId, false);
    }

    @Test
    void doctorCannotPreviewAnotherDoctorsRecord() throws Exception {
        Fixture fixture = new Fixture();
        UUID otherDoctor = UUID.randomUUID();
        when(fixture.visits.find(fixture.visitId, otherDoctor, false)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.workflow.previewExport(fixture.visitId, otherDoctor, DoctorRole.DOCTOR, fixture.request));

        assertEquals("NOT_FOUND", error.code());
        verify(fixture.visits, never()).find(fixture.visitId, false);
        verify(fixture.files, never()).render(any());
    }

    @Test
    void departmentHeadCannotPreviewAnUnsignedCurrentVersion() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.visits.find(fixture.visitId, false)).thenReturn(Optional.of(fixture.visit));
        when(fixture.records.currentConfirmedVersion(fixture.visitId)).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.workflow.previewExport(fixture.visitId, UUID.randomUUID(), DoctorRole.DEPARTMENT_HEAD, fixture.request));

        assertEquals("RECORD_NOT_CONFIRMED", error.code());
        verify(fixture.files, never()).render(any());
    }

    @Test
    void administratorsDoNotGainPreviewPermission() throws Exception {
        Fixture fixture = new Fixture();

        BusinessException error = assertThrows(BusinessException.class,
                () -> fixture.workflow.previewExport(fixture.visitId, UUID.randomUUID(), DoctorRole.ADMIN, fixture.request));

        assertEquals("RECORD_PREVIEW_FORBIDDEN", error.code());
        verify(fixture.files, never()).render(any());
    }

    private static final class Fixture {
        private final UUID visitId = UUID.randomUUID();
        private final UUID doctorId = UUID.randomUUID();
        private final VisitMapper visits = mock(VisitMapper.class);
        private final MedicalRecordMapper records = mock(MedicalRecordMapper.class);
        private final ExportTemplateService templates = mock(ExportTemplateService.class);
        private final ExportFileService files = mock(ExportFileService.class);
        private final Visit visit = new Visit(visitId, "VIS-001", UUID.randomUUID(), doctorId, "COMPLETED", "内科",
                "患者", "男", 30, "接诊医生", null, 1, Instant.now());
        private final MedicalRecordVersion version = new MedicalRecordVersion(UUID.randomUUID(), UUID.randomUUID(), 2,
                UUID.randomUUID(), "snapshot", "{\"doctor\":\"接诊医生\"}", null, "SUCCEEDED", "接诊医生", Instant.now());
        private final String definition = "{\"documentTitle\":\"模板病历\"}";
        private final ExportMedicalRecordRequest request = new ExportMedicalRecordRequest("PDF", UUID.randomUUID());
        private final byte[] pdf = new byte[] {'%', 'P', 'D', 'F'};
        private final ClinicalWorkflowService workflow;

        private Fixture() throws Exception {
            when(records.currentVersion(visitId)).thenReturn(Optional.of(version));
            UUID confirmationId = UUID.randomUUID();
            when(records.currentConfirmedVersion(visitId)).thenReturn(Optional.of(
                    new MedicalRecordMapper.ConfirmedVersion(version.recordId(), version.id(), 2, confirmationId)));
            when(records.confirmations(visitId)).thenReturn(List.of(
                    new MedicalRecordMapper.ConfirmationRow(confirmationId, 2, "接诊医生", Instant.now())));
            UUID templateId = UUID.randomUUID();
            when(templates.activeRevision(request.templateRevisionId())).thenReturn(new ExportTemplateWithRevision(
                    new ExportTemplate(templateId, "standard", "标准病历", "", "ACTIVE", true,
                            request.templateRevisionId(), Instant.now(), doctorId),
                    new ExportTemplateRevision(request.templateRevisionId(), templateId, 1, definition, doctorId, Instant.now())));
            when(files.render(any())).thenReturn(pdf);
            workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class), mock(DoctorMapper.class),
                    mock(RecordingMapper.class), records, mock(AiServiceClient.class), mock(AudioStorageService.class),
                    new ObjectMapper(), Clock.systemUTC(), mock(DashScopeRoleClient.class),
                    mock(TranscriptRoleReclassificationStore.class));
            ReflectionTestUtils.setField(workflow, "exportTemplates", templates);
            ReflectionTestUtils.setField(workflow, "exportFiles", files);
        }
    }
}

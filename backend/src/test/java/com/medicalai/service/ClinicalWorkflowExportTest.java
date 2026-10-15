package com.medicalai.service;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.RecordExport;
import com.medicalai.domain.Visit;
import com.medicalai.dto.ExportMedicalRecordRequest;
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

class ClinicalWorkflowExportTest {
    @Test
    void reusesOnlyTheCorrectedTemplateExport() {
        Fixture fixture = new Fixture();
        RecordExport reusable = fixture.export("SUCCEEDED");
        when(fixture.records.reusableExport(fixture.recordId, fixture.versionId, fixture.confirmationId, "PDF"))
                .thenReturn(Optional.of(reusable));
        when(fixture.records.exportsByVisit(fixture.visitId)).thenReturn(List.of(reusable));

        fixture.workflow.recordExport(fixture.visitId, fixture.doctorId, new ExportMedicalRecordRequest("PDF"));

        verify(fixture.records, never()).insertExport(fixture.recordId, 3, fixture.confirmationId,
                fixture.versionId, "PDF", fixture.doctorId);
    }

    @Test
    void requeuesFailedCorrectedTemplateInsteadOfCreatingDuplicate() {
        Fixture fixture = new Fixture();
        RecordExport failed = fixture.export("FAILED");
        when(fixture.records.reusableExport(fixture.recordId, fixture.versionId, fixture.confirmationId, "DOCX"))
                .thenReturn(Optional.empty());
        when(fixture.records.failedCurrentTemplateExport(fixture.recordId, fixture.versionId,
                fixture.confirmationId, "DOCX")).thenReturn(Optional.of(failed));
        when(fixture.records.exportsByVisit(fixture.visitId)).thenReturn(List.of(failed));

        fixture.workflow.recordExport(fixture.visitId, fixture.doctorId, new ExportMedicalRecordRequest("DOCX"));

        verify(fixture.records).requeueFailedExport(fixture.visitId, failed.id(), "DOCX");
        verify(fixture.records, never()).insertExport(fixture.recordId, 3, fixture.confirmationId,
                fixture.versionId, "DOCX", fixture.doctorId);
    }

    @Test
    void reusesExportCreatedByAConcurrentRequest() {
        Fixture fixture = new Fixture();
        RecordExport concurrent = fixture.export("PENDING");
        when(fixture.records.reusableExport(fixture.recordId, fixture.versionId, fixture.confirmationId, "PDF"))
                .thenReturn(Optional.empty(), Optional.of(concurrent));
        when(fixture.records.failedCurrentTemplateExport(fixture.recordId, fixture.versionId,
                fixture.confirmationId, "PDF")).thenReturn(Optional.empty());
        when(fixture.records.insertExport(fixture.recordId, 3, fixture.confirmationId,
                fixture.versionId, "PDF", fixture.doctorId)).thenReturn(Optional.empty());
        when(fixture.records.exportsByVisit(fixture.visitId)).thenReturn(List.of(concurrent));

        fixture.workflow.recordExport(fixture.visitId, fixture.doctorId, new ExportMedicalRecordRequest("PDF"));

        verify(fixture.records, never()).createExportJob(fixture.visitId, concurrent.id(), "PDF");
    }

    private static final class Fixture {
        private final UUID visitId = UUID.randomUUID();
        private final UUID doctorId = UUID.randomUUID();
        private final UUID recordId = UUID.randomUUID();
        private final UUID versionId = UUID.randomUUID();
        private final UUID confirmationId = UUID.randomUUID();
        private final MedicalRecordMapper records = mock(MedicalRecordMapper.class);
        private final ClinicalWorkflowService workflow;

        private Fixture() {
            VisitMapper visits = mock(VisitMapper.class);
            Visit visit = new Visit(visitId, "V-001", UUID.randomUUID(), doctorId, "ACTIVE", null,
                    null, null, null, null, null, 1, Instant.now());
            when(visits.find(eq(visitId), eq(doctorId), anyBoolean())).thenReturn(Optional.of(visit));
            when(records.currentVersion(visitId)).thenReturn(Optional.of(new com.medicalai.domain.MedicalRecordVersion(
                    versionId, recordId, 3, UUID.randomUUID(), "snapshot", "{}", null,
                    "SUCCEEDED", "test", Instant.now())));
            when(records.currentConfirmedVersion(visitId)).thenReturn(Optional.of(
                    new MedicalRecordMapper.ConfirmedVersion(recordId, versionId, 3, confirmationId)));
            workflow = new ClinicalWorkflowService(visits, mock(PatientMapper.class), mock(DoctorMapper.class),
                    mock(RecordingMapper.class), records, mock(AiServiceClient.class), mock(AudioStorageService.class),
                    new ObjectMapper(), Clock.systemUTC(), mock(DashScopeRoleClient.class),
                    mock(TranscriptRoleReclassificationStore.class));
        }

        private RecordExport export(String status) {
            return new RecordExport(UUID.randomUUID(), recordId, 3,
                    MedicalRecordMapper.CURRENT_EXPORT_TEMPLATE_VERSION, "PDF", status, "医生", Instant.now());
        }
    }
}

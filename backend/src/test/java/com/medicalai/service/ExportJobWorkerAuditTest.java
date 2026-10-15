package com.medicalai.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.AuditResult;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.MedicalRecordMapper.ExportAuditContext;
import com.medicalai.mapper.MedicalRecordMapper.ExportJob;
import com.medicalai.mapper.MedicalRecordMapper.ExportPayload;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportJobWorkerAuditTest {
    @Test
    void writesSuccessAuditOnlyAfterExportReachesTerminalState() {
        Fixture fixture = new Fixture();
        when(fixture.records.claimNextExportJob(any(UUID.class))).thenReturn(Optional.of(fixture.job));
        when(fixture.records.exportPayload(fixture.exportId)).thenReturn(Optional.of(fixture.payload));
        when(fixture.files.generate(fixture.payload)).thenReturn("exports/record.pdf");
        when(fixture.records.markExportSucceeded(eq(fixture.exportId), eq(fixture.jobId), any(String.class))).thenReturn(true);
        when(fixture.records.exportAuditContext(fixture.exportId)).thenReturn(Optional.of(fixture.context));

        fixture.worker.processOne();

        verify(fixture.auditLogs).recordMedicalRecordExport(fixture.doctorId, fixture.visitId,
                fixture.exportId, "PDF", AuditResult.SUCCESS, null, null, null, null);
    }

    @Test
    void writesFailureAuditOnlyAfterFinalRetryFails() {
        Fixture fixture = new Fixture(3);
        when(fixture.records.claimNextExportJob(any(UUID.class))).thenReturn(Optional.of(fixture.job));
        when(fixture.records.exportPayload(fixture.exportId)).thenReturn(Optional.of(fixture.payload));
        when(fixture.files.generate(fixture.payload)).thenThrow(new IllegalStateException("文件生成失败"));
        when(fixture.records.markExportFailed(eq(fixture.exportId), eq(fixture.jobId), eq(3), any(String.class))).thenReturn(true);
        when(fixture.records.exportAuditContext(fixture.exportId)).thenReturn(Optional.of(fixture.context));

        fixture.worker.processOne();

        verify(fixture.auditLogs).recordMedicalRecordExport(fixture.doctorId, fixture.visitId,
                fixture.exportId, "PDF", AuditResult.FAILED, null, null, null, null);
    }

    @Test
    void doesNotWriteAuditForIntermediateRetry() {
        Fixture fixture = new Fixture(2);
        when(fixture.records.claimNextExportJob(any(UUID.class))).thenReturn(Optional.of(fixture.job));
        when(fixture.records.exportPayload(fixture.exportId)).thenReturn(Optional.of(fixture.payload));
        when(fixture.files.generate(fixture.payload)).thenThrow(new IllegalStateException("文件生成失败"));
        when(fixture.records.markExportFailed(eq(fixture.exportId), eq(fixture.jobId), eq(2), any(String.class)))
                .thenReturn(false);

        fixture.worker.processOne();

        verify(fixture.auditLogs, never()).recordMedicalRecordExport(any(UUID.class), any(UUID.class),
                any(UUID.class), any(String.class), any(AuditResult.class));
    }

    private static final class Fixture {
        private final MedicalRecordMapper records = mock(MedicalRecordMapper.class);
        private final ExportFileService files = mock(ExportFileService.class);
        private final AuditLogService auditLogs = mock(AuditLogService.class);
        private final UUID jobId = UUID.randomUUID();
        private final UUID exportId = UUID.randomUUID();
        private final UUID doctorId = UUID.randomUUID();
        private final UUID visitId = UUID.randomUUID();
        private final ExportJob job;
        private final ExportPayload payload;
        private final ExportAuditContext context = new ExportAuditContext(doctorId, visitId, "PDF");
        private final ExportJobWorker worker = new ExportJobWorker(records, files, auditLogs);

        private Fixture() {
            this(1);
        }

        private Fixture(int attempt) {
            job = new ExportJob(jobId, exportId, attempt);
            payload = new ExportPayload(exportId, UUID.randomUUID(), UUID.randomUUID(), visitId,
                    "VIS-001", 1, "PDF", "{}", null, Instant.now());
        }
    }
}

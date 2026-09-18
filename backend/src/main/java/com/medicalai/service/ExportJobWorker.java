package com.medicalai.service;

import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.domain.AuditResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExportJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExportJobWorker.class);
    private final MedicalRecordMapper records;
    private final ExportFileService files;
    private final AuditLogService auditLogs;

    public ExportJobWorker(MedicalRecordMapper records, ExportFileService files, AuditLogService auditLogs) {
        this.records = records;
        this.files = files;
        this.auditLogs = auditLogs;
    }

    @Scheduled(fixedDelayString = "${medicalai.export.worker-delay-ms:1000}")
    @Transactional
    public void processOne() {
        UUID lease = UUID.randomUUID();
        var job = records.claimNextExportJob(lease);
        if (job.isEmpty()) return;
        var claimed = job.get();
        try {
            var payload = records.exportPayload(claimed.exportId())
                    .orElseThrow(() -> new IllegalStateException("export payload not found"));
            String objectKey = files.generate(payload);
            if (records.markExportSucceeded(claimed.exportId(), claimed.jobId(), objectKey)) {
                scheduleTerminalAudit(claimed.exportId(), AuditResult.SUCCESS);
            }
        } catch (Exception e) {
            LOG.error("Export job failed: jobId={}, exportId={}", claimed.jobId(), claimed.exportId(), e);
            if (records.markExportFailed(claimed.exportId(), claimed.jobId(), claimed.attempt(), safeMessage(e))) {
                scheduleTerminalAudit(claimed.exportId(), AuditResult.FAILED);
            }
        }
    }

    private void scheduleTerminalAudit(UUID exportId, AuditResult result) {
        try {
            records.exportAuditContext(exportId).ifPresent(context -> {
                Runnable writeAudit = () -> writeTerminalAudit(exportId, context, result);
                if (TransactionSynchronizationManager.isSynchronizationActive()
                        && TransactionSynchronizationManager.isActualTransactionActive()) {
                    // 导出状态提交后才以独立事务写审计，避免审计失败回滚已完成的异步任务。
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            writeAudit.run();
                        }
                    });
                    return;
                }
                writeAudit.run();
            });
        } catch (RuntimeException exception) {
            LOG.error("Export audit log failed: exportId={}, result={}", exportId, result, exception);
        }
    }

    private void writeTerminalAudit(UUID exportId, MedicalRecordMapper.ExportAuditContext context, AuditResult result) {
        try {
            auditLogs.recordMedicalRecordExport(context.doctorId(), context.visitId(), exportId, context.format(), result);
        } catch (RuntimeException exception) {
            LOG.error("Export audit log failed: exportId={}, result={}", exportId, result, exception);
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}

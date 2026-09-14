package com.medicalai.service;

import com.medicalai.mapper.MedicalRecordMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExportJobWorker.class);
    private final MedicalRecordMapper records;
    private final ExportFileService files;

    public ExportJobWorker(MedicalRecordMapper records, ExportFileService files) {
        this.records = records;
        this.files = files;
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
            records.markExportSucceeded(claimed.exportId(), claimed.jobId(), objectKey);
        } catch (Exception e) {
            LOG.error("Export job failed: jobId={}, exportId={}", claimed.jobId(), claimed.exportId(), e);
            records.markExportFailed(claimed.exportId(), claimed.jobId(), claimed.attempt(), safeMessage(e));
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}

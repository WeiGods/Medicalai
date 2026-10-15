package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.ClinicalExtractionJobMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 与 ASR 独立执行；提取失败不会把已完成的转写改为失败。 */
@Service
public class ClinicalExtractionJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalExtractionJobWorker.class);
    private final ClinicalExtractionJobMapper jobs;
    private final RecordingMapper recordings;
    private final VisitMapper visits;
    private final ClinicalExtractionService extractions;
    private final ScheduledExecutorService heartbeat;

    public ClinicalExtractionJobWorker(ClinicalExtractionJobMapper jobs, RecordingMapper recordings,
            VisitMapper visits, ClinicalExtractionService extractions,
            @Qualifier("asrLeaseHeartbeat") ScheduledExecutorService heartbeat) {
        this.jobs = jobs;
        this.recordings = recordings;
        this.visits = visits;
        this.extractions = extractions;
        this.heartbeat = heartbeat;
    }

    @Scheduled(scheduler="localExtractionScheduler", fixedDelayString="${medicalai.extraction.poll-delay-ms:1000}")
    public void processLocal() { process("LOCAL"); }

    @Scheduled(scheduler="publicExtractionScheduler", fixedDelayString="${medicalai.extraction.poll-delay-ms:1000}")
    public void processPublic() { process("DASHSCOPE"); }

    private void process(String route) {
        UUID token = UUID.randomUUID();
        var job = jobs.claim(route, token).orElse(null);
        if (job == null) return;
        var renewal = heartbeat.scheduleWithFixedDelay(() -> {
            try { jobs.renew(job.id(), token); }
            catch (Exception error) { LOG.warn("提取任务续期失败: jobId={}", job.id()); }
        }, 20, 20, TimeUnit.SECONDS);
        try {
            var snapshot = recordings.latestSnapshot(job.visitId()).orElseThrow(() ->
                    BusinessException.conflict("SOURCE_CHANGED", "当前对话已更新"));
            if (!snapshot.snapshotHash().equals(job.snapshotHash())) {
                jobs.finish(job.id(), token, "CANCELLED", null);
                return;
            }
            var result = extractions.generateForJob(job.visitId(), visits.doctorId(job.visitId()),
                    snapshot, job.route(), job.id(), token);
            jobs.finish(job.id(), token, "GENERATED".equals(result.status()) ? "SUCCEEDED" : "FAILED",
                    result.qualityIssues().isEmpty() ? null : String.join("\n", result.qualityIssues()));
        } catch (BusinessException error) {
            boolean obsolete = Set.of("SOURCE_CHANGED", "NOT_FOUND", "RECORD_CONFIRMED").contains(error.code());
            jobs.finish(job.id(), token, obsolete ? "CANCELLED" : "FAILED", obsolete ? null : error.getMessage());
        } catch (Exception error) {
            LOG.error("自动信息提取失败: jobId={}", job.id(), error);
            jobs.finish(job.id(), token, "FAILED", "信息提取暂未完成，请重试。");
        } finally {
            renewal.cancel(false);
        }
    }
}

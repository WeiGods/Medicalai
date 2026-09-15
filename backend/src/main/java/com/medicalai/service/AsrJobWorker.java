package com.medicalai.service;

import com.medicalai.mapper.RecordingMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Network/model work runs outside transactions and on separate route schedulers. */
@Service
public class AsrJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AsrJobWorker.class);
    private final AsrJobStore store;
    private final AudioStorageService storage;
    private final DashScopeAsrClient cloud;
    private final LocalAsrClient local;
    private final DashScopeRoleClient cloudRoles;
    private final ScheduledExecutorService heartbeat;
    private final long publicTimeoutMs;

    public AsrJobWorker(AsrJobStore store, AudioStorageService storage, DashScopeAsrClient cloud,
            LocalAsrClient local, DashScopeRoleClient cloudRoles,
            @Qualifier("asrLeaseHeartbeat") ScheduledExecutorService heartbeat,
            @Value("${medicalai.dashscope.timeout-ms:600000}") long publicTimeoutMs) {
        this.store=store; this.storage=storage; this.cloud=cloud; this.local=local;
        this.cloudRoles=cloudRoles; this.heartbeat=heartbeat; this.publicTimeoutMs=publicTimeoutMs;
    }

    @Scheduled(scheduler="publicAsrScheduler", fixedDelayString="${medicalai.dashscope.poll-delay-ms:3000}")
    public void processPublic() { process("DASHSCOPE"); }

    @Scheduled(scheduler="localAsrScheduler", fixedDelayString="${medicalai.local-asr.poll-delay-ms:3000}")
    public void processLocal() { process("LOCAL"); }

    private void process(String provider) {
        UUID token = UUID.randomUUID();
        var claimed = store.claim(provider, token);
        if (claimed.isEmpty()) return;
        var job = claimed.get();
        AtomicBoolean lost = new AtomicBoolean(false);
        ScheduledFuture<?> renewal = heartbeat.scheduleWithFixedDelay(() -> {
            try {
                if (!store.renew(job.id(), token)) lost.set(true);
            } catch (Exception e) {
                lost.set(true);
                LOG.warn("ASR lease renewal failed: jobId={}, provider={}", job.id(), provider);
            }
        }, 20, 20, TimeUnit.SECONDS);
        try {
            if (!provider.equals(job.providerRoute())) throw new IllegalStateException("ASR 任务路由不匹配");
            if ("PENDING".equals(job.status())) {
                var recording = store.begin(job, token);
                if ("LOCAL".equals(provider)) {
                    var result = local.transcribe(storage.load(recording.objectKey()), recording.fileName(), recording.mimeType());
                    checkLease(lost);
                    store.complete(job, token, recording.id(), localTurns(result));
                } else {
                    String taskId = cloud.submit(storage.presignedUrl(recording.objectKey()));
                    checkLease(lost);
                    store.submitted(job, token, recording.id(), taskId);
                }
            } else if ("LOCAL".equals(provider)) {
                // A synchronous local job can only be reclaimed after its worker lost
                // the lease or stopped. Never replay potentially ongoing inference.
                throw new IllegalStateException("本地 ASR 任务已中断，请手动重试");
            } else {
                pollPublic(job, token, lost);
            }
        } catch (AsrJobStore.LeaseLostException e) {
            LOG.warn("Discarding stale ASR execution: jobId={}, provider={}", job.id(), provider);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (message.length() > 1000) message=message.substring(0,1000);
            LOG.error("ASR failed: jobId={}, provider={}, message={}", job.id(), provider, message);
            try {
                checkLease(lost);
                store.fail(job, token, message);
            } catch (AsrJobStore.LeaseLostException ignored) {
                LOG.warn("Stale failure ignored: jobId={}", job.id());
            }
        } finally {
            renewal.cancel(false);
        }
    }

    private void pollPublic(RecordingMapper.AsrJob job, UUID token, AtomicBoolean lost) {
        if (job.startedAt()!=null && System.currentTimeMillis()-job.startedAt().toEpochMilli()>publicTimeoutMs)
            throw new IllegalStateException("公网 ASR 任务等待超时");
        if (job.providerTaskId()==null || job.providerTaskId().isBlank() || job.recordingId()==null)
            throw new IllegalStateException("公网 ASR 提交已中断，缺少任务编号，请手动重试");
        var task=cloud.query(job.providerTaskId());
        checkLease(lost);
        if (Set.of("PENDING","RUNNING").contains(task.status())) {
            store.release(job, token);
            return;
        }
        if (!"SUCCEEDED".equals(task.status())) throw new IllegalStateException("公网 ASR 任务状态："+task.status());
        var turns=publicTurns(cloud.result(task));
        checkLease(lost);
        store.complete(job, token, job.recordingId(), turns);
    }

    private List<RecordingMapper.Turn> localTurns(List<AsrSegment> result) {
        return result.stream().map(s -> new RecordingMapper.Turn("OTHER",s.text(),s.startMs(),s.endMs(),s.speakerId())).toList();
    }

    private List<RecordingMapper.Turn> publicTurns(List<AsrSegment> result) {
        if (result.isEmpty()) throw new IllegalStateException("公网 ASR 返回空句段");
        var input=IntStream.range(0,result.size()).mapToObj(i -> Map.<String,Object>of(
                "speaker_id",roleKey(result.get(i),i),"text",result.get(i).text())).toList();
        var roles=cloudRoles.assignRoles(input);
        return IntStream.range(0,result.size()).mapToObj(i -> {
            var s=result.get(i);
            return new RecordingMapper.Turn(roles.getOrDefault(roleKey(s,i),"OTHER"),s.text(),s.startMs(),s.endMs(),s.speakerId());
        }).toList();
    }

    private int roleKey(AsrSegment segment,int index) {
        return segment.speakerId()!=null && segment.speakerId()>=0 ? segment.speakerId() : -index-1;
    }
    private void checkLease(AtomicBoolean lost) { if (lost.get()) throw new AsrJobStore.LeaseLostException(); }
}

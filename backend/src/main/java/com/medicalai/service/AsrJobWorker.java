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

/**
 * ASR 主流程（网络和模型调用均不持有数据库事务）：
 * 1. 按公网/本地路由领取任务并续租；2. 调用 ASR 得到原始句段；
 * 3. 将每个句段交给角色 LLM；4. 由 AsrJobStore 在一个事务内保存原始回包、句段和快照。
 */
@Service
public class AsrJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AsrJobWorker.class);
    /**
     * Prevent a pre-role-classification worker connected to the shared database
     * from claiming new public ASR jobs and silently persisting AUTO roles.
     */
    public static final String PUBLIC_ROLE_ROUTE = "DASHSCOPE_ROLE_V2";
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
    public void processPublic() { process(PUBLIC_ROLE_ROUTE); }

    @Scheduled(scheduler="localAsrScheduler", fixedDelayString="${medicalai.local-asr.poll-delay-ms:3000}")
    public void processLocal() { process("LOCAL"); }

    private void process(String provider) {
        UUID token = UUID.randomUUID();
        var claimed = store.claim(provider, token);
        if (claimed.isEmpty()) return;
        var job = claimed.get();
        long taskStartedAt = System.nanoTime();
        if ("PENDING".equals(job.status())) {
            LOG.info("ASR任务开始：任务ID={}，接诊ID={}，录音ID={}，路由={}，尝试次数={}",
                    job.id(), job.visitId(), job.recordingId(), provider, job.attemptCount());
        }
        AtomicBoolean lost = new AtomicBoolean(false);
        ScheduledFuture<?> renewal = heartbeat.scheduleWithFixedDelay(() -> {
            try {
                if (!store.renew(job.id(), token)) lost.set(true);
            } catch (Exception e) {
                lost.set(true);
                LOG.warn("ASR任务租约续期失败：任务ID={}，路由={}", job.id(), provider);
            }
        }, 20, 20, TimeUnit.SECONDS);
        try {
            if (!provider.equals(job.providerRoute())) throw new IllegalStateException("ASR 任务路由不匹配");
            if ("PENDING".equals(job.status())) {
                // 第一步：将任务和录音切换为处理中，并获得本次录音的受控引用。
                var recording = store.begin(job, token);
                if ("LOCAL".equals(provider)) {
                    // 第二步（本地）：同步调用本地 ASR，原始文本、时间戳和 speaker_id 都由它返回。
                    long asrStartedAt = System.nanoTime();
                    var result = local.transcribeDetailed(storage.load(recording.objectKey()), recording.fileName(), recording.mimeType());
                    LOG.info("本地ASR调用成功：任务ID={}，句段数={}，耗时毫秒={}",
                            job.id(), result.segments().size(), elapsedMs(asrStartedAt));
                    checkLease(lost);
                    // 第三、四步：角色 LLM 判断后，事务性写入原始 ASR 回包、逐句数据和转写快照。
                    store.complete(job, token, recording.id(), roleAssignedTurns(job, result.segments()), result.rawResponse());
                } else {
                    // 第二步（公网）：只提交一次异步任务；后续轮询会查询该任务并取得最终结果。
                    long submitStartedAt = System.nanoTime();
                    String taskId = cloud.submit(storage.presignedUrl(recording.objectKey()));
                    LOG.info("公网ASR任务提交成功：任务ID={}，耗时毫秒={}", job.id(), elapsedMs(submitStartedAt));
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
            LOG.warn("已丢弃过期的ASR执行结果：任务ID={}，路由={}", job.id(), provider);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (message.length() > 1000) message=message.substring(0,1000);
            LOG.error("ASR任务失败：任务ID={}，路由={}，耗时毫秒={}，异常类型={}，原因={}",
                    job.id(), provider, elapsedMs(taskStartedAt), e.getClass().getSimpleName(), message);
            try {
                checkLease(lost);
                store.fail(job, token, message);
            } catch (AsrJobStore.LeaseLostException ignored) {
                LOG.warn("已忽略过期ASR失败结果：任务ID={}", job.id());
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
        // 第一步：轻量查询公网任务状态。处理中只释放租约，避免持续占用工作线程。
        var task=cloud.query(job.providerTaskId());
        checkLease(lost);
        if (Set.of("PENDING","RUNNING").contains(task.status())) {
            store.release(job, token);
            return;
        }
        if (!"SUCCEEDED".equals(task.status())) throw new IllegalStateException("公网 ASR 任务状态："+task.status());
        // 第二步：仅在任务成功后下载并解析最终 ASR 结果，轮询过程不打印句段内容。
        long resultStartedAt = System.nanoTime();
        var result=cloud.resultDetailed(task);
        LOG.info("公网ASR结果已获取：任务ID={}，任务状态={}，句段数={}，耗时毫秒={}",
                job.id(), task.status(), result.segments().size(), elapsedMs(resultStartedAt));
        checkLease(lost);
        // 第三、四步：LLM 逐句分类并原子落库。
        store.complete(job, token, job.recordingId(), roleAssignedTurns(job, result.segments()), result.rawResponse());
    }

    private List<RecordingMapper.Turn> roleAssignedTurns(RecordingMapper.AsrJob job, List<AsrSegment> result) {
        if (result.isEmpty()) throw new IllegalStateException("ASR 返回空句段");
        // LLM 只接收 ASR 已产生的文本、时间和声学 speaker_id；不会改写这些原始字段。
        var input=IntStream.range(0,result.size()).mapToObj(i -> {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("index", i);
            turn.put("speaker_id", result.get(i).speakerId());
            turn.put("start_ms", result.get(i).startMs());
            turn.put("end_ms", result.get(i).endMs());
            turn.put("text", result.get(i).text());
            return turn;
        }).toList();
        long roleStartedAt = System.nanoTime();
        var assignedRoles=cloudRoles.assignRoles(input);
        Map<Integer, DashScopeRoleClient.RoleAssignment> roles = assignedRoles == null
                ? Map.of() : assignedRoles;
        List<RecordingMapper.Turn> turns = IntStream.range(0,result.size()).mapToObj(i -> {
            var s=result.get(i);
            var assignment=roles.getOrDefault(i, new DashScopeRoleClient.RoleAssignment("OTHER", null, "FALLBACK"));
            return new RecordingMapper.Turn(assignment.role(), s.text(), s.startMs(), s.endMs(), s.speakerId(),
                    assignment.source(), assignment.confidence());
        }).toList();
        long fallback = turns.stream().filter(turn -> "FALLBACK".equals(turn.roleSource())).count();
        LOG.info("ASR角色判断完成：任务ID={}，路由={}，句段数={}，降级句段数={}，耗时毫秒={}",
                job.id(), job.providerRoute(), turns.size(), fallback, elapsedMs(roleStartedAt));
        return turns;
    }

    private long elapsedMs(long startedAt) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt); }

    private void checkLease(AtomicBoolean lost) { if (lost.get()) throw new AsrJobStore.LeaseLostException(); }
}

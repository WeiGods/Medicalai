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
 * 执行录音转写与角色 LLM 的第 5 至 9 步；网络和模型调用均不持有数据库事务。
 *
 * <p>第 5 步领取并续租任务；第 6 至 7 步调用或轮询 ASR；第 8 步调用角色 LLM；
 * 第 9 步委托 {@link AsrJobStore} 原子保存结果。
 */
@Service
public class AsrJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AsrJobWorker.class);
    /** 防止旧版本工作线程领取公网任务后静默写入 AUTO 角色。 */
    public static final String PUBLIC_ROLE_ROUTE = "DASHSCOPE_ROLE_V2";
    private final AsrJobStore store;
    private final AudioStorageService storage;
    private final DashScopeAsrClient cloud;
    private final LocalAsrClient local;
    private final LlmRoleRouter roleRouter;
    private final ScheduledExecutorService heartbeat;
    private final long publicTimeoutMs;

    @org.springframework.beans.factory.annotation.Autowired
    public AsrJobWorker(AsrJobStore store, AudioStorageService storage, DashScopeAsrClient cloud,
            LocalAsrClient local, LlmRoleRouter roleRouter,
            @Qualifier("asrLeaseHeartbeat") ScheduledExecutorService heartbeat,
            @Value("${medicalai.dashscope.timeout-ms:600000}") long publicTimeoutMs) {
        this.store=store; this.storage=storage; this.cloud=cloud; this.local=local;
        this.roleRouter=roleRouter; this.heartbeat=heartbeat; this.publicTimeoutMs=publicTimeoutMs;
    }

    /** 兼容既有仅覆盖公网任务的单元测试构造方式。 */
    public AsrJobWorker(AsrJobStore store, AudioStorageService storage, DashScopeAsrClient cloud,
            LocalAsrClient local, DashScopeRoleClient cloudRoles,
            @Qualifier("asrLeaseHeartbeat") ScheduledExecutorService heartbeat, long publicTimeoutMs) {
        this(store, storage, cloud, local, new LlmRoleRouter(cloudRoles), heartbeat, publicTimeoutMs);
    }

    @Scheduled(scheduler="publicAsrScheduler", fixedDelayString="${medicalai.dashscope.poll-delay-ms:3000}")
    public void processPublic() { process(PUBLIC_ROLE_ROUTE); }

    @Scheduled(scheduler="localAsrScheduler", fixedDelayString="${medicalai.local-asr.poll-delay-ms:3000}")
    public void processLocal() { process("LOCAL"); }

    private void process(String provider) {
        // 步骤 5：按路由领取 PENDING 或 RUNNING 任务，并通过租约避免多个工作线程重复处理。
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
                // 步骤 5（续）：将任务和一条录音切换为处理中，并获得本次录音的受控引用。
                var recording = store.begin(job, token);
                if ("LOCAL".equals(provider)) {
                    if (!roleRouter.isConfigured()) {
                        throw new IllegalStateException("公网角色识别未配置 DASHSCOPE_API_KEY，本地 ASR 结果无法继续处理");
                    }
                    // 步骤 6（本地）：同步调用本地 ASR，获得原始文本、时间戳和 speaker_id。
                    long asrStartedAt = System.nanoTime();
                    var result = local.transcribeDetailed(storage.load(recording.objectKey()), recording.fileName(), recording.mimeType());
                    LOG.info("本地ASR调用成功：任务ID={}，句段数={}，耗时毫秒={}",
                            job.id(), result.segments().size(), elapsedMs(asrStartedAt));
                    checkLease(lost);
                    // 步骤 8 至 9：角色 LLM 判断后，事务性写入原始 ASR 回包、逐句数据和转写快照。
                    store.complete(job, token, recording.id(), roleAssignedTurns(job, result.segments()), result.rawResponse());
                } else {
                    // 步骤 6（公网）：只提交一次 DashScope 异步任务；后续轮询会取得最终结果。
                    long submitStartedAt = System.nanoTime();
                    String taskId = cloud.submit(storage.presignedUrl(recording.objectKey()));
                    LOG.info("公网ASR任务提交成功：任务ID={}，耗时毫秒={}", job.id(), elapsedMs(submitStartedAt));
                    checkLease(lost);
                    store.submitted(job, token, recording.id(), taskId);
                }
            } else if ("LOCAL".equals(provider)) {
                // 本地同步任务仅可在工作线程丢失租约或停止后被重新领取，不能重放可能仍在执行的推理。
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
        // 步骤 7：轻量查询公网任务状态。处理中只释放租约，避免持续占用工作线程。
        var task=cloud.query(job.providerTaskId());
        checkLease(lost);
        if (Set.of("PENDING","RUNNING").contains(task.status())) {
            store.release(job, token);
            return;
        }
        if (!"SUCCEEDED".equals(task.status())) throw new IllegalStateException("公网 ASR 任务状态："+task.status());
        // 步骤 7（续）：仅在任务成功后下载并解析最终 ASR 结果，轮询过程不打印句段内容。
        long resultStartedAt = System.nanoTime();
        var result=cloud.resultDetailed(task);
        LOG.info("公网ASR结果已获取：任务ID={}，任务状态={}，句段数={}，耗时毫秒={}",
                job.id(), task.status(), result.segments().size(), elapsedMs(resultStartedAt));
        checkLease(lost);
        // 步骤 8 至 9：LLM 逐句分类后，以原子事务写入结果。
        store.complete(job, token, job.recordingId(), roleAssignedTurns(job, result.segments()), result.rawResponse());
    }

    private List<RecordingMapper.Turn> roleAssignedTurns(RecordingMapper.AsrJob job, List<AsrSegment> result) {
        if (result.isEmpty()) throw new IllegalStateException("ASR 返回空句段");
        // 步骤 8：LLM 只接收 ASR 已产生的文本、时间和声学 speaker_id；不会改写这些原始字段。
        var input=IntStream.range(0,result.size()).mapToObj(i -> {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("index", i);
            turn.put("speaker_id", result.get(i).speakerId());
            turn.put("start_ms", result.get(i).startMs());
            turn.put("end_ms", result.get(i).endMs());
            turn.put("text", result.get(i).text());
            return turn;
        }).toList();
        // 步骤 8：角色识别与 ASR 路由汇合；本地或公网 ASR 的文本都统一交给公网 LLM 判断。
        LlmRoute route = roleRouter.roleRoute();
        long roleStartedAt = System.nanoTime();
        // 角色判断必须与产生文本的 ASR 路由一致，避免把内网录音内容发送到公网，或反向依赖本地服务。
        var assignedRoles = roleRouter.assignRoles(input);
        Map<Integer, DashScopeRoleClient.RoleAssignment> roles = assignedRoles == null
                ? Map.of() : assignedRoles;
        List<RecordingMapper.Turn> turns = IntStream.range(0,result.size()).mapToObj(i -> {
            var s=result.get(i);
            var assignment=roles.getOrDefault(i, new DashScopeRoleClient.RoleAssignment("OTHER", null, "FALLBACK"));
            return new RecordingMapper.Turn(assignment.role(), s.text(), s.startMs(), s.endMs(), s.speakerId(),
                    assignment.source(), assignment.confidence(), route.name());
        }).toList();
        long fallback = turns.stream().filter(turn -> "FALLBACK".equals(turn.roleSource())).count();
        LOG.info("ASR角色判断完成：任务ID={}，ASR路由={}，角色LLM路由={}，句段数={}，降级句段数={}，耗时毫秒={}",
                job.id(), job.providerRoute(), route, turns.size(), fallback, elapsedMs(roleStartedAt));
        return turns;
    }

    private long elapsedMs(long startedAt) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt); }

    private void checkLease(AtomicBoolean lost) { if (lost.get()) throw new AsrJobStore.LeaseLostException(); }
}

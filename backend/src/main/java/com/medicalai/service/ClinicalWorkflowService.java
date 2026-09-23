package com.medicalai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.*;
import com.medicalai.dto.*;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import com.medicalai.vo.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * 临床工作流服务。
 *
 * 录音上传、转写与角色 LLM 主链路如下：
 *
 *
 *    客户端调用 {@code POST /api/v1/visits/{visitId}/recordings} 上传录音。
 *    校验录音后写入对象存储，并创建状态为 {@code UPLOADED} 的录音记录。
 *    客户端调用 {@code POST /api/v1/visits/{visitId}/recordings/transcribe} 选择公网或本地 ASR。
 *    校验配置和待转写录音后创建 {@code PENDING} ASR 任务，由定时工作线程异步处理。
 *    工作线程领取任务并持有租约，将本次录音状态改为 {@code PROCESSING}。
 *    本地路由同步调用本地 ASR；公网路由提交 DashScope 异步任务。
 *    公网路由轮询 DashScope；任务成功后下载并解析最终转写句段。
 *    将句段文本、时间和声学说话人编号发送给角色 LLM，得到医生、患者或其他人及置信度。
 *    在短事务中脱敏保存原始响应、写入句段和对话快照，并更新录音与任务状态。
 *
 */
@Service
public class ClinicalWorkflowService {
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalWorkflowService.class);
    private static final Set<String> AUDIO_TYPES = Set.of("audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/webm");
    private final VisitMapper visits;
    private final PatientMapper patients;
    private final DoctorMapper doctors;
    private final RecordingMapper recordings;
    private final MedicalRecordMapper records;
    private final ClinicalExtractionService extractions;
    private final AiServiceClient ai;
    private final AudioStorageService storage;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LlmRouteResolver routes;
    private final LlmRoleRouter roleRouter;
    private final TranscriptRoleReclassificationStore roleReclassificationStore;
    private final AuditLogService auditLogs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExportFileService exportFiles;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExportTemplateService exportTemplates;
    @org.springframework.beans.factory.annotation.Value("${medicalai.dashscope.role-review-threshold:70}")
    private int roleReviewThreshold = 70;
    @org.springframework.beans.factory.annotation.Value("${medicalai.dashscope.api-key:}")
    private String dashscopeApiKey;

    public ClinicalWorkflowService(VisitMapper visits, PatientMapper patients, DoctorMapper doctors, RecordingMapper recordings,
                                   MedicalRecordMapper records, ClinicalExtractionService extractions, AiServiceClient ai,
                                   AudioStorageService storage, ObjectMapper objectMapper, Clock clock, DashScopeRoleClient roleClient,
                                   TranscriptRoleReclassificationStore roleReclassificationStore) {
        this(visits, patients, doctors, recordings, records, extractions, ai, storage, objectMapper, clock,
                roleReclassificationStore, new LlmRouteResolver(recordings), new LlmRoleRouter(roleClient));
    }

    public ClinicalWorkflowService(VisitMapper visits, PatientMapper patients, DoctorMapper doctors, RecordingMapper recordings,
                                   MedicalRecordMapper records, ClinicalExtractionService extractions, AiServiceClient ai,
                                   AudioStorageService storage, ObjectMapper objectMapper, Clock clock,
                                   TranscriptRoleReclassificationStore roleReclassificationStore,
                                   LlmRouteResolver routes, LlmRoleRouter roleRouter) {
        this(visits, patients, doctors, recordings, records, extractions, ai, storage, objectMapper, clock,
                roleReclassificationStore, routes, roleRouter, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ClinicalWorkflowService(VisitMapper visits, PatientMapper patients, DoctorMapper doctors, RecordingMapper recordings,
                                   MedicalRecordMapper records, ClinicalExtractionService extractions, AiServiceClient ai,
                                   AudioStorageService storage, ObjectMapper objectMapper, Clock clock,
                                   TranscriptRoleReclassificationStore roleReclassificationStore,
                                   LlmRouteResolver routes, LlmRoleRouter roleRouter, AuditLogService auditLogs) {
        this.visits = visits;
        this.patients = patients;
        this.doctors = doctors;
        this.recordings = recordings;
        this.records = records;
        this.extractions = extractions;
        this.ai = ai;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.roleReclassificationStore = roleReclassificationStore;
        this.routes = routes;
        this.roleRouter = roleRouter;
        this.auditLogs = auditLogs;
    }

    /** 保留既有单元测试和旧组装代码的构造方式；运行时始终注入完整的提取服务。 */
    public ClinicalWorkflowService(VisitMapper visits, PatientMapper patients, DoctorMapper doctors, RecordingMapper recordings,
                                   MedicalRecordMapper records, AiServiceClient ai, AudioStorageService storage,
                                   ObjectMapper objectMapper, Clock clock, DashScopeRoleClient roleClient,
                                   TranscriptRoleReclassificationStore roleReclassificationStore) {
        this(visits, patients, doctors, recordings, records, null, ai, storage, objectMapper, clock,
                roleClient, roleReclassificationStore);
    }

    @Transactional
    public List<RecordingVO> recordings(UUID visitId, UUID doctorId) {
        return recordings(visitId, doctorId, DoctorRole.DOCTOR);
    }

    @Transactional(readOnly = true)
    public List<RecordingVO> recordings(UUID visitId, UUID doctorId, DoctorRole role) {
        Visit visit = readable(visitId, doctorId, role);
        return recordings.list(visit.id()).stream().map(RecordingVO::from).toList();
    }

    @Transactional(readOnly = true)
    public Recording readableRecording(UUID recordingId, UUID doctorId, DoctorRole role) {
        Recording recording = recordings.findById(recordingId).orElseThrow(BusinessException::notFound);
        readable(recording.visitId(), doctorId, role);
        return recording;
    }

    @Transactional
    public RecordingVO upload(UUID visitId, UUID doctorId, MultipartFile file, Long durationMs) {
        // 步骤 1：接收录音上传请求，并校验接诊归属、接诊状态、病历可编辑性及录音基本信息。
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (name.isBlank() || !name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|wav|m4a|webm)$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", "仅支持 MP3、WAV、M4A 或 WEBM 录音");
        }
        if (file.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_AUDIO", "录音文件不能为空");
        if (file.getSize() > 200L * 1024 * 1024) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AUDIO_TOO_LARGE", "录音文件不能超过 200MB");
        }
        UUID id = UUID.randomUUID();
        // 步骤 2：保存录音到对象存储，创建 UPLOADED 状态的录音记录并记录审计日志。
        String objectKey = storage.save(file, visit.id().toString(), id.toString());
        Recording recording = recordings.insert(new Recording(id, visit.id(), nextRecordingNo(visit.id()),
                "UPLOAD", objectKey, name, file.getContentType(), file.getSize(),
                durationMs == null || durationMs < 0 ? null : durationMs, "UPLOADED", null, Instant.now(clock)));
        invalidateClinicalExtraction(visit.id());
        auditRecordingUploaded(doctorId, visit.id(), id, name);
        return RecordingVO.from(recording);
    }

    @Transactional
    public void deleteRecording(UUID visitId, UUID recordingId, UUID doctorId) {
        Recording recording = recordings.find(recordingId, doctorId)
                .orElseThrow(BusinessException::notFound);
        if (!recording.visitId().equals(visitId)) {
            throw BusinessException.notFound();
        }
        Visit visit = owned(recording.visitId(), doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        if ("PROCESSING".equals(recording.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_PROCESSING", "录音正在转写，暂不能删除");
        }
        // PENDING 任务还没有把 recording_id 写回 ai_job，不能仅靠录音状态判断；
        // 整个接诊有任务或实时录音会话时都拒绝删除，避免 worker 继续消费已删除的行。
        if (recordings.hasActiveAsrJob(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_PROCESSING", "本次接诊有转写任务正在执行，暂不能删除");
        }
        if (visits.hasOpenSession(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_PROCESSING", "本次接诊仍有录音操作进行中，暂不能删除");
        }
        if ("DONE".equals(recording.status())) {
            if (records.hasAnyConfirmation(visit.id()) || isConfirmed(visit.id())) {
                throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，已完成接诊不允许删除录音");
            }
            // 已完成录音的句段、快照、提取和未签署病历草稿均属于该接诊的派生链路，
            // 先在同一事务内清除，再让剩余录音重新进入队列。该物理删除不写审计日志。
            recordings.purgeVisitDerivedData(visit.id());
            recordings.requeueRemainingRecordings(visit.id());
        }
        // 先剥离 ASR 任务外键再删库行；转写失败的录音在 ai_job 中仍持有 recording_id 引用。
        recordings.detachAsrJobs(recording.id());
        try {
            if (recordings.delete(recording.id()) != 1) {
                throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_STATE_CHANGED", "录音状态已变化，请刷新后重试");
            }
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_HAS_STREAM_DATA",
                    "录音存在关联的转写会话数据，暂不能删除", e);
        }
        invalidateClinicalExtraction(visit.id());
        if (!"DONE".equals(recording.status()) && auditLogs != null) {
            auditLogs.recordRecordingDeleted(doctorId, visit.id(), recording.id(), recording.fileName());
        }
        Runnable deleteObject = () -> {
            try {
                storage.delete(recording.objectKey());
            } catch (RuntimeException e) {
                LOG.warn("录音对象删除失败，已保留孤儿文件: recordingId={}", recording.id(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteObject.run();
                }
            });
        } else {
            deleteObject.run();
        }
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AsrJobVO transcribe(UUID visitId, UUID doctorId, String provider) {
        // 步骤 3：接收转写请求，校验接诊状态、任务幂等性和所选 ASR 路由的运行条件。
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        Optional<RecordingMapper.AsrJob> active = recordings.latestAsrJob(visit.id())
                .filter(j -> Set.of("PENDING", "RUNNING").contains(j.status()));
        if (active.isPresent()) return asrJob(visitId, doctorId, active.get().id());
        if ("DASHSCOPE".equals(provider) && (dashscopeApiKey == null || dashscopeApiKey.isBlank())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_NOT_CONFIGURED", "未配置 DASHSCOPE_API_KEY，请配置公网凭据或选择本地 ASR");
        }
        recordings.requeueRetryableRecordings(visit.id());
        // 先恢复过期的 PROCESSING 记录，再校验存储凭据，确保失败提交后的录音可以重试。
        storage.assertAsrSubmissionReady();
        List<Recording> pending = recordings.list(visit.id()).stream()
                .filter(r -> "UPLOADED".equals(r.status())).toList();
        if (pending.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "NO_PENDING_RECORDING", "请先上传录音");
        // 步骤 4：创建 PENDING ASR 任务；定时工作线程会按任务路由异步领取并处理。
        UUID jobId = recordings.createAsrJob(visit.id(), queueProvider(provider));
        return asrJob(visitId, doctorId, jobId);
    }

    /** 在不重新上传原始文件的情况下，使用另一条 ASR 路由重新处理指定录音。 */
    @Transactional
    public AsrJobVO retranscribeRecording(UUID visitId, UUID recordingId, UUID doctorId, String provider) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        Recording recording = recordings.find(recordingId, doctorId)
                .filter(item -> visit.id().equals(item.visitId()))
                .orElseThrow(BusinessException::notFound);
        if (!"DONE".equals(recording.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_NOT_TRANSCRIBED", "只能重新转写已完成的录音");
        }
        if (recordings.hasActiveAsrJob(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_PROCESSING", "本次接诊已有转写任务正在执行");
        }
        if (visits.hasOpenSession(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_PROCESSING", "本次接诊仍有录音操作进行中，暂不能重新转写");
        }
        if ("DASHSCOPE".equals(provider) && (dashscopeApiKey == null || dashscopeApiKey.isBlank())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DASHSCOPE_NOT_CONFIGURED", "未配置 DASHSCOPE_API_KEY，请配置公网凭据或选择本地 ASR");
        }
        storage.assertAsrSubmissionReady();
        if (!recordings.requeueTranscribedRecording(recording.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_STATE_CHANGED", "录音状态已变化，请刷新后重试");
        }
        UUID jobId = recordings.createAsrJob(visit.id(), queueProvider(provider), recording.id());
        invalidateClinicalExtraction(visit.id());
        return asrJob(visitId, doctorId, jobId);
    }

    @Transactional(readOnly = true)
    public AsrJobVO asrJob(UUID visitId, UUID doctorId, UUID jobId) {
        return asrJob(visitId, doctorId, jobId, DoctorRole.DOCTOR);
    }

    @Transactional(readOnly = true)
    public AsrJobVO asrJob(UUID visitId, UUID doctorId, UUID jobId, DoctorRole role) {
        Visit visit = readable(visitId, doctorId, role);
        RecordingMapper.AsrJob job = recordings.asrJob(jobId, visit.id())
                .orElseThrow(BusinessException::notFound);
        List<Recording> all = recordings.list(visit.id());
        int completed = (int) all.stream().filter(r -> "DONE".equals(r.status())).count();
        TranscriptVO result = "SUCCEEDED".equals(job.status()) ? transcript(visitId, doctorId, role) : null;
        return new AsrJobVO(job.id(), job.status(), all.size(), completed, job.lastError(), result,
                displayProvider(job.providerRoute()));
    }

    @Transactional(readOnly = true)
    public TranscriptVO transcript(UUID visitId, UUID doctorId) {
        return transcript(visitId, doctorId, DoctorRole.DOCTOR);
    }

    @Transactional(readOnly = true)
    public TranscriptVO transcript(UUID visitId, UUID doctorId, DoctorRole role) {
        Visit visit = readable(visitId, doctorId, role);
        Optional<DialogueSnapshot> snapshot = recordings.latestSnapshot(visit.id());
        if (snapshot.isEmpty()) {
            return new TranscriptVO(null, 0, null, null, "", false, false, List.of());
        }
        List<UtteranceVO> turns = recordings.list(visit.id()).stream()
                .filter(r -> "DONE".equals(r.status()))
                .flatMap(r -> recordings.listByRecording(r.id()).stream())
                .map(u -> UtteranceVO.from(u, roleReviewThreshold)).toList();
        Optional<RecordingMapper.TurnState> state = recordings.transcript(visit.id());
        String text = state.map(RecordingMapper.TurnState::transcript).orElse("");
        boolean edited = state.map(RecordingMapper.TurnState::edited).orElse(false);
        boolean dirty = state.isPresent() && !state.get().snapshotId().equals(snapshot.get().id());
        LlmRouting routing = routes.routing(snapshot.get());
        return new TranscriptVO(snapshot.get().id().toString(), snapshot.get().snapshotVersion(),
                snapshot.get().snapshotHash(), snapshot.get().authorityStatus(), text, edited, dirty, turns,
                routing.sourceRoute().name(), routing.availableRoutes().stream().map(Enum::name).toList(),
                routing.selectionRequired());
    }

    @Transactional(readOnly = true)
    public ClinicalExtractionVO clinicalExtraction(UUID visitId, UUID doctorId) {
        return clinicalExtraction(visitId, doctorId, DoctorRole.DOCTOR);
    }

    @Transactional(readOnly = true)
    public ClinicalExtractionVO clinicalExtraction(UUID visitId, UUID doctorId, DoctorRole role) {
        Visit visit = readable(visitId, doctorId, role);
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id()).orElse(null);
        if (snapshot == null) {
            return new ClinicalExtractionVO(null, 0, "PENDING", null, null, Map.of(), List.of(), null, null);
        }
        return extractionService().current(visit.id(), snapshot);
    }

    public ClinicalExtractionVO generateClinicalExtraction(UUID visitId, UUID doctorId) {
        return generateClinicalExtraction(visitId, doctorId, null);
    }

    public ClinicalExtractionVO generateClinicalExtraction(UUID visitId, UUID doctorId, String requestedProvider) {
        // 提取会同步等待远程 LLM 返回，不能在这段网络等待期间持有 visit 的 FOR UPDATE 锁。
        // 否则取消接诊会被无谓阻塞；提取服务不会在远程调用前获取这把接诊行锁。
        Visit visit = owned(visitId, doctorId, false);
        requireActive(visit);
        requireRecordEditable(visit.id());
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id()).orElseThrow(() ->
                new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成录音转写"));
        // 提取路由已固定为公网；转写可以来自内网，但分析必须使用可配置的公网模型。
        return extractionService().generate(visit.id(), doctorId, snapshot,
                routes.resolveAnalysis(snapshot, requestedProvider));
    }

    @Transactional
    public ClinicalExtractionVO confirmClinicalExtraction(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id()).orElseThrow(() ->
                new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成录音转写"));
        return extractionService().confirm(visit.id(), doctorId, snapshot);
    }

    @Transactional
    public TranscriptVO saveTranscript(UUID visitId, UUID doctorId, SaveTranscriptRequest request) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成转写"));
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        String editedText = request.transcript().strip();
        RecordingMapper.TurnState current = recordings.transcript(visit.id()).orElse(null);
        if (current != null && editedText.equals(current.transcript())) {
            return transcript(visitId, doctorId);
        }
        List<RecordingMapper.Turn> editedTurns = editedTurns(editedText);
        if (editedTurns.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TRANSCRIPT_EMPTY", "转写内容不能为空");
        }
        UUID editedSnapshotId = recordings.createEditedSnapshot(visit.id(), snapshot, editedTurns,
                DialogueSnapshotHasher.hash(visit.id(), editedTurns), doctorId);
        recordings.saveTranscript(visit.id(), editedSnapshotId, editedText, true);
        invalidateClinicalExtraction(visit.id());
        return transcript(visitId, doctorId);
    }

    @Transactional
    public TranscriptVO updateUtteranceRole(UUID visitId, UUID doctorId, UUID utteranceId,
                                            UpdateUtteranceRoleRequest request) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成转写"));
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        recordings.utterance(visit.id(), utteranceId).orElseThrow(BusinessException::notFound);
        List<RecordingMapper.Turn> turnsBeforeUpdate = currentTurns(visit.id());
        RecordingMapper.TurnState current = recordings.transcript(visit.id()).orElse(null);
        if (current != null && current.edited() && !transcriptText(turnsBeforeUpdate).equals(current.transcript())) {
            throw new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPT_TEXT_EDITED",
                    "全文转写已手工编辑，请在全文编辑中核对角色，避免覆盖已修改的文本");
        }
        if (!recordings.updateRole(visit.id(), utteranceId, request.role())) throw BusinessException.notFound();
        List<RecordingMapper.Turn> turns = currentTurns(visit.id());
        UUID snapshotId = recordings.createEditedSnapshot(visit.id(), snapshot, turns,
                DialogueSnapshotHasher.hash(visit.id(), turns), doctorId);
        recordings.saveTranscript(visit.id(), snapshotId, transcriptText(turns), true);
        invalidateClinicalExtraction(visit.id());
        return transcript(visitId, doctorId);
    }

    /**
     * 将当前逐句角色模型应用到已完成的历史转写。
     *
     * <p>模型调用刻意放在数据库事务外，事务性存储会在写入前重新校验所有可变状态。
     */
    public TranscriptVO reclassifyTranscriptRoles(UUID visitId, UUID doctorId) {
        return reclassifyTranscriptRoles(visitId, doctorId, null);
    }

    public TranscriptVO reclassifyTranscriptRoles(UUID visitId, UUID doctorId, String requestedProvider) {
        Visit visit = owned(visitId, doctorId, false);
        requireActive(visit);
        requireRecordEditable(visit.id());
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成转写"));
        if (requestedProvider != null && !requestedProvider.isBlank()
                && LlmRoute.fromRequested(requestedProvider) != LlmRoute.DASHSCOPE) {
            throw BusinessException.conflict("INTERNAL_LLM_UNAVAILABLE", "内网 LLM 未部署，角色识别请使用公网路由");
        }
        LlmRoute route = roleRouter.roleRoute();

        List<Utterance> candidates = currentUtterances(visit.id()).stream()
                .filter(utterance -> !"MANUAL".equals(utterance.roleSource()))
                .toList();
        if (candidates.isEmpty()) return transcript(visitId, doctorId);

        List<Map<String, Object>> inputs = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Utterance utterance = candidates.get(index);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("index", index);
            input.put("speaker_id", utterance.speakerId());
            input.put("start_ms", utterance.startMs());
            input.put("end_ms", utterance.endMs());
            input.put("text", utterance.text());
            inputs.add(input);
        }

        Map<Integer, DashScopeRoleClient.RoleAssignment> assignments = roleRouter.assignRoles(inputs);
        List<RecordingMapper.RoleUpdate> updates = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            DashScopeRoleClient.RoleAssignment assignment = assignments.get(index);
            if (assignment == null) assignment = new DashScopeRoleClient.RoleAssignment("OTHER", null, "FALLBACK");
            updates.add(new RecordingMapper.RoleUpdate(candidates.get(index).id(), assignment.role(),
                    assignment.source(), assignment.confidence(), route.name()));
        }
        roleReclassificationStore.apply(visit.id(), doctorId, updates, route);
        invalidateClinicalExtraction(visit.id());
        return transcript(visitId, doctorId);
    }

    @Transactional
    public MedicalRecordVO generate(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成录音转写"));
        if (recordings.list(visit.id()).isEmpty() || recordings.list(visit.id()).stream()
                .anyMatch(recording -> !"DONE".equals(recording.status()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_NOT_TRANSCRIBED", "请先完成全部录音转写");
        }
        RecordingMapper.TurnState state = recordings.transcript(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED", "请先完成录音转写"));
        if (!snapshot.id().equals(state.snapshotId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SOURCE_CHANGED", "转写已更新，请重新确认当前转写后再生成病历");
        }
        // 病历只能消费医生已确认且哈希匹配的事实，禁止直接把未核对转写交给模型生成。
        Map<String, ClinicalFactVO> facts = extractionService().requireConfirmedFields(visit.id(), snapshot);
        Patient patient = patient(visit);
        MedicalRecordContent content = contentFromExtraction(facts, patient, visit, doctorName(doctorId));
        validateContent(content);
        UUID recordId = records.findRecordId(visit.id()).orElseGet(() -> records.createRecord(UUID.randomUUID(), visit.id()));
        int versionNo = records.latestVersion(recordId) + 1;
        records.insertVersion(recordId, versionNo, snapshot.id(), snapshot.snapshotHash(),
                json(content), doctorName(doctorId), doctorId);
        return medicalRecord(visitId, doctorId);
    }

    @Transactional(readOnly = true)
    public MedicalRecordVO medicalRecord(UUID visitId, UUID doctorId) {
        return medicalRecord(visitId, doctorId, DoctorRole.DOCTOR);
    }

    @Transactional(readOnly = true)
    public MedicalRecordVO medicalRecord(UUID visitId, UUID doctorId, DoctorRole role) {
        Visit visit = readable(visitId, doctorId, role);
        Optional<MedicalRecordVersion> version = records.currentVersion(visit.id());
        if (version.isEmpty()) {
            return new MedicalRecordVO(null, visit.id(), 0, "DRAFT", "PENDING", null, false, null, null, false);
        }
        MedicalRecordContent content = effectiveContent(version.get());
        Optional<MedicalRecordMapper.ConfirmationRow> confirmation = records.confirmations(visit.id()).stream()
                .filter(c -> c.versionNo() == version.get().versionNo()).findFirst();
        RecordingMapper.TurnState state = recordings.transcript(visit.id()).orElse(null);
        boolean dirty = state != null && (!state.snapshotId().equals(version.get().sourceSnapshotId())
                || (state.edited() && state.updatedAt().isAfter(version.get().createdAt())));
        boolean confirmed = "CONFIRMED".equals(recordStatus(visit.id()))
                && confirmation.isPresent();
        return new MedicalRecordVO(version.get().recordId(), visit.id(), version.get().versionNo(),
                recordStatus(visit.id()), version.get().generationStatus(), toVO(content), confirmed,
                confirmation.map(MedicalRecordMapper.ConfirmationRow::confirmedAt).orElse(null),
                confirmation.map(MedicalRecordMapper.ConfirmationRow::doctorName).orElse(null), dirty);
    }

    @Transactional
    public MedicalRecordVO saveDraft(UUID visitId, UUID doctorId, SaveMedicalRecordRequest request) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        MedicalRecordContent current = effectiveContent(version);
        MedicalRecordContent merged = merge(current, request);
        validateContent(merged);
        records.saveDraft(version.recordId(), version.versionNo(), json(merged));
        return medicalRecord(visitId, doctorId);
    }

    @Transactional
    public MedicalRecordVO editConfirmed(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        if (!isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_NOT_CONFIRMED", "当前病历尚未确认");
        }
        MedicalRecordContent content = effectiveContent(version);
        int nextVersion = records.latestVersion(version.recordId()) + 1;
        records.insertVersion(version.recordId(), nextVersion, version.sourceSnapshotId(),
                version.sourceSnapshotHash(), json(content), doctorName(doctorId), doctorId);
        return medicalRecord(visitId, doctorId);
    }

    @Transactional
    public MedicalRecordVO confirm(UUID visitId, UUID doctorId, ConfirmMedicalRecordRequest request,
                                   String clientIp) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "当前病历已确认");
        }
        if (records.confirmationExists(version.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "当前病历版本已确认");
        }
        RecordingMapper.TurnState state = recordings.transcript(visit.id()).orElse(null);
        if (recordings.list(visit.id()).isEmpty() || recordings.list(visit.id()).stream()
                .anyMatch(recording -> !"DONE".equals(recording.status()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_NOT_TRANSCRIBED", "请先完成全部录音转写");
        }
        DialogueSnapshot latestSnapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成录音转写"));
        if (state == null || !latestSnapshot.id().equals(state.snapshotId())
                || !latestSnapshot.id().equals(version.sourceSnapshotId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SOURCE_CHANGED", "录音或转写已更新，请重新生成病历");
        }
        MedicalRecordContent content = effectiveContent(version);
        List<String> missing = missingFields(content);
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECORD_FIELDS_REQUIRED", "请完善：" + String.join("、", missing));
        }
        UUID confirmationId = records.insertConfirmation(version.recordId(), version.id(), doctorId, clientIp);
        records.confirm(version.recordId(), version.versionNo(), doctorId, confirmationId);
        auditMedicalRecordConfirmed(doctorId, visit.id(), version.recordId(), version.versionNo());
        return medicalRecord(visitId, doctorId);
    }

    @Transactional(readOnly = true)
    public List<ConfirmationVO> confirmations(UUID visitId, UUID doctorId) {
        return confirmations(visitId, doctorId, DoctorRole.DOCTOR);
    }

    @Transactional(readOnly = true)
    public List<ConfirmationVO> confirmations(UUID visitId, UUID doctorId, DoctorRole role) {
        Visit visit = readable(visitId, doctorId, role);
        return records.confirmations(visit.id()).stream()
                .map(c -> new ConfirmationVO(c.id(), c.versionNo(), c.doctorName(), c.confirmedAt())).toList();
    }

    @Transactional
    public List<RecordExportVO> recordExport(UUID visitId, UUID doctorId, ExportMedicalRecordRequest request) {
        Visit visit = owned(visitId, doctorId, true);
        requireExportable(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        MedicalRecordMapper.ConfirmedVersion confirmedVersion = records.currentConfirmedVersion(visit.id()).orElse(null);
        if (confirmedVersion == null || !confirmedVersion.versionId().equals(version.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_NOT_CONFIRMED", "请先确认当前病历版本");
        }
        // Unit tests that exercise the retired in-process API do not construct the template service.
        // The running application always resolves a concrete active revision before writing an export job.
        if (exportTemplates == null) return recordExportLegacy(visitId, doctorId, request, visit, version, confirmedVersion);
        var template = exportTemplates.activeRevision(request.templateRevisionId());
        RecordExport reusable = records.reusableExport(version.recordId(), version.id(),
                confirmedVersion.confirmationId(), request.format(), template.revision().id()).orElse(null);
        if (reusable != null) {
            return exports(visitId, doctorId);
        }
        RecordExport failed = records.failedCurrentTemplateExport(version.recordId(), version.id(),
                confirmedVersion.confirmationId(), request.format(), template.revision().id()).orElse(null);
        if (failed != null) {
            records.requeueFailedExport(visit.id(), failed.id(), request.format());
            return exports(visitId, doctorId);
        }
        RecordExport export = records.insertExport(version.recordId(), version.versionNo(), confirmedVersion.confirmationId(),
                version.id(), request.format(), template.template().id(), template.revision().id(), doctorId).orElse(null);
        if (export == null) {
            // A concurrent request inserted the same immutable revision first. Reuse that job after the unique-index race.
            RecordExport concurrent = records.reusableExport(version.recordId(), version.id(),
                    confirmedVersion.confirmationId(), request.format(), template.revision().id()).orElse(null);
            if (concurrent == null) {
                throw new BusinessException(HttpStatus.CONFLICT, "EXPORT_IN_PROGRESS", "导出任务正在创建，请稍后重试");
            }
            return exports(visitId, doctorId);
        }
        records.createExportJob(visit.id(), export.id(), request.format());
        return exports(visitId, doctorId);
    }

    @Transactional(readOnly = true)
    public byte[] previewExport(UUID visitId, UUID doctorId, ExportMedicalRecordRequest request) {
        if (exportTemplates == null || exportFiles == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_PREVIEW_UNAVAILABLE", "导出预览服务不可用");
        }
        Visit visit = owned(visitId, doctorId, false);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        MedicalRecordMapper.ConfirmedVersion confirmed = records.currentConfirmedVersion(visit.id()).orElse(null);
        if (confirmed == null || !confirmed.versionId().equals(version.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_NOT_CONFIRMED", "请先确认当前病历版本");
        }
        var template = exportTemplates.activeRevision(request.templateRevisionId());
        Instant confirmedAt = records.confirmations(visit.id()).stream().findFirst().map(MedicalRecordMapper.ConfirmationRow::confirmedAt)
                .orElse(null);
        try {
            return exportFiles.render(new MedicalRecordMapper.ExportPayload(UUID.randomUUID(), version.recordId(), version.id(),
                    visit.id(), visit.visitNo(), version.versionNo(), request.format(), version.contentJson(), version.editedContentJson(),
                    confirmedAt, template.revision().definitionJson()));
        } catch (java.io.IOException error) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_PREVIEW_FAILED", "病历预览生成失败", error);
        }
    }

    private List<RecordExportVO> recordExportLegacy(UUID visitId, UUID doctorId, ExportMedicalRecordRequest request,
                                                     Visit visit, MedicalRecordVersion version,
                                                     MedicalRecordMapper.ConfirmedVersion confirmedVersion) {
        int templateVersion = MedicalRecordMapper.CURRENT_EXPORT_TEMPLATE_VERSION;
        RecordExport reusable = records.reusableExport(version.recordId(), version.id(),
                confirmedVersion.confirmationId(), request.format()).orElse(null);
        if (reusable != null) return exports(visitId, doctorId);
        RecordExport failed = records.failedCurrentTemplateExport(version.recordId(), version.id(),
                confirmedVersion.confirmationId(), request.format()).orElse(null);
        if (failed != null) {
            records.requeueFailedExport(visit.id(), failed.id(), request.format());
            return exports(visitId, doctorId);
        }
        RecordExport export = records.insertExport(version.recordId(), version.versionNo(), confirmedVersion.confirmationId(),
                version.id(), request.format(), doctorId).orElse(null);
        if (export == null) {
            RecordExport concurrent = records.reusableExport(version.recordId(), version.id(),
                    confirmedVersion.confirmationId(), request.format()).orElse(null);
            if (concurrent == null) throw new BusinessException(HttpStatus.CONFLICT, "EXPORT_IN_PROGRESS", "导出任务正在创建，请稍后重试");
        }
        return exports(visitId, doctorId);
    }

    /**
     * 接收前端模板生成的导出文件，保存到本地归档目录并把导出记录置为已完成。
     */
    @Transactional
    public List<RecordExportVO> uploadExport(UUID visitId, UUID exportId, UUID doctorId, MultipartFile file) {
        if (exportFiles == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_STORAGE_UNAVAILABLE", "导出归档服务不可用");
        }
        Visit visit = owned(visitId, doctorId, true);
        RecordExport export = records.exportsByVisit(visit.id()).stream()
                .filter(item -> item.id().equals(exportId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "导出任务不存在"));
        if (!records.claimExportForUpload(export.id(), visit.id(), doctorId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXPORT_ALREADY_FINISHED", "导出任务已结束，不能重复上传");
        }
        String objectKey = exportFiles.storeUploaded(export.id(), export.format(), file);
        if (!records.markUploadedExportSucceeded(export.id(), visit.id(), doctorId, objectKey)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EXPORT_ALREADY_FINISHED", "导出任务已结束，不能重复上传");
        }
        records.exportAuditContext(export.id()).ifPresent(context -> {
            Runnable writeAudit = () -> auditLogs.recordMedicalRecordExport(context.doctorId(), context.visitId(),
                    export.id(), context.format(), AuditResult.SUCCESS);
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                writeAudit.run();
                            }
                        });
                return;
            }
            writeAudit.run();
        });
        return exports(visitId, doctorId);
    }

    @Transactional(readOnly = true)
    public List<RecordExportVO> exports(UUID visitId, UUID doctorId) {
        return exports(visitId, doctorId, DoctorRole.DOCTOR);
    }

    @Transactional(readOnly = true)
    public List<RecordExportVO> exports(UUID visitId, UUID doctorId, DoctorRole role) {
        Visit visit = readable(visitId, doctorId, role);
        return records.exportsByVisit(visit.id()).stream()
                .map(e -> new RecordExportVO(e.id(), e.versionNo(), e.templateId(), e.templateRevisionId(),
                        e.templateName(), e.templateRevisionNo(), e.format(), e.status(), e.doctorName(), e.createdAt()))
                .toList();
    }

    private List<Map<String, Object>> dialogueForAi(String transcript) {
        List<Map<String, Object>> dialogue = new ArrayList<>();
        for (String line : transcript.split("\\R+")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            String role = value.startsWith("医生：") || value.startsWith("医生:") ? "DOCTOR"
                    : value.startsWith("患者：") || value.startsWith("患者:") ? "PATIENT" : "OTHER";
            String text = value.replaceFirst("^(医生|患者|其他人|未识别角色|说话人 ?[0-9]+)[：:]\\s*", "");
            dialogue.add(Map.of("role", role, "text", text));
        }
        return dialogue;
    }

    private List<RecordingMapper.Turn> currentTurns(UUID visitId) {
        return currentUtterances(visitId).stream()
                .map(utterance -> new RecordingMapper.Turn(utterance.role(), utterance.text(),
                        utterance.startMs(), utterance.endMs(), utterance.speakerId(),
                        utterance.roleSource(), utterance.roleConfidence(), utterance.roleProviderRoute()))
                .toList();
    }

    private List<Utterance> currentUtterances(UUID visitId) {
        return recordings.list(visitId).stream().filter(recording -> "DONE".equals(recording.status()))
                .flatMap(recording -> recordings.listByRecording(recording.id()).stream())
                .toList();
    }

    private String transcriptText(List<RecordingMapper.Turn> turns) {
        return turns.stream().map(turn -> AsrJobStore.roleLabel(turn) + "：" + turn.text())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    private List<RecordingMapper.Turn> editedTurns(String transcript) {
        List<RecordingMapper.Turn> turns = new ArrayList<>();
        long cursor = 0;
        for (String line : transcript.split("\\R+")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            String role = value.startsWith("医生：") || value.startsWith("医生:") ? "DOCTOR" : value.startsWith("患者：") || value.startsWith("患者:") ? "PATIENT" : "OTHER";
            String text = value.replaceFirst("^(医生|患者|其他人|未识别角色|说话人 ?[0-9]+)[：:]\\s*", "").strip();
            if (text.isEmpty()) continue;
            long end = cursor + Math.max(500, text.length() * 120L);
            // 全文编辑是医生对文本及行首角色的人工采纳，保存后无需再沿用旧的 ASR 置信度。
            turns.add(new RecordingMapper.Turn(role, text, cursor, end, null, "MANUAL", null, "MANUAL"));
            cursor = end + 500;
        }
        return turns;
    }

    private Map<String, Object> patientInfo(Patient patient, Visit visit) {
        return Map.of("name", nullSafe(patient.name()), "gender", nullSafe(patient.gender()),
                "age", patient.birthDate() == null ? "" : Period.between(patient.birthDate(), LocalDate.now(clock)).getYears(),
                "phone", nullSafe(patient.phoneMasked()), "visitNo", nullSafe(visit.visitNo()));
    }

    private MedicalRecordContent contentFromAi(Map<String, Object> generated, Patient patient,
                                               Visit visit, String doctorName) {
        String today = LocalDate.now(clock).toString();
        return new MedicalRecordContent(
                patient.name(), patient.gender(),
                patient.birthDate() == null ? null : Period.between(patient.birthDate(), LocalDate.now(clock)).getYears(),
                patient.phoneMasked(), string(generated, "chief"), string(generated, "present"),
                string(generated, "past"), string(generated, "opinion"), string(generated, "medication"),
                string(generated, "followup"), doctorName, today);
    }

    private MedicalRecordContent contentFromExtraction(Map<String, ClinicalFactVO> facts, Patient patient,
                                                       Visit visit, String doctorName) {
        String present = joinFacts(facts, List.of("onset_course", "symptom_characteristics", "associated_symptoms"));
        String past = joinFacts(facts, List.of("past_medical_history", "medication_history", "allergy_history",
                "family_history", "social_history"));
        return new MedicalRecordContent(patient.name(), patient.gender(),
                patient.birthDate() == null ? null : Period.between(patient.birthDate(), LocalDate.now(clock)).getYears(),
                patient.phoneMasked(), factValue(facts, "chief_complaint"), present, past,
                factValue(facts, "doctor_diagnosis"), factValue(facts, "doctor_medication"),
                factValue(facts, "doctor_followup"), doctorName, LocalDate.now(clock).toString());
    }

    private String joinFacts(Map<String, ClinicalFactVO> facts, List<String> keys) {
        return keys.stream().map(key -> factValue(facts, key)).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "；" + right).orElse("");
    }

    private String factValue(Map<String, ClinicalFactVO> facts, String key) {
        ClinicalFactVO fact = facts.get(key);
        return fact == null || fact.value() == null ? "" : fact.value().strip();
    }

    private MedicalRecordContent effectiveContent(MedicalRecordVersion version) {
        try {
            return MedicalRecordContentCodec.parse(objectMapper, version.contentJson(), version.editedContentJson());
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORD_PARSE_FAILED", "病历内容读取失败");
        }
    }

    private MedicalRecordContent merge(MedicalRecordContent current, SaveMedicalRecordRequest request) {
        return new MedicalRecordContent(
                request.name() == null ? current.name() : request.name(),
                request.gender() == null ? current.gender() : request.gender(),
                request.age() == null ? current.age() : request.age(),
                request.phone() == null ? current.phone() : request.phone(),
                request.chief() == null ? current.chief() : request.chief(),
                request.present() == null ? current.present() : request.present(),
                request.past() == null ? current.past() : request.past(),
                request.opinion() == null ? current.opinion() : request.opinion(),
                request.medication() == null ? current.medication() : request.medication(),
                request.followup() == null ? current.followup() : request.followup(),
                request.doctor() == null ? current.doctor() : request.doctor(),
                request.date() == null ? current.date() : request.date());
    }

    private void validateContent(MedicalRecordContent content) {
        List<String> missing = missingFields(content);
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECORD_FIELDS_REQUIRED", "请完善：" + String.join("、", missing));
        }
    }

    private List<String> missingFields(MedicalRecordContent content) {
        List<String> missing = new ArrayList<>();
        if (blank(content.name())) missing.add("患者姓名");
        if (blank(content.gender())) missing.add("性别");
        if (content.age() == null || content.age() < 0 || content.age() > 150) missing.add("有效年龄");
        if (blank(content.phone())) missing.add("联系方式");
        if (blank(content.chief())) missing.add("患者主诉");
        if (blank(content.doctor())) missing.add("接诊医生");
        if (blank(content.date())) missing.add("接诊日期");
        return missing;
    }

    private MedicalRecordContentVO toVO(MedicalRecordContent c) {
        return new MedicalRecordContentVO(c.name(), c.gender(), c.age(), c.phone(), c.chief(), c.present(),
                c.past(), c.opinion(), c.medication(), c.followup(), c.doctor(), c.date());
    }

    private String recordStatus(UUID visitId) {
        return records.status(visitId);
    }

    private boolean isConfirmed(UUID visitId) {
        return "CONFIRMED".equals(recordStatus(visitId));
    }

    private Visit owned(UUID visitId, UUID doctorId, boolean lock) {
        return visits.find(visitId, doctorId, lock).orElseThrow(BusinessException::notFound);
    }

    private Visit readable(UUID visitId, UUID doctorId, DoctorRole role) {
        if (role == DoctorRole.DEPARTMENT_HEAD) {
            return visits.find(visitId, false).orElseThrow(BusinessException::notFound);
        }
        return owned(visitId, doctorId, false);
    }

    private void requireActive(Visit visit) {
        if (!"ACTIVE".equals(visit.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VISIT_NOT_ACTIVE", "请先开始本次接诊");
        }
    }

    private void requireExportable(Visit visit) {
        if (!Set.of("ACTIVE", "COMPLETED").contains(visit.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VISIT_NOT_EXPORTABLE", "当前接诊状态不允许导出病历");
        }
    }

    private void requireRecordEditable(UUID visitId) {
        if (records.findRecordId(visitId).isPresent() && isConfirmed(visitId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
    }

    private Patient patient(Visit visit) {
        return patients.findById(visit.patientId()).orElseThrow(BusinessException::notFound);
    }

    private String doctorName(UUID doctorId) {
        return doctors.findById(doctorId).map(Doctor::displayName).orElse(doctorId.toString());
    }

    private void auditRecordingUploaded(UUID doctorId, UUID visitId, UUID recordingId, String fileName) {
        if (auditLogs != null) {
            auditLogs.recordRecordingUploaded(doctorId, visitId, recordingId, fileName);
        }
    }

    private void auditMedicalRecordConfirmed(UUID doctorId, UUID visitId, UUID recordId, int versionNo) {
        if (auditLogs != null) {
            auditLogs.recordMedicalRecordConfirmed(doctorId, visitId, recordId, versionNo);
        }
    }

    private String nextRecordingNo(UUID visitId) {
        // 录音允许物理删除，不能再用 count+1，否则删除 REC-001 后补传会复用已有 REC-002。
        int max = recordings.list(visitId).stream()
                .map(Recording::recordingNo)
                .filter(Objects::nonNull)
                .mapToInt(value -> {
                    try {
                        String digits = value.startsWith("REC-") ? value.substring(4) : value;
                        return Integer.parseInt(digits);
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                })
                .max().orElse(0);
        return String.format("REC-%03d", max + 1);
    }

    private String queueProvider(String provider) {
        return "DASHSCOPE".equals(provider) ? AsrJobWorker.PUBLIC_ROLE_ROUTE : provider;
    }

    private String displayProvider(String providerRoute) {
        return AsrJobWorker.PUBLIC_ROLE_ROUTE.equals(providerRoute) ? "DASHSCOPE" : providerRoute;
    }

    private String string(Map<String, Object> map, String key) {
        return map == null || map.get(key) == null ? "" : String.valueOf(map.get(key));
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORD_SERIALIZE_FAILED", "病历内容保存失败"); }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String nullSafe(String value) { return value == null ? "" : value; }

    private void invalidateClinicalExtraction(UUID visitId) {
        if (extractions != null) extractions.invalidate(visitId);
    }

    private ClinicalExtractionService extractionService() {
        if (extractions == null) throw new IllegalStateException("结构化提取服务未注入");
        return extractions;
    }
}

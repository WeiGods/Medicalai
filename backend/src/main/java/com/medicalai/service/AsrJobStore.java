package com.medicalai.service;

import com.medicalai.domain.Recording;
import com.medicalai.domain.Utterance;
import com.fasterxml.jackson.databind.JsonNode;
import com.medicalai.mapper.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All writes are fenced by the current lease in a short database transaction. */
@Service
public class AsrJobStore {
    private static final Logger LOG = LoggerFactory.getLogger(AsrJobStore.class);
    private final RecordingMapper recordings;
    private final MedicalRecordMapper records;
    private final VisitMapper visits;
    public AsrJobStore(RecordingMapper recordings, MedicalRecordMapper records, VisitMapper visits) {
        this.recordings=recordings; this.records=records; this.visits=visits;
    }

    public Optional<RecordingMapper.AsrJob> claim(String provider, UUID token) {
        return recordings.claimNextAsrJob(token, provider);
    }
    public boolean renew(UUID jobId, UUID token) { return recordings.renewAsrLease(jobId, token); }

    private void fence(RecordingMapper.AsrJob job, UUID token) {
        recordings.lockVisit(job.visitId());
        if (!recordings.lockAsrLease(job.id(), token)) throw new LeaseLostException();
    }

    @Transactional
    public Recording begin(RecordingMapper.AsrJob job, UUID token) {
        fence(job, token);
        Recording recording = recordings.findByVisitAndStatus(job.visitId(), "UPLOADED")
                .orElseThrow(() -> new IllegalStateException("没有待转写录音"));
        recordings.beginAsrRecording(job.id(), recording.id(), job.providerRoute());
        recordings.updateStatus(recording.id(), "PROCESSING", null);
        return recording;
    }

    @Transactional
    public void submitted(RecordingMapper.AsrJob job, UUID token, UUID recordingId, String taskId) {
        fence(job, token);
        recordings.updateAsrSubmission(job.id(), recordingId, taskId);
    }

    @Transactional
    public void release(RecordingMapper.AsrJob job, UUID token) {
        fence(job, token);
        recordings.releaseAsrJob(job.id());
    }

    @Transactional
    public void fail(RecordingMapper.AsrJob job, UUID token, String message) {
        fence(job, token);
        var current = recordings.asrJob(job.id(), job.visitId()).orElseThrow(LeaseLostException::new);
        if (current.recordingId() != null) recordings.updateStatus(current.recordingId(), "FAILED", message.length() > 512 ? message.substring(0,512) : message);
        recordings.markAsrFailed(job.id(), message);
    }

    @Transactional
    public void complete(RecordingMapper.AsrJob job, UUID token, UUID recordingId, List<RecordingMapper.Turn> turns) {
        complete(job, token, recordingId, turns, null);
    }

    @Transactional
    public void complete(RecordingMapper.AsrJob job, UUID token, UUID recordingId, List<RecordingMapper.Turn> turns,
                         JsonNode rawResponse) {
        /*
         * 此事务只负责持久化，不执行 ASR 或 LLM 网络调用：
         * 1. 校验租约仍归当前 worker；2. 拒绝未完成角色判断的 AUTO/UNKNOWN；
         * 3. 保存脱敏后的 ASR 原始 JSON；4. 保存标准化句段；5. 生成当前对话快照与展示文本。
         */
        fence(job, token);
        var current = recordings.asrJob(job.id(), job.visitId()).orElseThrow(LeaseLostException::new);
        if (!Objects.equals(current.recordingId(), recordingId)) throw new LeaseLostException();
        if (turns.isEmpty() || turns.stream().anyMatch(t -> t.text() == null || t.text().isBlank()
                || t.startMs() < 0 || t.endMs() < t.startMs())) throw new IllegalStateException("ASR 返回无效句段");
        if (turns.stream().anyMatch(turn -> !Set.of("LLM", "FALLBACK", "MANUAL").contains(turn.roleSource()))) {
            throw new IllegalStateException("ASR 角色判断未完成，拒绝以 AUTO 或 UNKNOWN 结果入库");
        }
        String rawJson = AsrRawResponse.sanitizedJson(rawResponse);
        if (rawJson != null) {
            // 原始响应仅用于后台审计；前端始终读取后面的标准化句段和展示文本。
            recordings.saveAsrRawResponse(recordingId, rawJson, AsrRawResponse.sha256(rawJson), turns.size());
        }
        UUID sessionId = recordings.createSession(job.visitId(), recordingId);
        recordings.insertUtterances(job.visitId(), recordingId, sessionId, turns);
        recordings.updateStatus(recordingId, "DONE", null);
        if (recordings.list(job.visitId()).stream().anyMatch(r -> "UPLOADED".equals(r.status()))) {
            recordings.prepareNextAsrRecording(job.id());
            return;
        }
        finalizeVisit(job, recordingId, sessionId);
        recordings.markAsrSucceeded(job.id());
    }

    public static String roleLabel(RecordingMapper.Turn turn) {
        return "DOCTOR".equals(turn.role()) ? "医生" : "PATIENT".equals(turn.role()) ? "患者"
                : turn.speakerId() == null ? "未识别角色" : "说话人 " + turn.speakerId();
    }

    public static class LeaseLostException extends RuntimeException {}

    private void finalizeVisit(RecordingMapper.AsrJob job, UUID recordingId, UUID sessionId) {
        List<RecordingMapper.Turn> turns = new ArrayList<>();
        List<UUID> utteranceIds = new ArrayList<>();
        Recording last = null;
        for (Recording recording : recordings.list(job.visitId())) {
            if (!"DONE".equals(recording.status())) continue;
            last = recording;
            for (Utterance utterance : recordings.listByRecording(recording.id())) {
                turns.add(new RecordingMapper.Turn(utterance.role(), utterance.text(), utterance.startMs(), utterance.endMs(),
                        utterance.speakerId(), utterance.roleSource(), utterance.roleConfidence()));
                utteranceIds.add(utterance.id());
            }
        }
        if (turns.isEmpty() || last == null) throw new IllegalStateException("没有可保存的转写句段");
        String hash = hash(job.visitId(), turns);
        UUID snapshotId = recordings.createSnapshot(job.visitId(), recordingId, sessionId, turns, utteranceIds, hash);
        recordings.saveTranscript(job.visitId(), snapshotId, transcriptText(turns), false);
        records.audit(visits.doctorId(job.visitId()), job.visitId(), "TRANSCRIPT_CREATED", snapshotId);
        LOG.info("ASR转写结果已入库：任务ID={}，接诊ID={}，快照ID={}，句段数={}",
                job.id(), job.visitId(), snapshotId, turns.size());
    }

    private String transcriptText(List<RecordingMapper.Turn> turns) {
        return turns.stream().map(t -> roleLabel(t) + "：" + t.text()).reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private String hash(UUID visitId, List<RecordingMapper.Turn> turns) {
        try {
            String source = visitId + "|" + turns.stream().map(t -> t.role() + ":" + t.speakerId() + ":" + t.text())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("转写快照哈希生成失败", e);
        }
    }

}

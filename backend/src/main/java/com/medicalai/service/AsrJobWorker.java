package com.medicalai.service;

import com.medicalai.domain.Recording;
import com.medicalai.domain.Utterance;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.VisitMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AsrJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AsrJobWorker.class);
    private final RecordingMapper recordings;
    private final AudioStorageService storage;
    private final DashScopeAsrClient dashscope;
    private final AiServiceClient ai;
    private final MedicalRecordMapper records;
    private final VisitMapper visits;
    private final long pollTimeoutMs;

    public AsrJobWorker(RecordingMapper recordings, AudioStorageService storage, DashScopeAsrClient dashscope,
                        MedicalRecordMapper records, VisitMapper visits, AiServiceClient ai,
                        @Value("${medicalai.dashscope.timeout-ms:600000}") long pollTimeoutMs) {
        this.recordings = recordings;
        this.storage = storage;
        this.dashscope = dashscope;
        this.ai = ai;
        this.records = records;
        this.visits = visits;
        this.pollTimeoutMs = Math.max(30_000, pollTimeoutMs);
    }

    @Scheduled(fixedDelayString = "${medicalai.dashscope.poll-delay-ms:3000}")
    @Transactional
    public void processOne() {
        var job = recordings.claimNextAsrJob(UUID.randomUUID());
        if (job.isEmpty()) return;
        RecordingMapper.AsrJob current = job.get();
        try {
            if ("PENDING".equals(current.status())) {
                submitNext(current);
            } else {
                if (current.startedAt() != null && System.currentTimeMillis() - current.startedAt().toEpochMilli() > pollTimeoutMs) {
                    throw new IllegalStateException("DashScope ASR 任务等待超时");
                }
                poll(current);
            }
        } catch (Exception e) {
            String message = safeMessage(e);
            UUID recordingId = current.recordingId();
            if (recordingId == null) {
                recordingId = recordings.asrJob(current.id(), current.visitId())
                        .map(RecordingMapper.AsrJob::recordingId).orElse(null);
            }
            LOG.error("ASR job failed: jobId={}, visitId={}, recordingId={}, message={}",
                    current.id(), current.visitId(), recordingId, message, e);
            if (recordingId != null) recordings.updateStatus(recordingId, "FAILED", message);
            recordings.markAsrFailed(current.id(), message);
        }
    }

    private void submitNext(RecordingMapper.AsrJob job) {
        Recording recording = recordings.findByVisitAndStatus(job.visitId(), "UPLOADED")
                .orElseThrow(() -> new IllegalStateException("没有待转写录音"));
        if (recording.objectKey() == null || recording.objectKey().isBlank()) {
            throw new IllegalStateException("录音对象不存在");
        }
        recordings.bindAsrRecording(job.id(), recording.id());
        recordings.updateStatus(recording.id(), "PROCESSING", null);
        String url = storage.presignedUrl(recording.objectKey());
        String taskId = dashscope.submit(url);
        recordings.updateAsrSubmission(job.id(), recording.id(), taskId);
        LOG.info("ASR task submitted: jobId={}, visitId={}, recordingId={}, taskId={}",
                job.id(), job.visitId(), recording.id(), taskId);
    }

    private void poll(RecordingMapper.AsrJob job) {
        if (job.providerTaskId() == null || job.providerTaskId().isBlank() || job.recordingId() == null) {
            throw new IllegalStateException("ASR 任务缺少 provider task id");
        }
        DashScopeAsrClient.Task task = dashscope.query(job.providerTaskId());
        if ("PENDING".equals(task.status()) || "RUNNING".equals(task.status())) {
            recordings.releaseAsrJob(job.id());
            return;
        }
        if (!"SUCCEEDED".equals(task.status())) {
            throw new IllegalStateException("DashScope 任务状态：" + task.status());
        }
        List<DashScopeAsrClient.Segment> segments = dashscope.result(task);
        if (segments.isEmpty()) throw new IllegalStateException("DashScope 返回空转写结果");

        UUID sessionId = recordings.createSession(job.visitId(), job.recordingId());
        List<RecordingMapper.Turn> rawTurns = segments.stream()
                .map(segment -> new RecordingMapper.Turn("OTHER", segment.text(), segment.startMs(), segment.endMs(), segment.speakerId())).toList();
        var roleInput = rawTurns.stream().map(t -> java.util.Map.<String,Object>of("speaker_id", t.speakerId() == null ? "unknown" : t.speakerId(), "text", t.text())).toList();
        var roles = ai.assignRoles(roleInput);
        List<RecordingMapper.Turn> turns = rawTurns.stream().map(t -> new RecordingMapper.Turn(
                t.speakerId() == null ? "OTHER" : roles.getOrDefault(t.speakerId(), "OTHER"),
                t.text(), t.startMs(), t.endMs(), t.speakerId())).toList();
        recordings.insertUtterances(job.visitId(), job.recordingId(), sessionId, turns);
        recordings.updateStatus(job.recordingId(), "DONE", null);

        boolean pending = recordings.list(job.visitId()).stream().anyMatch(r -> "UPLOADED".equals(r.status()));
        if (pending) {
            recordings.prepareNextAsrRecording(job.id());
            return;
        }
        finalizeVisit(job, job.recordingId(), sessionId);
        recordings.markAsrSucceeded(job.id());
    }

    private void finalizeVisit(RecordingMapper.AsrJob job, UUID recordingId, UUID sessionId) {
        List<RecordingMapper.Turn> turns = new ArrayList<>();
        List<UUID> utteranceIds = new ArrayList<>();
        Recording last = null;
        for (Recording recording : recordings.list(job.visitId())) {
            if (!"DONE".equals(recording.status())) continue;
            last = recording;
            for (Utterance utterance : recordings.listByRecording(recording.id())) {
                turns.add(new RecordingMapper.Turn(utterance.role(), utterance.text(), utterance.startMs(), utterance.endMs()));
                utteranceIds.add(utterance.id());
            }
        }
        if (turns.isEmpty() || last == null) throw new IllegalStateException("没有可保存的转写句段");
        String hash = hash(job.visitId(), turns);
        UUID snapshotId = recordings.createSnapshot(job.visitId(), last.id(), sessionId, turns, utteranceIds, hash);
        recordings.saveTranscript(job.visitId(), snapshotId, transcriptText(turns), false);
        records.audit(visits.doctorId(job.visitId()), job.visitId(), "TRANSCRIPT_CREATED", snapshotId);
        LOG.info("ASR transcription completed: jobId={}, visitId={}, snapshotId={}, utterances={}",
                job.id(), job.visitId(), snapshotId, turns.size());
    }

    private String transcriptText(List<RecordingMapper.Turn> turns) {
        return turns.stream().map(t -> ("DOCTOR".equals(t.role()) ? "医生" : "PATIENT".equals(t.role()) ? "患者" : "其他人") + "：" + t.text()).reduce((a, b) -> a + "\n\n" + b).orElse("");
    }

    private String hash(UUID visitId, List<RecordingMapper.Turn> turns) {
        try {
            String source = visitId + "|" + turns.stream().map(t -> t.role() + ":" + t.text())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("转写快照哈希生成失败", e);
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}

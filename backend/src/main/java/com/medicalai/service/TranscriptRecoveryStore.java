package com.medicalai.service;

import com.medicalai.domain.Recording;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only entry point that clears unsigned mixed/unknown encounter derivatives. Audio is retained. */
@Service
public class TranscriptRecoveryStore {
    private final VisitMapper visits;
    private final RecordingMapper recordings;
    private final MedicalRecordMapper records;

    public TranscriptRecoveryStore(VisitMapper visits, RecordingMapper recordings, MedicalRecordMapper records) {
        this.visits = visits; this.recordings = recordings; this.records = records;
    }

    public record Prepared(String route, List<Recording> audio, RecordingMapper.AsrJob existing) {
        public Prepared { audio = List.copyOf(audio); }
    }

    @Transactional
    public Prepared prepare(UUID visitId, UUID doctorId, String provider, UUID snapshotId, boolean acknowledged) {
        var visit = visits.find(visitId, doctorId, true).orElseThrow(BusinessException::notFound);
        if (!"ACTIVE".equals(visit.status())) throw BusinessException.conflict("VISIT_NOT_ACTIVE", "请先开始本次接诊");
        if (!acknowledged) throw BusinessException.conflict("REBUILD_CONFIRMATION_REQUIRED", "请确认重新核对转写、信息提取和病历草稿");
        LlmRoute target = LlmRoute.fromAsrRoute(provider);
        if (!target.isCallable() || snapshotId == null) throw BusinessException.conflict("REBUILD_SOURCE_REQUIRED", "请选择统一 ASR 路由并提供原快照标识");
        if (records.hasAnyConfirmation(visitId) || "CONFIRMED".equals(records.status(visitId)))
            throw BusinessException.conflict("RECORD_CONFIRMED", "本次接诊有签署历史，不能清理转写及病历数据");
        var existing = recordings.rebuildJob(visitId, snapshotId);
        if (existing.isPresent()) {
            if (LlmRoute.fromAsrRoute(existing.get().providerRoute()) != target)
                throw BusinessException.conflict("ASR_ROUTE_MISMATCH", "恢复任务已使用另一条路由");
            return new Prepared(target.name(), List.of(), existing.get());
        }
        if (recordings.hasActiveAsrJob(visitId) || visits.hasOpenSession(visitId))
            throw BusinessException.conflict("RECORDING_PROCESSING", "请先结束当前 ASR 任务和录音操作");
        var snapshot = recordings.latestSnapshot(visitId).orElseThrow(() -> BusinessException.conflict("SNAPSHOT_REQUIRED", "缺少待恢复快照"));
        if (!snapshot.id().equals(snapshotId)) throw BusinessException.conflict("SOURCE_CHANGED", "当前快照已变化，请刷新后重新确认恢复");
        LlmRoute source = new LlmRouteResolver(recordings).routing(snapshot).sourceRoute();
        if (source.isCallable() && !"UNKNOWN".equals(visits.historicalProcessingRoute(visitId)))
            throw BusinessException.conflict("REBUILD_NOT_REQUIRED", "当前接诊来源明确，请使用普通转写或重转写");
        String pinned = visit.processingRoute() != null ? visit.processingRoute() : visit.recoveryRoute();
        if (pinned != null && LlmRoute.fromAsrRoute(pinned) != target)
            throw BusinessException.conflict("ASR_ROUTE_MISMATCH", "恢复必须沿用接诊已锁定的路由");
        List<Recording> audio = recordings.list(visitId);
        if (audio.isEmpty() || audio.stream().anyMatch(r -> r.objectKey() == null || r.objectKey().isBlank()))
            throw BusinessException.conflict("AUDIO_NOT_FOUND", "原始录音不完整，无法整份恢复");
        return new Prepared(target.name(), audio, null);
    }

    @Transactional
    public UUID rebuild(UUID visitId, UUID doctorId, UUID snapshotId, Prepared expected) {
        // Recheck after object-storage I/O. No destructive writes occur before all gates pass.
        Prepared current = prepare(visitId, doctorId, expected.route(), snapshotId, true);
        if (current.existing() != null) return current.existing().id();
        if (!current.audio().equals(expected.audio())) throw BusinessException.conflict("SOURCE_CHANGED", "录音已变化，请刷新后重新确认恢复");
        recordings.purgeVisitDerivedData(visitId);
        recordings.requeueRemainingRecordings(visitId);
        recordings.resetRecordingRoutes(visitId);
        visits.setRecoveryRoute(visitId, expected.route());
        return recordings.createRebuildJob(visitId, snapshotId,
                "DASHSCOPE".equals(expected.route()) ? AsrJobWorker.PUBLIC_ROLE_ROUTE : expected.route());
    }
}

package com.medicalai.service;

import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.domain.Utterance;
import com.medicalai.domain.Visit;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes LLM role results atomically after the network call has already completed. */
@Service
public class TranscriptRoleReclassificationStore {
    private final VisitMapper visits;
    private final RecordingMapper recordings;
    private final MedicalRecordMapper records;

    public TranscriptRoleReclassificationStore(VisitMapper visits, RecordingMapper recordings, MedicalRecordMapper records) {
        this.visits = visits;
        this.recordings = recordings;
        this.records = records;
    }

    @Transactional
    public void apply(UUID visitId, UUID doctorId, List<RecordingMapper.RoleUpdate> updates) {
        Visit visit = visits.find(visitId, doctorId, true).orElseThrow(BusinessException::notFound);
        if (!"ACTIVE".equals(visit.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VISIT_NOT_ACTIVE", "请先开始本次接诊");
        }
        if (records.findRecordId(visitId).isPresent() && "CONFIRMED".equals(records.status(visitId))) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        DialogueSnapshot snapshot = recordings.latestSnapshot(visitId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成转写"));
        List<RecordingMapper.Turn> before = currentTurns(visitId);
        RecordingMapper.TurnState state = recordings.transcript(visitId).orElse(null);
        if (state != null && state.edited() && !transcriptText(before).equals(state.transcript())) {
            throw new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPT_TEXT_EDITED",
                    "全文转写已手工编辑，请在全文编辑中核对角色，避免覆盖已修改的文本");
        }
        if (recordings.updateRoleAssignments(visitId, updates) != updates.size()) {
            throw new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPT_CHANGED", "转写已被修改，请刷新后重试");
        }
        List<RecordingMapper.Turn> turns = currentTurns(visitId);
        UUID snapshotId = recordings.createEditedSnapshot(visitId, snapshot, turns, hash(visitId, turns), doctorId);
        recordings.saveTranscript(visitId, snapshotId, transcriptText(turns), state != null && state.edited());
        records.audit(doctorId, visitId, "TRANSCRIPT_ROLES_RECLASSIFIED", snapshotId);
    }

    private List<RecordingMapper.Turn> currentTurns(UUID visitId) {
        return recordings.list(visitId).stream().filter(recording -> "DONE".equals(recording.status()))
                .flatMap(recording -> recordings.listByRecording(recording.id()).stream())
                .map(this::turn).toList();
    }

    private RecordingMapper.Turn turn(Utterance utterance) {
        return new RecordingMapper.Turn(utterance.role(), utterance.text(), utterance.startMs(), utterance.endMs(),
                utterance.speakerId(), utterance.roleSource(), utterance.roleConfidence());
    }

    private String transcriptText(List<RecordingMapper.Turn> turns) {
        return turns.stream().map(turn -> AsrJobStore.roleLabel(turn) + "：" + turn.text())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

    private String hash(UUID visitId, List<RecordingMapper.Turn> turns) {
        try {
            String content = visitId + "|" + turns.stream().map(turn -> turn.role() + ":" + turn.text())
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "HASH_FAILED", "快照生成失败");
        }
    }
}

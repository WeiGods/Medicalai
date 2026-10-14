package com.medicalai.service;

import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.domain.Utterance;
import com.medicalai.domain.Visit;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.ClinicalExtractionMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.mapper.VisitMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在网络调用完成后，以原子方式写入 LLM 角色判断结果。 */
@Service
public class TranscriptRoleReclassificationStore {
    private final VisitMapper visits;
    private final RecordingMapper recordings;
    private final MedicalRecordMapper records;
    private final ClinicalExtractionMapper extractions;

    @org.springframework.beans.factory.annotation.Autowired
    public TranscriptRoleReclassificationStore(VisitMapper visits, RecordingMapper recordings, MedicalRecordMapper records,
                                               ClinicalExtractionMapper extractions) {
        this.visits = visits;
        this.recordings = recordings;
        this.records = records;
        this.extractions = extractions;
    }

    public TranscriptRoleReclassificationStore(VisitMapper visits, RecordingMapper recordings, MedicalRecordMapper records) {
        this(visits, recordings, records, null);
    }

    @Transactional
    public void apply(UUID visitId, UUID doctorId, List<RecordingMapper.RoleUpdate> updates) {
        apply(visitId, doctorId, updates, LlmRoute.UNKNOWN);
    }

    /**
     * 将一次角色重判及其实际模型路由原子写入。
     * 路由与快照哈希一起冻结，后续提取才能追溯本段角色是由公网、内网还是医生确认得到。
     */
    @Transactional
    public void apply(UUID visitId, UUID doctorId, List<RecordingMapper.RoleUpdate> updates, LlmRoute route) {
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
        UUID snapshotId = recordings.createEditedSnapshot(visitId, snapshot, turns,
                DialogueSnapshotHasher.hash(visitId, turns), doctorId);
        recordings.saveTranscript(visitId, snapshotId, transcriptText(turns), state != null && state.edited());
        if (extractions != null) extractions.markCurrentStale(visitId);
    }

    private List<RecordingMapper.Turn> currentTurns(UUID visitId) {
        return recordings.list(visitId).stream().filter(recording -> "DONE".equals(recording.status()))
                .flatMap(recording -> recordings.listByRecording(recording.id()).stream())
                .map(this::turn).toList();
    }

    private RecordingMapper.Turn turn(Utterance utterance) {
        return new RecordingMapper.Turn(utterance.role(), utterance.text(), utterance.startMs(), utterance.endMs(),
                utterance.speakerId(), utterance.roleSource(), utterance.roleConfidence(), utterance.roleProviderRoute());
    }

    private String transcriptText(List<RecordingMapper.Turn> turns) {
        return turns.stream().map(turn -> AsrJobStore.roleLabel(turn) + "：" + turn.text())
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
    }

}

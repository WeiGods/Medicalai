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
import java.util.Objects;
import java.util.Set;
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

    public record Prepared(DialogueSnapshot snapshot, LlmRoute route, List<Utterance> utterances,
                           RecordingMapper.TurnState transcript) {
        public Prepared { utterances = List.copyOf(utterances); }
        public List<Utterance> candidates() {
            return utterances.stream().filter(u -> !"MANUAL".equals(u.roleSource())).toList();
        }
    }

    @Transactional
    public Prepared prepare(UUID visitId, UUID doctorId, String provider) {
        requireEditable(visitId, doctorId);
        DialogueSnapshot snapshot = recordings.latestSnapshot(visitId)
                .orElseThrow(() -> BusinessException.conflict("SNAPSHOT_REQUIRED", "请先完成转写"));
        LlmRoute route = new LlmRouteResolver(recordings).resolve(snapshot, provider);
        List<Utterance> utterances = currentUtterances(visitId);
        List<RecordingMapper.Turn> turns = utterances.stream().map(this::turn).toList();
        RecordingMapper.TurnState state = recordings.transcript(visitId).orElse(null);
        if (state != null && state.edited() && !transcriptText(turns).equals(state.transcript())) {
            throw BusinessException.conflict("TRANSCRIPT_TEXT_EDITED", "全文转写已手工编辑，请在全文编辑中核对角色，避免覆盖已修改的文本");
        }
        if (state == null || !snapshot.id().equals(state.snapshotId())
                || !snapshot.snapshotHash().equals(DialogueSnapshotHasher.hash(visitId, turns))) {
            throw BusinessException.conflict("SOURCE_CHANGED", "快照与当前句段不一致，请刷新并重新核对转写");
        }
        return new Prepared(snapshot, route, utterances, state);
    }

    /** Only a context frozen before inference may be used to write model results. */
    @Transactional
    public void apply(UUID visitId, UUID doctorId, Prepared expected, List<RecordingMapper.RoleUpdate> updates) {
        requireEditable(visitId, doctorId);
        var latest = recordings.latestSnapshot(visitId);
        if (latest.isEmpty() || !expected.snapshot().id().equals(latest.get().id())
                || expected.snapshot().snapshotVersion() != latest.get().snapshotVersion()
                || !expected.snapshot().snapshotHash().equals(latest.get().snapshotHash())) {
            throw BusinessException.conflict("SOURCE_CHANGED", "当前转写快照已变化，已丢弃旧角色结果");
        }
        if (new LlmRouteResolver(recordings).routing(latest.get()).sourceRoute() != expected.route()
                || !Objects.equals(expected.transcript(), recordings.transcript(visitId).orElse(null))
                || !expected.utterances().equals(currentUtterances(visitId))) {
            throw BusinessException.conflict("SOURCE_CHANGED", "模型调用期间转写已变化，已丢弃旧结果，请刷新后重试");
        }
        Set<UUID> candidateIds = expected.candidates().stream().map(Utterance::id).collect(java.util.stream.Collectors.toSet());
        if (updates.size() != candidateIds.size()
                || !candidateIds.equals(updates.stream().map(RecordingMapper.RoleUpdate::utteranceId).collect(java.util.stream.Collectors.toSet()))
                || updates.stream().anyMatch(u -> !Set.of("LLM", "FALLBACK").contains(u.source())
                    || !Set.of("DOCTOR", "PATIENT", "OTHER").contains(u.role())
                    || LlmRoute.fromAsrRoute(u.providerRoute()) != expected.route())) {
            throw BusinessException.conflict("TRANSCRIPT_CHANGED", "角色结果与本次冻结输入不一致");
        }
        if (recordings.updateRoleAssignments(visitId, updates) != updates.size()) {
            throw BusinessException.conflict("TRANSCRIPT_CHANGED", "转写已被修改，请刷新后重试");
        }
        List<RecordingMapper.Turn> turns = currentTurns(visitId);
        UUID snapshotId = recordings.createEditedSnapshot(visitId, expected.snapshot(), turns,
                DialogueSnapshotHasher.hash(visitId, turns), doctorId);
        recordings.saveTranscript(visitId, snapshotId, transcriptText(turns), expected.transcript().edited());
        if (extractions != null) extractions.markCurrentStale(visitId);
    }

    private void requireEditable(UUID visitId, UUID doctorId) {
        Visit visit = visits.find(visitId, doctorId, true).orElseThrow(BusinessException::notFound);
        if (!"ACTIVE".equals(visit.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VISIT_NOT_ACTIVE", "请先开始本次接诊");
        }
        if (records.findRecordId(visitId).isPresent() && "CONFIRMED".equals(records.status(visitId))) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        if (visits.hasIncompleteRecording(visitId) || visits.hasOpenSession(visitId) || recordings.hasActiveAsrJob(visitId)) {
            throw BusinessException.conflict("RECORDING_PROCESSING", "请先完成全部录音转写再重判角色");
        }
    }

    private List<Utterance> currentUtterances(UUID visitId) {
        return recordings.list(visitId).stream().filter(r -> "DONE".equals(r.status()))
                .flatMap(r -> recordings.listByRecording(r.id()).stream()).toList();
    }

    private List<RecordingMapper.Turn> currentTurns(UUID visitId) {
        return currentUtterances(visitId).stream().map(this::turn).toList();
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

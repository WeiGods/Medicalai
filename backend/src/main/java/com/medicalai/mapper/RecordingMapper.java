package com.medicalai.mapper;

import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.domain.Recording;
import com.medicalai.domain.Utterance;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RecordingMapper {
    private static final RowMapper<Recording> RECORDING = (rs, n) -> new Recording(
            rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
            rs.getString("recording_no"), rs.getString("source_type"), rs.getString("object_key"),
            rs.getString("file_name"), rs.getString("mime_type"), rs.getObject("size_bytes", Long.class),
            rs.getObject("duration_ms", Long.class), rs.getString("status"), rs.getString("error_message"),
            DatabaseDateTime.getInstant(rs, "created_at"));
    private static final RowMapper<Utterance> UTTERANCE = (rs, n) -> new Utterance(
            rs.getObject("id", UUID.class), rs.getObject("recording_id", UUID.class),
            rs.getObject("session_id", UUID.class), rs.getString("utterance_id"), rs.getInt("revision"),
            rs.getString("result_type"), rs.getString("text"), rs.getString("role"),
            rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getBoolean("is_current"),
            DatabaseDateTime.getInstant(rs, "created_at"), rs.getObject("speaker_id", Integer.class),
            rs.getString("role_source"), rs.getObject("role_confidence", Integer.class),
            rs.getString("role_provider_route"));

    private final JdbcTemplate jdbc;
    public RecordingMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Recording> list(UUID visitId) {
        return jdbc.query("SELECT * FROM recording WHERE visit_id=? ORDER BY created_at,recording_no", RECORDING, visitId);
    }

    public Optional<Recording> find(UUID id, UUID doctorId) {
        return jdbc.query("""
                SELECT r.* FROM recording r JOIN visit v ON v.id=r.visit_id
                WHERE r.id=? AND v.doctor_id=?
                """, RECORDING, id, doctorId).stream().findFirst();
    }

    public int count(UUID visitId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM recording WHERE visit_id=?", Integer.class, visitId);
        return n == null ? 0 : n;
    }

    public Recording insert(Recording r) {
        return jdbc.queryForObject("""
                INSERT INTO recording(id,visit_id,recording_no,source_type,object_key,file_name,mime_type,size_bytes,duration_ms,status,asr_route)
                VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """, RECORDING, r.id(), r.visitId(), r.recordingNo(), r.sourceType(), r.objectKey(),
                r.fileName(), r.mimeType(), r.sizeBytes(), r.durationMs(), r.status(), "DASHSCOPE");
    }

    public int delete(UUID id) {
        return jdbc.update("DELETE FROM recording WHERE id=? AND status IN ('UPLOADED','FAILED','DONE')", id);
    }

    /**
     * 将已经完成的录音重新放回 ASR 队列。原始音频对象不变，只清理该录音产生的
     * 句段和快照来源映射，新的任务完成后会重新汇总本次接诊的全部 DONE 录音。
     */
    public boolean requeueTranscribedRecording(UUID recordingId) {
        int exists = jdbc.update("""
                DELETE FROM dialogue_snapshot_source
                WHERE utterance_id IN (SELECT id FROM asr_utterance WHERE recording_id=?)
                """, recordingId);
        jdbc.update("DELETE FROM asr_utterance WHERE recording_id=?", recordingId);
        return jdbc.update("""
                UPDATE recording SET status='UPLOADED',error_code=NULL,error_message=NULL,
                    asr_raw_response=NULL,asr_raw_response_hash=NULL,asr_segment_count=NULL,
                    updated_at=medicalai_local_now()
                WHERE id=? AND status='DONE'
                """, recordingId) == 1;
    }

    /** 清除本次接诊所有尚未签署的转写/提取/病历草稿派生数据。 */
    public void purgeVisitDerivedData(UUID visitId) {
        jdbc.update("DELETE FROM dialogue_snapshot_source WHERE snapshot_id IN (SELECT id FROM dialogue_snapshot WHERE visit_id=?)", visitId);
        jdbc.update("DELETE FROM clinical_extraction_version WHERE extraction_id IN (SELECT id FROM clinical_extraction WHERE visit_id=?)", visitId);
        jdbc.update("DELETE FROM clinical_extraction WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM visit_transcript WHERE visit_id=?", visitId);
        // 导出任务通过 result_ref 指向 record_export，不能在删除导出行后再回溯清理。
        jdbc.update("DELETE FROM ai_job WHERE result_ref IN (SELECT e.id FROM record_export e JOIN medical_record r ON r.id=e.record_id WHERE r.visit_id=?)", visitId);
        jdbc.update("DELETE FROM record_export WHERE record_id IN (SELECT id FROM medical_record WHERE visit_id=?)", visitId);
        jdbc.update("DELETE FROM medical_record_confirmation WHERE record_id IN (SELECT id FROM medical_record WHERE visit_id=?)", visitId);
        jdbc.update("DELETE FROM medical_record_version WHERE record_id IN (SELECT id FROM medical_record WHERE visit_id=?)", visitId);
        jdbc.update("DELETE FROM medical_record WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM dialogue_snapshot WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM asr_utterance WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM recording_session WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM ai_job WHERE visit_id=? AND job_type='ASR_TRANSCRIBE'", visitId);
    }

    /** 删除一段录音后，剩余音频必须重新合并转写。 */
    public void requeueRemainingRecordings(UUID visitId) {
        jdbc.update("""
                UPDATE recording SET status='UPLOADED',error_code=NULL,error_message=NULL,
                    asr_raw_response=NULL,asr_raw_response_hash=NULL,asr_segment_count=NULL,
                    updated_at=medicalai_local_now()
                WHERE visit_id=?
                """, visitId);
    }

    public boolean hasActiveAsrJob(UUID visitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ai_job WHERE visit_id=? AND job_type='ASR_TRANSCRIBE'
                              AND status IN ('PENDING','RUNNING'))
                """, Boolean.class, visitId));
    }

    /** 剥离转写任务对录音的外键引用，允许删除转写失败的录音。 */
    public void detachAsrJobs(UUID recordingId) {
        jdbc.update("UPDATE ai_job SET recording_id=NULL WHERE recording_id=? AND job_type='ASR_TRANSCRIBE'", recordingId);
    }

    public Recording updateStatus(UUID id, String status, String error) {
        return jdbc.queryForObject("""
                UPDATE recording SET status=?,error_code=CASE WHEN ?::text IS NULL THEN NULL ELSE 'ASR_FAILED' END,
                  error_message=?,updated_at=medicalai_local_now() WHERE id=? RETURNING *
                """, RECORDING, status, error, error, id);
    }

    public void saveAsrRawResponse(UUID recordingId, String rawJson, String rawHash, int segmentCount) {
        jdbc.update("""
                UPDATE recording SET asr_raw_response=?::jsonb,asr_raw_response_hash=?,asr_segment_count=?,
                    updated_at=medicalai_local_now() WHERE id=?
                """, rawJson, rawHash, segmentCount, recordingId);
    }

    public Optional<Recording> findByVisitAndStatus(UUID visitId, String status) {
        return jdbc.query("SELECT * FROM recording WHERE visit_id=? AND status=? ORDER BY created_at LIMIT 1",
                RECORDING, visitId, status).stream().findFirst();
    }

    public UUID createAsrJob(UUID visitId, String provider) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_job(id,job_type,visit_id,idempotency_key,status,provider_route)
                VALUES (?,?,?,?, 'PENDING',?)
                """, id, "ASR_TRANSCRIBE", visitId, "asr:" + visitId + ":" + id, provider);
        return id;
    }

    /** 创建任务时可绑定指定录音；补充重转写不能被其它待转写录音抢占。 */
    public UUID createAsrJob(UUID visitId, String provider, UUID recordingId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_job(id,job_type,visit_id,recording_id,idempotency_key,status,provider_route)
                VALUES (?,?,?,?,?,'PENDING',?)
                """, id, "ASR_TRANSCRIBE", visitId, recordingId, "asr:" + visitId + ":" + id, provider);
        return id;
    }

    public Optional<Recording> findByVisitAndIdAndStatus(UUID visitId, UUID recordingId, String status) {
        return jdbc.query("SELECT * FROM recording WHERE visit_id=? AND id=? AND status=?",
                RECORDING, visitId, recordingId, status).stream().findFirst();
    }

    public Optional<AsrJob> claimNextAsrJob(UUID leaseToken, String provider) {
        List<AsrJob> jobs = jdbc.query("""
                WITH candidate AS (
                    SELECT j.id
                    FROM ai_job j
                    WHERE j.job_type='ASR_TRANSCRIBE'
                      AND j.status IN ('PENDING','RUNNING')
                      AND COALESCE(j.provider_route,'DASHSCOPE')=?
                      AND (j.locked_at IS NULL OR j.locked_at < medicalai_local_clock() - interval '2 minutes')
                    ORDER BY j.created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE ai_job j
                SET locked_at=medicalai_local_clock(), lease_token=?
                FROM candidate c
                WHERE j.id=c.id
                RETURNING j.id,j.visit_id,j.recording_id,j.provider_task_id,j.status,j.attempt_count,j.last_error,j.started_at,j.provider_route
                """, (rs, n) -> new AsrJob(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                        rs.getObject("recording_id", UUID.class), rs.getString("provider_task_id"),
                        rs.getString("status"), rs.getInt("attempt_count"), rs.getString("last_error"),
                        DatabaseDateTime.getInstant(rs, "started_at"),
                        rs.getString("provider_route") == null ? "DASHSCOPE" : rs.getString("provider_route")), provider, leaseToken);
        return jobs.stream().findFirst();
    }

    /** 在短事务内、变更任务或结果之前调用。 */
    public boolean lockAsrLease(UUID jobId, UUID token) {
        return !jdbc.queryForList("""
                SELECT id FROM ai_job WHERE id=? AND lease_token=?
                  AND job_type='ASR_TRANSCRIBE' AND status IN ('PENDING','RUNNING')
                  AND locked_at > medicalai_local_clock() - interval '2 minutes'
                FOR UPDATE
                """, jobId, token).isEmpty();
    }

    public boolean renewAsrLease(UUID jobId, UUID token) {
        return jdbc.update("""
                UPDATE ai_job SET locked_at=medicalai_local_clock()
                WHERE id=? AND lease_token=? AND job_type='ASR_TRANSCRIBE'
                  AND status IN ('PENDING','RUNNING')
                  AND locked_at > medicalai_local_clock() - interval '2 minutes'
                """, jobId, token) == 1;
    }

    public void lockVisit(UUID visitId) {
        jdbc.queryForList("SELECT id FROM visit WHERE id=? FOR UPDATE", visitId);
    }

    // 以下变更方法必须与 lockAsrLease 处于同一事务；持有行锁可阻止新租约持有者在提交前写入。
    public void beginAsrRecording(UUID jobId, UUID recordingId, String provider) {
        jdbc.update("""
                UPDATE ai_job SET status='RUNNING',recording_id=?,started_at=medicalai_local_now(),
                    attempt_count=attempt_count+1,last_error=NULL WHERE id=?
                """, recordingId, jobId);
        jdbc.update("UPDATE recording SET asr_route=? WHERE id=?", provider, recordingId);
    }

    public void updateAsrSubmission(UUID jobId, UUID recordingId, String providerTaskId) {
        jdbc.update("""
                UPDATE ai_job SET provider_task_id=?,locked_at=NULL,lease_token=NULL
                WHERE id=? AND recording_id=? AND job_type='ASR_TRANSCRIBE'
                """, providerTaskId, jobId, recordingId);
    }

    /** 使失败任务及修复前遗留的孤立 PROCESSING 记录能够显式重试。 */
    public void requeueRetryableRecordings(UUID visitId) {
        jdbc.update("""
                UPDATE recording SET status='UPLOADED',error_code=NULL,error_message=NULL,updated_at=medicalai_local_now()
                WHERE visit_id=?
                  AND (status='FAILED' OR (
                    status='PROCESSING' AND NOT EXISTS (
                      SELECT 1 FROM ai_job
                      WHERE visit_id=? AND job_type='ASR_TRANSCRIBE' AND status IN ('PENDING','RUNNING')
                    )
                  ))
                """, visitId, visitId);
    }

    public void prepareNextAsrRecording(UUID jobId) {
        jdbc.update("""
                UPDATE ai_job SET status='PENDING',recording_id=NULL,provider_task_id=NULL,
                    locked_at=NULL,lease_token=NULL WHERE id=? AND job_type='ASR_TRANSCRIBE'
                """, jobId);
    }

    public void releaseAsrJob(UUID jobId) {
        jdbc.update("UPDATE ai_job SET locked_at=NULL,lease_token=NULL WHERE id=? AND job_type='ASR_TRANSCRIBE'", jobId);
    }

    public void markAsrSucceeded(UUID jobId) {
        jdbc.update("""
                UPDATE ai_job SET status='SUCCEEDED',finished_at=medicalai_local_now(),locked_at=NULL,lease_token=NULL,last_error=NULL
                WHERE id=? AND job_type='ASR_TRANSCRIBE'
                """, jobId);
    }

    public void markAsrFailed(UUID jobId, String error) {
        jdbc.update("""
                UPDATE ai_job SET status='FAILED',finished_at=medicalai_local_now(),locked_at=NULL,lease_token=NULL,last_error=?
                WHERE id=? AND job_type='ASR_TRANSCRIBE'
                """, error, jobId);
    }

    public Optional<AsrJob> asrJob(UUID jobId, UUID visitId) {
        return jdbc.query("""
                SELECT id,visit_id,recording_id,provider_task_id,status,attempt_count,last_error,started_at,provider_route
                FROM ai_job WHERE id=? AND visit_id=? AND job_type='ASR_TRANSCRIBE'
                """, (rs, n) -> new AsrJob(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                        rs.getObject("recording_id", UUID.class), rs.getString("provider_task_id"),
                        rs.getString("status"), rs.getInt("attempt_count"), rs.getString("last_error"),
                        DatabaseDateTime.getInstant(rs, "started_at"),
                        rs.getString("provider_route") == null ? "DASHSCOPE" : rs.getString("provider_route")), jobId, visitId).stream().findFirst();
    }

    public Optional<AsrJob> latestAsrJob(UUID visitId) {
        return jdbc.query("""
                SELECT id,visit_id,recording_id,provider_task_id,status,attempt_count,last_error,started_at,provider_route
                FROM ai_job WHERE visit_id=? AND job_type='ASR_TRANSCRIBE'
                ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> new AsrJob(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                        rs.getObject("recording_id", UUID.class), rs.getString("provider_task_id"),
                        rs.getString("status"), rs.getInt("attempt_count"), rs.getString("last_error"),
                        DatabaseDateTime.getInstant(rs, "started_at"),
                        rs.getString("provider_route") == null ? "DASHSCOPE" : rs.getString("provider_route")), visitId).stream().findFirst();
    }

    public UUID createSession(UUID visitId, UUID recordingId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recording_session(id,visit_id,recording_id,recording_fencing_token,status)
                VALUES (?,?,?,?, 'STOPPED')
                """, id, visitId, recordingId, UUID.randomUUID().toString());
        return id;
    }

    public List<UUID> insertUtterances(UUID visitId, UUID recordingId, UUID sessionId, List<Turn> turns) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            UUID id = UUID.randomUUID();
            ids.add(id);
            jdbc.update("""
                    INSERT INTO asr_utterance(id,visit_id,recording_id,session_id,utterance_id,revision,result_type,
                                              text,role,speaker_id,role_source,role_confidence,role_provider_route,start_ms,end_ms,is_current)
                    VALUES (?,?,?,?,?,?, 'CANONICAL', ?,?,?,?,?,?,?,?,true)
                    """, id, visitId, recordingId, sessionId, "u-" + (i + 1), 0,
                    t.text(), t.role(), t.speakerId(), t.roleSource(), t.roleConfidence(), t.roleProviderRoute(),
                    t.startMs(), t.endMs());
        }
        return ids;
    }

    public UUID createSnapshot(UUID visitId, UUID recordingId, UUID sessionId, List<Turn> turns,
                               List<UUID> utteranceIds, String snapshotHash) {
        supersedeAdopted(visitId);
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dialogue_snapshot(id,visit_id,recording_id,session_id,snapshot_version,snapshot_hash,authority_status,turns_json,created_by)
                SELECT ?,?,?,?,?,?,?,?::jsonb,v.doctor_id FROM visit v WHERE v.id=?
                """, snapshotId, visitId, recordingId, sessionId, nextSnapshotVersion(visitId),
                snapshotHash, "ADOPTED", turnsJson(turns), visitId);
        snapshotId = jdbc.queryForObject("SELECT id FROM dialogue_snapshot WHERE snapshot_hash=?", UUID.class, snapshotHash);
        for (UUID utteranceId : utteranceIds) {
            jdbc.update("INSERT INTO dialogue_snapshot_source(snapshot_id,utterance_id) VALUES (?,?)", snapshotId, utteranceId);
        }
        return snapshotId;
    }

    /** 根据医生编辑后的转写文本创建新的已采纳快照。 */
    public UUID createEditedSnapshot(UUID visitId, DialogueSnapshot base, List<Turn> turns,
                                     String snapshotHash, UUID doctorId) {
        Optional<UUID> existing = snapshotIdByHash(visitId, snapshotHash);
        if (existing.isPresent()) {
            UUID snapshotId = existing.get();
            // 哈希包含全部冻结字段；命中时表示文本、角色、时间及角色元数据均未变化，可安全重新采纳。
            jdbc.update("""
                    UPDATE dialogue_snapshot
                    SET authority_status=CASE WHEN id=? THEN 'ADOPTED' ELSE 'SUPERSEDED' END
                    WHERE visit_id=? AND (id=? OR authority_status='ADOPTED')
                    """, snapshotId, visitId, snapshotId);
            return snapshotId;
        }
        supersedeAdopted(visitId);
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dialogue_snapshot(id,visit_id,recording_id,session_id,snapshot_version,
                                              snapshot_hash,authority_status,turns_json,created_by)
                VALUES (?,?,?,?,?,?, 'ADOPTED',?::jsonb,?)
                """, snapshotId, visitId, base.recordingId(), base.sessionId(), nextSnapshotVersion(visitId),
                snapshotHash, turnsJson(turns), doctorId);
        jdbc.update("""
                INSERT INTO dialogue_snapshot_source(snapshot_id, utterance_id)
                SELECT ?, utterance_id FROM dialogue_snapshot_source WHERE snapshot_id=?
                """, snapshotId, base.id());
        return snapshotId;
    }

    private Optional<UUID> snapshotIdByHash(UUID visitId, String snapshotHash) {
        return jdbc.query("SELECT id FROM dialogue_snapshot WHERE visit_id=? AND snapshot_hash=?",
                (rs, n) -> rs.getObject("id", UUID.class), visitId, snapshotHash).stream().findFirst();
    }

    public Optional<DialogueSnapshot> latestSnapshot(UUID visitId) {
        return jdbc.query("""
                SELECT * FROM dialogue_snapshot
                WHERE visit_id=? AND authority_status='ADOPTED'
                ORDER BY snapshot_version DESC LIMIT 1
                """, (rs, n) -> new DialogueSnapshot(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                        rs.getObject("recording_id", UUID.class), rs.getObject("session_id", UUID.class),
                        rs.getInt("snapshot_version"), rs.getString("snapshot_hash"), rs.getString("authority_status"),
                        DatabaseDateTime.getInstant(rs, "created_at")), visitId).stream().findFirst();
    }

    public Optional<DialogueSnapshot> latestAdoptedSnapshot(UUID visitId) {
        return latestSnapshot(visitId);
    }

    /** 返回快照固化的句段 JSON，结构化提取不能使用可变的展示文本替代它。 */
    public Optional<String> snapshotTurnsJson(UUID snapshotId) {
        return jdbc.query("SELECT turns_json::text FROM dialogue_snapshot WHERE id=?",
                (rs, n) -> rs.getString(1), snapshotId).stream().findFirst();
    }

    /** 快照来源表是路由唯一可信来源，编辑快照复制来源表后仍可保持同一处理边界。 */
    public List<String> snapshotAsrRoutes(UUID snapshotId) {
        return jdbc.query("""
                SELECT DISTINCT COALESCE(r.asr_route,'')
                FROM dialogue_snapshot_source source
                JOIN asr_utterance utterance ON utterance.id=source.utterance_id
                JOIN recording r ON r.id=utterance.recording_id
                WHERE source.snapshot_id=?
                ORDER BY 1
                """, (rs, n) -> rs.getString(1), snapshotId);
    }

    public Optional<Utterance> firstUtterance(UUID sessionId) {
        return jdbc.query("SELECT * FROM asr_utterance WHERE session_id=? ORDER BY start_ms LIMIT 1",
                UTTERANCE, sessionId).stream().findFirst();
    }

    public List<Utterance> listByRecording(UUID recordingId) {
        return jdbc.query("SELECT * FROM asr_utterance WHERE recording_id=? ORDER BY start_ms,id", UTTERANCE, recordingId);
    }

    public Optional<Utterance> utterance(UUID visitId, UUID utteranceId) {
        return jdbc.query("SELECT * FROM asr_utterance WHERE id=? AND visit_id=?", UTTERANCE, utteranceId, visitId)
                .stream().findFirst();
    }

    public boolean updateRole(UUID visitId, UUID utteranceId, String role) {
        return jdbc.update("""
                UPDATE asr_utterance
                SET role=?, role_source='MANUAL', role_confidence=NULL, role_provider_route='MANUAL'
                WHERE id=? AND visit_id=?
                """, role, utteranceId, visitId) == 1;
    }

    /** 应用完整的 LLM 重新分类结果，且绝不覆盖医生的人工决定。 */
    public int updateRoleAssignments(UUID visitId, List<RoleUpdate> updates) {
        int updated = 0;
        for (RoleUpdate update : updates) {
            updated += jdbc.update("""
                    UPDATE asr_utterance
                    SET role=?, role_source=?, role_confidence=?, role_provider_route=?
                    WHERE id=? AND visit_id=? AND role_source <> 'MANUAL'
                    """, update.role(), update.source(), update.confidence(), update.providerRoute(),
                    update.utteranceId(), visitId);
        }
        return updated;
    }

    public Optional<TurnState> transcript(UUID visitId) {
                return jdbc.query("SELECT * FROM visit_transcript WHERE visit_id=?", (rs, n) -> new TurnState(
                rs.getObject("snapshot_id", UUID.class), rs.getString("transcript_text"), rs.getBoolean("edited"),
                DatabaseDateTime.getInstant(rs, "updated_at")), visitId).stream().findFirst();
    }

    public void saveTranscript(UUID visitId, UUID snapshotId, String transcript, boolean edited) {
        jdbc.update("""
                INSERT INTO visit_transcript(visit_id,snapshot_id,transcript_text,edited)
                VALUES (?,?,?,?)
                ON CONFLICT (visit_id) DO UPDATE SET
                  snapshot_id=EXCLUDED.snapshot_id,transcript_text=EXCLUDED.transcript_text,
                  edited=EXCLUDED.edited,updated_at=medicalai_local_now()
                """, visitId, snapshotId, transcript, edited);
    }

    private int nextSnapshotVersion(UUID visitId) {
        Integer n = jdbc.queryForObject("SELECT coalesce(max(snapshot_version),0) FROM dialogue_snapshot WHERE visit_id=?", Integer.class, visitId);
        return (n == null ? 0 : n) + 1;
    }

    private void supersedeAdopted(UUID visitId) {
        jdbc.update("UPDATE dialogue_snapshot SET authority_status='SUPERSEDED' WHERE visit_id=? AND authority_status='ADOPTED'", visitId);
    }

    private String turnsJson(List<Turn> turns) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if (i > 0) json.append(',');
            // 角色来源和置信度与文本一起冻结，确保之后的提取能判断该快照是否可安全使用。
            json.append("{\"role\":\"").append(escape(t.role())).append("\",\"text\":\"").append(escape(t.text()))
                    .append("\",\"startMs\":").append(t.startMs()).append(",\"endMs\":").append(t.endMs())
                    .append(",\"speaker_id\":").append(t.speakerId())
                    .append(",\"role_source\":\"").append(escape(t.roleSource())).append("\"")
                    .append(",\"role_confidence\":").append(t.roleConfidence())
                    .append(",\"role_provider_route\":\"").append(escape(t.roleProviderRoute())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String escape(String value) {
        String source = String.valueOf(value);
        StringBuilder escaped = new StringBuilder(source.length() + 16);
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public record Turn(String role, String text, long startMs, long endMs, Integer speakerId,
                       String roleSource, Integer roleConfidence, String roleProviderRoute) {
        public Turn(String role, String text, long startMs, long endMs, Integer speakerId,
                    String roleSource, Integer roleConfidence) {
            this(role, text, startMs, endMs, speakerId, roleSource, roleConfidence, "UNKNOWN");
        }
        public Turn(String role, String text, long startMs, long endMs, Integer speakerId) {
            this(role, text, startMs, endMs, speakerId, "AUTO", null, "UNKNOWN");
        }
        public Turn(String role, String text, long startMs, long endMs) {
            this(role, text, startMs, endMs, null, "AUTO", null, "UNKNOWN");
        }
    }
    public record RoleUpdate(UUID utteranceId, String role, String source, Integer confidence, String providerRoute) {
        public RoleUpdate(UUID utteranceId, String role, String source, Integer confidence) {
            this(utteranceId, role, source, confidence, "UNKNOWN");
        }
    }
    public record TurnState(UUID snapshotId, String transcript, boolean edited, Instant updatedAt) {}
    public record AsrJob(UUID id, UUID visitId, UUID recordingId, String providerTaskId, String status,
                         int attemptCount, String lastError, Instant startedAt, String providerRoute) {}
}

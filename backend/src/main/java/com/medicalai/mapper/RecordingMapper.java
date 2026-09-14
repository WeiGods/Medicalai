package com.medicalai.mapper;

import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.domain.Recording;
import com.medicalai.domain.Utterance;
import java.sql.Timestamp;
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
            rs.getObject("duration_ms", Long.class), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<Utterance> UTTERANCE = (rs, n) -> new Utterance(
            rs.getObject("id", UUID.class), rs.getObject("recording_id", UUID.class),
            rs.getObject("session_id", UUID.class), rs.getString("utterance_id"), rs.getInt("revision"),
            rs.getString("result_type"), rs.getString("text"), rs.getString("role"),
            rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getBoolean("is_current"),
            rs.getTimestamp("created_at").toInstant());

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
                r.fileName(), r.mimeType(), r.sizeBytes(), r.durationMs(), r.status(), r.sourceType().equals("SAMPLE") ? "MOCK" : "UPLOADED");
    }

    public Recording updateStatus(UUID id, String status, String error) {
        return jdbc.queryForObject("""
                UPDATE recording SET status=?,error_code=CASE WHEN ?::text IS NULL THEN NULL ELSE 'ASR_FAILED' END,
                  error_message=?,updated_at=now() WHERE id=? RETURNING *
                """, RECORDING, status, error, error, id);
    }

    public Optional<Recording> findByVisitAndStatus(UUID visitId, String status) {
        return jdbc.query("SELECT * FROM recording WHERE visit_id=? AND status=? ORDER BY created_at LIMIT 1",
                RECORDING, visitId, status).stream().findFirst();
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
                                              text,role,start_ms,end_ms,is_current)
                    VALUES (?,?,?,?,?,?, 'CANONICAL', ?,?,?,?,true)
                    """, id, visitId, recordingId, sessionId, "u-" + (i + 1), 0,
                    t.text(), t.role(), t.startMs(), t.endMs());
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

    /** Create a new adopted snapshot from doctor-edited transcript text. */
    public UUID createEditedSnapshot(UUID visitId, DialogueSnapshot base, List<Turn> turns,
                                     String snapshotHash, UUID doctorId) {
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

    public Optional<DialogueSnapshot> latestSnapshot(UUID visitId) {
        return jdbc.query("""
                SELECT * FROM dialogue_snapshot
                WHERE visit_id=? AND authority_status='ADOPTED'
                ORDER BY snapshot_version DESC LIMIT 1
                """, (rs, n) -> new DialogueSnapshot(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class),
                        rs.getObject("recording_id", UUID.class), rs.getObject("session_id", UUID.class),
                        rs.getInt("snapshot_version"), rs.getString("snapshot_hash"), rs.getString("authority_status"),
                        rs.getTimestamp("created_at").toInstant()), visitId).stream().findFirst();
    }

    public Optional<DialogueSnapshot> latestAdoptedSnapshot(UUID visitId) {
        return latestSnapshot(visitId);
    }

    public Optional<Utterance> firstUtterance(UUID sessionId) {
        return jdbc.query("SELECT * FROM asr_utterance WHERE session_id=? ORDER BY start_ms LIMIT 1",
                UTTERANCE, sessionId).stream().findFirst();
    }

    public List<Utterance> listByRecording(UUID recordingId) {
        return jdbc.query("SELECT * FROM asr_utterance WHERE recording_id=? ORDER BY start_ms,id", UTTERANCE, recordingId);
    }

    public Optional<TurnState> transcript(UUID visitId) {
                return jdbc.query("SELECT * FROM visit_transcript WHERE visit_id=?", (rs, n) -> new TurnState(
                rs.getObject("snapshot_id", UUID.class), rs.getString("transcript_text"), rs.getBoolean("edited"),
                rs.getTimestamp("updated_at").toInstant()), visitId).stream().findFirst();
    }

    public void saveTranscript(UUID visitId, UUID snapshotId, String transcript, boolean edited) {
        jdbc.update("""
                INSERT INTO visit_transcript(visit_id,snapshot_id,transcript_text,edited)
                VALUES (?,?,?,?)
                ON CONFLICT (visit_id) DO UPDATE SET
                  snapshot_id=EXCLUDED.snapshot_id,transcript_text=EXCLUDED.transcript_text,
                  edited=EXCLUDED.edited,updated_at=now()
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
            json.append("{\"role\":\"").append(escape(t.role())).append("\",\"text\":\"").append(escape(t.text()))
                    .append("\",\"startMs\":").append(t.startMs()).append(",\"endMs\":").append(t.endMs()).append('}');
        }
        return json.append(']').toString();
    }

    private String escape(String value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    public record Turn(String role, String text, long startMs, long endMs) {}
    public record TurnState(UUID snapshotId, String transcript, boolean edited, Instant updatedAt) {}
}

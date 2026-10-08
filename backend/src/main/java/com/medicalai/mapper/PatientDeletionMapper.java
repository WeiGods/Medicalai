package com.medicalai.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PatientDeletionMapper {
    private final JdbcTemplate jdbc;

    public PatientDeletionMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VisitRef> lockVisits(UUID patientId) {
        return jdbc.query("SELECT id,visit_no FROM visit WHERE patient_id=? ORDER BY id FOR UPDATE",
                (rs, n) -> new VisitRef(rs.getObject("id", UUID.class), rs.getString("visit_no")), patientId);
    }

    public boolean hasOpenSession(List<UUID> visitIds) {
        if (visitIds.isEmpty()) return false;
        String sql = "SELECT EXISTS(SELECT 1 FROM recording_session WHERE visit_id IN ("
                + placeholders(visitIds.size()) + ") AND status IN ('OPEN','STOPPING'))";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, visitIds.toArray()));
    }

    /** Use the same visit -> job -> export lock order as writers before capturing file paths. */
    public void lockJobs(List<UUID> visitIds) {
        if (visitIds.isEmpty()) return;
        jdbc.query("SELECT id FROM ai_job WHERE visit_id IN (" + placeholders(visitIds.size())
                        + ") ORDER BY id FOR UPDATE",
                (rs, n) -> rs.getObject("id", UUID.class), visitIds.toArray());
    }

    public List<ExportAsset> lockExportAssets(List<UUID> visitIds) {
        if (visitIds.isEmpty()) return List.of();
        return jdbc.query("""
                        SELECT e.id,e.format,e.object_key,r.visit_id
                        FROM record_export e JOIN medical_record r ON r.id=e.record_id
                        WHERE r.visit_id IN (%s)
                        ORDER BY e.id FOR UPDATE OF e
                        """.formatted(placeholders(visitIds.size())),
                (rs, n) -> new ExportAsset(rs.getObject("id", UUID.class), rs.getString("format"),
                        rs.getString("object_key"), rs.getObject("visit_id", UUID.class)), visitIds.toArray());
    }

    /** Preserve the old audit rows while removing their restrictive visit foreign keys. */
    public void detachAuditLogs(UUID patientId, String patientName, String patientNo) {
        jdbc.update("""
                UPDATE audit_log a
                SET patient_id_snapshot=COALESCE(a.patient_id_snapshot,?),
                    patient_name_snapshot=COALESCE(a.patient_name_snapshot,?),
                    patient_no_snapshot=COALESCE(a.patient_no_snapshot,?),
                    visit_no_snapshot=COALESCE(a.visit_no_snapshot,v.visit_no),
                    visit_id=NULL
                FROM visit v
                WHERE a.visit_id=v.id AND v.patient_id=?
                """, patientId, patientName, patientNo, patientId);
    }

    public void snapshotAuditVisit(UUID patientId, String patientName, String patientNo,
                                   UUID visitId, String visitNo) {
        jdbc.update("""
                UPDATE audit_log
                SET patient_id_snapshot=COALESCE(patient_id_snapshot,?),
                    patient_name_snapshot=COALESCE(patient_name_snapshot,?),
                    patient_no_snapshot=COALESCE(patient_no_snapshot,?),
                    visit_no_snapshot=COALESCE(visit_no_snapshot,?)
                WHERE visit_id=?
                """, patientId, patientName, patientNo, visitNo, visitId);
    }

    public void insertCleanupTask(UUID id, String visitIdsJson, String exportKeysJson, UUID requestedBy) {
        jdbc.update("""
                INSERT INTO patient_deletion_cleanup(id,visit_ids,export_keys,requested_by)
                VALUES (?,?::jsonb,?::jsonb,?)
                """, id, visitIdsJson, exportKeysJson, requestedBy);
    }

    public void deletePatientChain(UUID patientId, List<UUID> visitIds) {
        if (!visitIds.isEmpty()) {
            Object[] params = visitIds.toArray();
            String in = placeholders(visitIds.size());
            jdbc.update("DELETE FROM ai_job WHERE visit_id IN (" + in + ")", params);
            jdbc.update("""
                    DELETE FROM record_export e USING medical_record r
                    WHERE e.record_id=r.id AND r.visit_id IN (%s)
                    """.formatted(in), params);
            jdbc.update("""
                    DELETE FROM medical_record_confirmation c USING medical_record r
                    WHERE c.record_id=r.id AND r.visit_id IN (%s)
                    """.formatted(in), params);
            jdbc.update("""
                    DELETE FROM medical_record_version v USING medical_record r
                    WHERE v.record_id=r.id AND r.visit_id IN (%s)
                    """.formatted(in), params);
            jdbc.update("DELETE FROM medical_record WHERE visit_id IN (" + in + ")", params);
            jdbc.update("""
                    DELETE FROM clinical_extraction_version v USING clinical_extraction e
                    WHERE v.extraction_id=e.id AND e.visit_id IN (%s)
                    """.formatted(in), params);
            jdbc.update("DELETE FROM clinical_extraction WHERE visit_id IN (" + in + ")", params);
            jdbc.update("DELETE FROM visit_transcript WHERE visit_id IN (" + in + ")", params);
            jdbc.update("""
                    DELETE FROM dialogue_snapshot_source s USING dialogue_snapshot d
                    WHERE s.snapshot_id=d.id AND d.visit_id IN (%s)
                    """.formatted(in), params);
            jdbc.update("DELETE FROM dialogue_snapshot WHERE visit_id IN (" + in + ")", params);
            jdbc.update("DELETE FROM asr_utterance WHERE visit_id IN (" + in + ")", params);
            jdbc.update("DELETE FROM recording_session WHERE visit_id IN (" + in + ")", params);
            jdbc.update("DELETE FROM recording WHERE visit_id IN (" + in + ")", params);
            jdbc.update("DELETE FROM visit WHERE patient_id=?", patientId);
        }
        jdbc.update("DELETE FROM patient WHERE id=?", patientId);
    }

    public Optional<CleanupTask> claimCleanupTask(UUID id) {
        return jdbc.query("""
                UPDATE patient_deletion_cleanup
                SET status='RUNNING',attempt_count=attempt_count+1,locked_at=medicalai_local_now(),last_error=NULL
                WHERE id=? AND next_attempt_at<=medicalai_local_now()
                  AND (status='PENDING' OR (status='RUNNING' AND locked_at<medicalai_local_now()-interval '5 minutes'))
                RETURNING id,visit_ids::text,export_keys::text,attempt_count
                """, (rs, n) -> new CleanupTask(rs.getObject("id", UUID.class), rs.getString("visit_ids"),
                rs.getString("export_keys"), rs.getInt("attempt_count")), id).stream().findFirst();
    }

    public Optional<CleanupTask> claimNextCleanupTask() {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM patient_deletion_cleanup
                    WHERE next_attempt_at<=medicalai_local_now()
                      AND (status='PENDING' OR (status='RUNNING' AND locked_at<medicalai_local_now()-interval '5 minutes'))
                    ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE patient_deletion_cleanup t
                SET status='RUNNING',attempt_count=t.attempt_count+1,locked_at=medicalai_local_now(),last_error=NULL
                FROM candidate c WHERE t.id=c.id
                RETURNING t.id,t.visit_ids::text,t.export_keys::text,t.attempt_count
                """, (rs, n) -> new CleanupTask(rs.getObject("id", UUID.class), rs.getString("visit_ids"),
                rs.getString("export_keys"), rs.getInt("attempt_count"))).stream().findFirst();
    }

    public void completeCleanupTask(UUID id) {
        jdbc.update("""
                UPDATE patient_deletion_cleanup SET status='COMPLETED',locked_at=NULL,last_error=NULL,
                    completed_at=medicalai_local_now()
                WHERE id=? AND status='RUNNING'
                """, id);
    }

    public boolean retryCleanupTask(UUID id, int attempt, String error) {
        long delaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(0, attempt - 1)));
        return jdbc.update("""
                UPDATE patient_deletion_cleanup
                SET status='PENDING',locked_at=NULL,last_error=?,
                    next_attempt_at=medicalai_local_now()+(? * interval '1 second')
                WHERE id=? AND status='RUNNING'
                """, truncate(error, 1024), delaySeconds, id) == 1;
    }

    public Optional<String> cleanupStatus(UUID id) {
        return jdbc.query("SELECT status FROM patient_deletion_cleanup WHERE id=?",
                (rs, n) -> rs.getString("status"), id).stream().findFirst();
    }

    private String placeholders(int count) {
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add("?");
        return String.join(",", values);
    }

    private String truncate(String value, int max) {
        if (value == null || value.isBlank()) return "清理失败";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record VisitRef(UUID id, String visitNo) {}
    public record ExportAsset(UUID id, String format, String objectKey, UUID visitId) {}
    public record CleanupTask(UUID id, String visitIdsJson, String exportKeysJson, int attempt) {}
}

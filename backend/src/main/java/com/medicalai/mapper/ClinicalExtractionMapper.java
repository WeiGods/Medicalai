package com.medicalai.mapper;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 结构化提取结果的版本化存储。
 *
 * <p>病历和提取结果分别版本化，避免重新转写时覆盖已确认病历的审计记录。
 */
@Repository
public class ClinicalExtractionMapper {
    private static final RowMapper<Version> VERSION = (rs, n) -> new Version(
            rs.getObject("id", UUID.class), rs.getObject("extraction_id", UUID.class),
            rs.getInt("version_no"), rs.getObject("source_snapshot_id", UUID.class),
            rs.getString("source_snapshot_hash"), rs.getString("status"),
            rs.getString("content_json"), rs.getString("quality_issues_json"),
            rs.getString("provider_route"), rs.getString("generated_by"), DatabaseDateTime.getInstant(rs, "created_at"),
            DatabaseDateTime.getInstant(rs, "confirmed_at"));

    private final JdbcTemplate jdbc;

    public ClinicalExtractionMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reacquire the visit lock after the model call, before persisting any result rows. */
    private void requireVisitForWrite(UUID visitId) {
        boolean exists = !jdbc.query("SELECT id FROM visit WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getObject("id", UUID.class), visitId).isEmpty();
        if (!exists) throw com.medicalai.exception.BusinessException.notFound();
    }

    public Optional<Version> current(UUID visitId) {
        return jdbc.query("""
                SELECT v.* FROM clinical_extraction_version v
                JOIN clinical_extraction e ON e.id=v.extraction_id
                WHERE e.visit_id=? AND v.version_no=e.current_version
                """, VERSION, visitId).stream().findFirst();
    }

    public Optional<Version> confirmedForSnapshot(UUID visitId, UUID snapshotId, String snapshotHash) {
        return jdbc.query("""
                SELECT v.* FROM clinical_extraction_version v
                JOIN clinical_extraction e ON e.id=v.extraction_id
                WHERE e.visit_id=? AND e.status='CONFIRMED' AND v.version_no=e.current_version
                  AND v.status='CONFIRMED' AND v.source_snapshot_id=? AND v.source_snapshot_hash=?
                """, VERSION, visitId, snapshotId, snapshotHash).stream().findFirst();
    }

    public Version insert(UUID visitId, UUID snapshotId, String snapshotHash, String status,
                          String contentJson, String qualityIssuesJson, String generatedBy, UUID doctorId) {
        return insert(visitId, snapshotId, snapshotHash, status, contentJson, qualityIssuesJson,
                "UNKNOWN", generatedBy, doctorId);
    }

    public Version insert(UUID visitId, UUID snapshotId, String snapshotHash, String status,
                          String contentJson, String qualityIssuesJson, String providerRoute,
                          String generatedBy, UUID doctorId) {
        return insertForJob(visitId, snapshotId, snapshotHash, status, contentJson, qualityIssuesJson,
                providerRoute, generatedBy, doctorId, null, null);
    }

    /** 模型调用前的无锁检查；写入时持有接诊锁再检查一次，防止旧快照回写。 */
    public void validateSource(UUID visitId, UUID doctorId, UUID snapshotId, String snapshotHash, String route) {
        var visit = jdbc.query("SELECT status FROM visit WHERE id=? AND doctor_id=?",
                (rs, n) -> rs.getString(1), visitId, doctorId);
        if (visit.isEmpty()) throw com.medicalai.exception.BusinessException.notFound();
        if (!"ACTIVE".equals(visit.getFirst())) throw sourceChanged();
        if (!jdbc.query("SELECT status FROM medical_record WHERE visit_id=? AND status='CONFIRMED'",
                (rs, n) -> rs.getString(1), visitId).isEmpty()) {
            throw com.medicalai.exception.BusinessException.conflict("RECORD_CONFIRMED", "病历已确认，不能重新提取");
        }
        Integer valid = jdbc.queryForObject("""
                SELECT count(*) FROM dialogue_snapshot s JOIN visit_transcript t ON t.snapshot_id=s.id AND t.visit_id=s.visit_id
                WHERE s.visit_id=? AND s.id=? AND s.snapshot_hash=? AND s.authority_status='ADOPTED'
                  AND NOT EXISTS (SELECT 1 FROM recording r WHERE r.visit_id=s.visit_id AND r.status<>'DONE')
                  AND NOT EXISTS (SELECT 1 FROM ai_job a WHERE a.visit_id=s.visit_id
                      AND a.job_type='ASR_TRANSCRIBE' AND a.status IN ('PENDING','RUNNING'))
                  AND NOT EXISTS (SELECT 1 FROM recording_session rs WHERE rs.visit_id=s.visit_id AND rs.status IN ('OPEN','STOPPING'))
                """, Integer.class, visitId, snapshotId, snapshotHash);
        if (valid == null || valid != 1) throw sourceChanged();
        List<String> routes = jdbc.query("""
                SELECT DISTINCT CASE r.asr_route WHEN 'DASHSCOPE_ROLE_V2' THEN 'DASHSCOPE'
                    WHEN 'DASHSCOPE' THEN 'DASHSCOPE' WHEN 'LOCAL' THEN 'LOCAL' ELSE 'UNKNOWN' END
                FROM dialogue_snapshot_source src JOIN asr_utterance u ON u.id=src.utterance_id
                JOIN recording r ON r.id=u.recording_id WHERE src.snapshot_id=?
                """, (rs, n) -> rs.getString(1), snapshotId);
        if (routes.isEmpty() || routes.contains("UNKNOWN") || !routes.contains(route)) throw sourceChanged();
    }

    private com.medicalai.exception.BusinessException sourceChanged() {
        return com.medicalai.exception.BusinessException.conflict("SOURCE_CHANGED", "对话来源已更新，已丢弃本次旧提取结果");
    }

    public Version insertForJob(UUID visitId, UUID snapshotId, String snapshotHash, String status,
                          String contentJson, String qualityIssuesJson, String providerRoute,
                          String generatedBy, UUID doctorId, UUID jobId, UUID token) {
        requireVisitForWrite(visitId);
        validateSource(visitId, doctorId, snapshotId, snapshotHash, providerRoute);
        if (jobId != null && jdbc.query("""
                SELECT id FROM ai_job WHERE id=? AND job_type='CLINICAL_EXTRACT' AND status='RUNNING'
                    AND lease_token=? AND locked_at >= medicalai_local_clock() - interval '90 seconds' FOR UPDATE
                """, (rs, n) -> rs.getObject("id", UUID.class), jobId, token).isEmpty()) throw sourceChanged();
        UUID extractionId = jdbc.query("SELECT id FROM clinical_extraction WHERE visit_id=?",
                (rs, n) -> rs.getObject("id", UUID.class), visitId).stream().findFirst().orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.update("INSERT INTO clinical_extraction(id,visit_id,status) VALUES (?,?,'PENDING')", id, visitId);
                    return id;
                });
        Integer latest = jdbc.queryForObject(
                "SELECT coalesce(max(version_no),0) FROM clinical_extraction_version WHERE extraction_id=?",
                Integer.class, extractionId);
        int versionNo = (latest == null ? 0 : latest) + 1;
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO clinical_extraction_version(id,extraction_id,version_no,source_snapshot_id,
                                                        source_snapshot_hash,status,content_json,quality_issues_json,
                                                        provider_route,generated_by,created_by)
                VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)
                """, versionId, extractionId, versionNo, snapshotId, snapshotHash, status,
                contentJson, qualityIssuesJson, providerRoute, generatedBy, doctorId);
        jdbc.update("UPDATE clinical_extraction SET current_version=?,status=?,updated_at=medicalai_local_now() WHERE id=?",
                versionNo, status, extractionId);
        return current(visitId).orElseThrow();
    }

    public Optional<Version> confirm(UUID visitId, UUID snapshotId, String snapshotHash, UUID doctorId) {
        int updated = jdbc.update("""
                UPDATE clinical_extraction_version v
                SET status='CONFIRMED',confirmed_at=medicalai_local_now(),confirmed_by=?
                FROM clinical_extraction e
                WHERE e.id=v.extraction_id AND e.visit_id=? AND v.version_no=e.current_version
                  AND e.status='GENERATED' AND v.status='GENERATED'
                  AND v.source_snapshot_id=? AND v.source_snapshot_hash=?
                """, doctorId, visitId, snapshotId, snapshotHash);
        if (updated != 1) return Optional.empty();
        jdbc.update("UPDATE clinical_extraction SET status='CONFIRMED',updated_at=medicalai_local_now() WHERE visit_id=?", visitId);
        return current(visitId);
    }

    /**
     * 新的对话快照出现后，旧提取不得继续被病历生成复用。
     * 已确认病历本身不受影响，仍引用自己的历史快照。
     */
    public void markCurrentStale(UUID visitId) {
        jdbc.update("""
                UPDATE clinical_extraction_version v SET status='STALE'
                FROM clinical_extraction e
                WHERE e.id=v.extraction_id AND e.visit_id=? AND v.version_no=e.current_version
                  AND v.status IN ('GENERATED','CONFIRMED','FAILED')
                """, visitId);
        jdbc.update("""
                UPDATE clinical_extraction SET status='STALE',updated_at=medicalai_local_now()
                WHERE visit_id=? AND status IN ('GENERATED','CONFIRMED','FAILED')
                """, visitId);
    }

    public record Version(UUID id, UUID extractionId, int versionNo, UUID sourceSnapshotId,
                          String sourceSnapshotHash, String status, String contentJson,
                          String qualityIssuesJson, String providerRoute, String generatedBy, Instant createdAt,
                          Instant confirmedAt) {
        public Version(UUID id, UUID extractionId, int versionNo, UUID sourceSnapshotId,
                       String sourceSnapshotHash, String status, String contentJson,
                       String qualityIssuesJson, String generatedBy, Instant createdAt, Instant confirmedAt) {
            this(id, extractionId, versionNo, sourceSnapshotId, sourceSnapshotHash, status, contentJson,
                    qualityIssuesJson, "UNKNOWN", generatedBy, createdAt, confirmedAt);
        }
    }
}

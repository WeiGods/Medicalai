package com.medicalai.mapper;

import java.time.Instant;
import java.util.Optional;
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

package com.medicalai.mapper;

import com.medicalai.domain.MedicalRecordConfirmation;
import com.medicalai.domain.MedicalRecordVersion;
import com.medicalai.domain.RecordExport;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MedicalRecordMapper {
    public static final int CURRENT_EXPORT_TEMPLATE_VERSION = 6;
    static final String INSERT_REVISION_EXPORT_SQL = """
            INSERT INTO record_export(id,record_id,version_id,confirmation_id,format,template_version,template_id,template_revision_id,status,object_key,created_by)
            VALUES (?,?,?,?,?,NULL,?,?,'PENDING',NULL,?)
            ON CONFLICT DO NOTHING
            """;
    private static final RowMapper<MedicalRecordVersion> VERSION = (rs, n) -> new MedicalRecordVersion(
            rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class), rs.getInt("version_no"),
            rs.getObject("source_snapshot_id", UUID.class), rs.getString("source_snapshot_hash"),
            rs.getString("content_json"), rs.getString("edited_content_json"), rs.getString("generation_status"),
            rs.getString("generated_by"), DatabaseDateTime.getInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;
    public MedicalRecordMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> findRecordId(UUID visitId) {
        return jdbc.query("SELECT id FROM medical_record WHERE visit_id=?",
                (rs,n) -> rs.getObject("id", UUID.class), visitId).stream().findFirst();
    }

    public String status(UUID visitId) {
        // 转写可在病历生成前编辑。此时尚不存在 medical_record 记录，应视为“未确认”，
        // 而非数据库故障。
        return jdbc.query("SELECT r.status FROM medical_record r WHERE r.visit_id=?", (rs, n) -> rs.getString("status"), visitId)
                .stream().findFirst().orElse("DRAFT");
    }

    public UUID createRecord(UUID id, UUID visitId) {
        jdbc.update("INSERT INTO medical_record(id,visit_id) VALUES (?,?)", id, visitId);
        return id;
    }

    public int latestVersion(UUID recordId) {
        Integer n = jdbc.queryForObject("SELECT coalesce(max(version_no),0) FROM medical_record_version WHERE record_id=?",
                Integer.class, recordId);
        return n == null ? 0 : n;
    }

    public MedicalRecordVersion insertVersion(UUID recordId, int versionNo, UUID snapshotId, String snapshotHash,
                                              String contentJson, String generatedBy, UUID doctorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO medical_record_version(id,record_id,version_no,source_snapshot_id,source_snapshot_hash,
                                                   content_json,generation_status,generated_by,created_by)
                VALUES (?,?,?,?,?,?::jsonb,'SUCCEEDED',?,?)
                """, id, recordId, versionNo, snapshotId, snapshotHash, contentJson, generatedBy, doctorId);
        jdbc.update("""
                UPDATE medical_record SET current_version=?,status='DRAFT',confirmed_version=NULL,
                  confirmed_at=NULL,confirmed_by=NULL,updated_at=medicalai_local_now() WHERE id=?
                """, versionNo, recordId);
        return findVersion(recordId, versionNo).orElseThrow();
    }

    public Optional<MedicalRecordVersion> findVersion(UUID recordId, int versionNo) {
        return jdbc.query("SELECT * FROM medical_record_version WHERE record_id=? AND version_no=?",
                VERSION, recordId, versionNo).stream().findFirst();
    }

    public Optional<MedicalRecordVersion> currentVersion(UUID visitId) {
        return jdbc.query("""
                SELECT v.* FROM medical_record_version v
                JOIN medical_record r ON r.id=v.record_id
                WHERE r.visit_id=? AND v.version_no=r.current_version
                """, VERSION, visitId).stream().findFirst();
    }

    public void saveDraft(UUID recordId, int versionNo, String contentJson) {
        jdbc.update("""
                UPDATE medical_record_version SET edited_content_json=?::jsonb,generation_status='EDITED'
                WHERE record_id=? AND version_no=?
                """, contentJson, recordId, versionNo);
    }

    public void confirm(UUID recordId, int versionNo, UUID doctorId, UUID confirmationId) {
        jdbc.update("""
                UPDATE medical_record SET status='CONFIRMED',confirmed_version=?,confirmed_at=medicalai_local_now(),confirmed_by=?,updated_at=medicalai_local_now()
                WHERE id=?
                """, versionNo, doctorId, recordId);
    }

    public boolean confirmationExists(UUID versionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM medical_record_confirmation WHERE version_id=?)", Boolean.class, versionId));
    }

    /** 已签署过的接诊禁止物理删除其录音及派生链路。 */
    public boolean hasAnyConfirmation(UUID visitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM medical_record_confirmation c
                    JOIN medical_record r ON r.id=c.record_id
                    WHERE r.visit_id=?
                )
                """, Boolean.class, visitId));
    }

    public UUID insertConfirmation(UUID recordId, UUID versionId, UUID doctorId, String clientIp) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO medical_record_confirmation(id,record_id,version_id,doctor_id,declaration,client_ip)
                VALUES (?,?,?,?,true,?)
                """, id, recordId, versionId, doctorId, clientIp);
        return id;
    }

    public List<ConfirmationRow> confirmations(UUID visitId) {
        return jdbc.query("""
                SELECT c.id,v.version_no,d.display_name,c.confirmed_at
                FROM medical_record_confirmation c
                JOIN medical_record r ON r.id=c.record_id
                JOIN medical_record_version v ON v.id=c.version_id
                JOIN doctor d ON d.id=c.doctor_id
                WHERE r.visit_id=? ORDER BY c.confirmed_at DESC
                """, (rs,n) -> new ConfirmationRow(rs.getObject("id", UUID.class), rs.getInt("version_no"),
                        rs.getString("display_name"), DatabaseDateTime.getInstant(rs, "confirmed_at")), visitId);
    }

    public Optional<ConfirmedVersion> currentConfirmedVersion(UUID visitId) {
        return jdbc.query("""
                SELECT r.id AS record_id, v.id AS version_id, v.version_no, c.id AS confirmation_id
                FROM medical_record r
                JOIN medical_record_version v ON v.record_id=r.id AND v.version_no=r.current_version
                JOIN medical_record_confirmation c ON c.record_id=r.id AND c.version_id=v.id
                WHERE r.visit_id=? AND r.status='CONFIRMED'
                  AND r.confirmed_version=r.current_version AND c.declaration=true
                """, (rs, n) -> new ConfirmedVersion(rs.getObject("record_id", UUID.class),
                        rs.getObject("version_id", UUID.class), rs.getInt("version_no"),
                        rs.getObject("confirmation_id", UUID.class)), visitId).stream().findFirst();
    }

    public Optional<RecordExport> reusableExport(UUID recordId, UUID versionId, UUID confirmationId, String format, int templateVersion) {
        return jdbc.query("""
                SELECT e.id,e.record_id,v.version_no,e.template_version,e.format,e.status,d.display_name,e.created_at
                FROM record_export e
                JOIN medical_record_version v ON v.id=e.version_id AND v.record_id=e.record_id
                JOIN doctor d ON d.id=e.created_by
                WHERE e.record_id=? AND e.version_id=? AND e.confirmation_id=? AND e.format=?
                  AND e.template_version=?
                  AND e.status IN ('PENDING','RUNNING','SUCCEEDED')
                ORDER BY e.created_at DESC LIMIT 1
                """, (rs, n) -> new RecordExport(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                        rs.getInt("version_no"), rs.getInt("template_version"), rs.getString("format"), rs.getString("status"),
                        rs.getString("display_name"), DatabaseDateTime.getInstant(rs, "created_at")),
                recordId, versionId, confirmationId, format, templateVersion).stream().findFirst();
    }

    public Optional<RecordExport> reusableExport(UUID recordId, UUID versionId, UUID confirmationId, String format) {
        return reusableExport(recordId, versionId, confirmationId, format, CURRENT_EXPORT_TEMPLATE_VERSION);
    }

    public Optional<RecordExport> reusableExport(UUID recordId, UUID versionId, UUID confirmationId, String format,
                                                 UUID templateRevisionId) {
        return exportByRevision(recordId, versionId, confirmationId, format, templateRevisionId,
                "e.status IN ('PENDING','RUNNING','SUCCEEDED')");
    }

    public Optional<RecordExport> failedCurrentTemplateExport(UUID recordId, UUID versionId, UUID confirmationId,
                                                               String format, int templateVersion) {
        return jdbc.query("""
                SELECT e.id,e.record_id,v.version_no,e.template_version,e.format,e.status,d.display_name,e.created_at
                FROM record_export e
                JOIN medical_record_version v ON v.id=e.version_id AND v.record_id=e.record_id
                JOIN doctor d ON d.id=e.created_by
                WHERE e.record_id=? AND e.version_id=? AND e.confirmation_id=? AND e.format=?
                  AND e.template_version=? AND e.status='FAILED'
                ORDER BY e.created_at DESC LIMIT 1
                """, (rs, n) -> new RecordExport(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                        rs.getInt("version_no"), rs.getInt("template_version"), rs.getString("format"),
                        rs.getString("status"), rs.getString("display_name"), DatabaseDateTime.getInstant(rs, "created_at")),
                recordId, versionId, confirmationId, format, templateVersion).stream().findFirst();
    }

    public Optional<RecordExport> failedCurrentTemplateExport(UUID recordId, UUID versionId, UUID confirmationId,
                                                               String format) {
        return failedCurrentTemplateExport(recordId, versionId, confirmationId, format,
                CURRENT_EXPORT_TEMPLATE_VERSION);
    }

    public Optional<RecordExport> failedCurrentTemplateExport(UUID recordId, UUID versionId, UUID confirmationId,
                                                               String format, UUID templateRevisionId) {
        return exportByRevision(recordId, versionId, confirmationId, format, templateRevisionId, "e.status='FAILED'");
    }

    public Optional<RecordExport> insertExport(UUID recordId, int versionNo, UUID confirmationId, UUID versionId,
                                               String format, int templateVersion, UUID doctorId) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO record_export(id,record_id,version_id,confirmation_id,format,template_version,status,object_key,created_by)
                VALUES (?,?,?,?,?,?, 'PENDING', NULL,?)
                ON CONFLICT DO NOTHING
                """, id, recordId, versionId, confirmationId, format, templateVersion, doctorId);
        if (inserted == 0) return Optional.empty();
        return exportsByVisit(visitIdOf(recordId)).stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public Optional<RecordExport> insertExport(UUID recordId, int versionNo, UUID confirmationId, UUID versionId,
                                               String format, UUID doctorId) {
        return insertExport(recordId, versionNo, confirmationId, versionId, format,
                CURRENT_EXPORT_TEMPLATE_VERSION, doctorId);
    }

    public Optional<RecordExport> insertExport(UUID recordId, int versionNo, UUID confirmationId, UUID versionId,
                                               String format, UUID templateId, UUID templateRevisionId, UUID doctorId) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(INSERT_REVISION_EXPORT_SQL, id, recordId, versionId, confirmationId, format,
                templateId, templateRevisionId, doctorId);
        if (inserted == 0) return Optional.empty();
        return exportsByVisit(visitIdOf(recordId)).stream().filter(e -> e.id().equals(id)).findFirst();
    }

    public UUID createExportJob(UUID visitId, UUID exportId, String format) {
        UUID id = UUID.randomUUID();
        String type = "DOCX".equalsIgnoreCase(format) ? "EXPORT_DOCX" : "EXPORT_PDF";
        jdbc.update("""
                INSERT INTO ai_job(id,job_type,visit_id,idempotency_key,status,result_ref,provider_route)
                VALUES (?,?,?,?,'PENDING',?,'BACKEND')
                """, id, type, visitId, "export:" + exportId, exportId);
        return id;
    }

    public void requeueFailedExport(UUID visitId, UUID exportId, String format) {
        int reset = jdbc.update("""
                UPDATE record_export SET status='PENDING',object_key=NULL,error_message=NULL
                WHERE id=? AND status='FAILED'
                """, exportId);
        if (reset == 0) return;
        int updated = jdbc.update("""
                UPDATE ai_job SET status='FAILED',finished_at=medicalai_local_now(),
                    last_error=NULL,locked_at=NULL,lease_token=NULL
                WHERE idempotency_key=?
                """, "export:" + exportId);
        if (updated == 0) return;
    }

    public Optional<ExportJob> claimNextExportJob(UUID leaseToken) {
        List<ExportJob> jobs = jdbc.query("""
                WITH candidate AS (
                    SELECT j.id, j.result_ref
                    FROM ai_job j
                    JOIN record_export e ON e.id=j.result_ref
                    WHERE j.job_type IN ('EXPORT_DOCX','EXPORT_PDF')
                      AND j.status='PENDING' AND e.status='PENDING'
                    ORDER BY j.created_at
                    FOR UPDATE OF j SKIP LOCKED
                    LIMIT 1
                )
                UPDATE ai_job j
                SET status='RUNNING', attempt_count=j.attempt_count+1,
                    started_at=COALESCE(j.started_at,medicalai_local_now()), locked_at=medicalai_local_clock(), lease_token=?
                FROM candidate c
                WHERE j.id=c.id
                RETURNING j.id,j.result_ref,j.attempt_count
                """, (rs, n) -> new ExportJob(rs.getObject("id", UUID.class),
                        rs.getObject("result_ref", UUID.class), rs.getInt("attempt_count")), leaseToken);
        if (jobs.isEmpty()) return Optional.empty();
        ExportJob job = jobs.getFirst();
        jdbc.update("UPDATE record_export SET status='RUNNING',error_message=NULL WHERE id=? AND status='PENDING'", job.exportId());
        return Optional.of(job);
    }

    public boolean markExportSucceeded(UUID exportId, UUID jobId, String objectKey) {
        int updated = jdbc.update("""
                UPDATE record_export SET status='SUCCEEDED',object_key=?,error_message=NULL
                WHERE id=? AND status='RUNNING'
                """, objectKey, exportId);
        if (updated == 0) return false;
        jdbc.update("""
                UPDATE ai_job SET status='SUCCEEDED',finished_at=medicalai_local_now(),locked_at=NULL,lease_token=NULL,last_error=NULL
                WHERE id=? AND result_ref=?
                """, jobId, exportId);
        return true;
    }

    /**
     * 前端模板生成完成后上传归档。只允许同一医生把当前接诊下的待处理导出标记为成功。
     */
    public boolean markUploadedExportSucceeded(UUID exportId, UUID visitId, UUID doctorId, String objectKey) {
        int updated = jdbc.update("""
                UPDATE record_export e
                SET status='SUCCEEDED',object_key=?,error_message=NULL
                FROM medical_record r
                JOIN visit v ON v.id=r.visit_id
                WHERE e.id=? AND e.record_id=r.id AND r.visit_id=? AND v.doctor_id=?
                  AND e.status IN ('PENDING','RUNNING')
                """, objectKey, exportId, visitId, doctorId);
        if (updated == 0) return false;
        jdbc.update("""
                UPDATE ai_job
                SET status='SUCCEEDED',finished_at=medicalai_local_now(),locked_at=NULL,lease_token=NULL,last_error=NULL
                WHERE result_ref=?
                """, exportId);
        return true;
    }

    /**
     * 原子占用待上传的导出任务；只有占用成功的请求才允许写归档文件，
     * 防止并发或重复上传在状态校验前覆盖已归档内容。
     */
    public boolean claimExportForUpload(UUID exportId, UUID visitId, UUID doctorId) {
        int claimed = jdbc.update("""
                UPDATE record_export e
                SET status='RUNNING',error_message=NULL
                FROM medical_record r
                JOIN visit v ON v.id=r.visit_id
                WHERE e.id=? AND e.record_id=r.id AND r.visit_id=? AND v.doctor_id=?
                  AND e.status IN ('PENDING','RUNNING')
                """, exportId, visitId, doctorId);
        return claimed > 0;
    }

    /**
     * 历史记录可能因部署清理或旧下载链路误读存储位置而丢失本地导出文件。
     * 不能继续保留 SUCCEEDED，否则前端会永久复用一个无法下载的导出记录。
     */
    public void markExportFileMissing(UUID exportId) {
        jdbc.update("""
                UPDATE record_export
                SET status='FAILED',object_key=NULL,error_message='导出文件不存在，请重新导出'
                WHERE id=? AND status='SUCCEEDED'
                """, exportId);
    }

    public boolean markExportFailed(UUID exportId, UUID jobId, int attempt, String error) {
        if (attempt < 3) {
            jdbc.update("UPDATE record_export SET status='PENDING',error_message=? WHERE id=?", error, exportId);
            jdbc.update("""
                    UPDATE ai_job SET status='PENDING',last_error=?,locked_at=NULL,lease_token=NULL
                    WHERE id=? AND result_ref=?
                    """, error, jobId, exportId);
            return false;
        } else {
            int updated = jdbc.update("UPDATE record_export SET status='FAILED',error_message=? WHERE id=? AND status='RUNNING'",
                    error, exportId);
            if (updated == 0) return false;
            jdbc.update("""
                    UPDATE ai_job SET status='FAILED',finished_at=medicalai_local_now(),last_error=?,locked_at=NULL,lease_token=NULL
                    WHERE id=? AND result_ref=?
                    """, error, jobId, exportId);
            return true;
        }
    }

    public Optional<ExportAuditContext> exportAuditContext(UUID exportId) {
        return jdbc.query("""
                SELECT e.created_by,r.visit_id,e.format
                FROM record_export e
                JOIN medical_record r ON r.id=e.record_id
                WHERE e.id=?
                """, (resultSet, rowNum) -> new ExportAuditContext(
                resultSet.getObject("created_by", UUID.class), resultSet.getObject("visit_id", UUID.class),
                resultSet.getString("format")), exportId).stream().findFirst();
    }

    public Optional<ExportPayload> exportPayload(UUID exportId) {
        return jdbc.query("""
                SELECT e.id,e.format,e.version_id,e.record_id,
                       r.visit_id,vi.visit_no,v.version_no,
                       v.content_json,v.edited_content_json,
                       c.confirmed_at,tr.definition_json
                FROM record_export e
                JOIN medical_record r ON r.id=e.record_id
                JOIN medical_record_version v ON v.id=e.version_id AND v.record_id=r.id
                JOIN medical_record_confirmation c ON c.id=e.confirmation_id
                    AND c.record_id=r.id AND c.version_id=v.id
                JOIN visit vi ON vi.id=r.visit_id
                LEFT JOIN export_template_revision tr ON tr.id=e.template_revision_id
                -- Export recovery must use the version and template revision frozen on the export row.
                -- The record may legitimately have a newer signed revision by the time a historical file is rebuilt.
                WHERE e.id=? AND c.declaration=true
                """, (rs, n) -> new ExportPayload(rs.getObject("id", UUID.class),
                        rs.getObject("record_id", UUID.class), rs.getObject("version_id", UUID.class),
                        rs.getObject("visit_id", UUID.class), rs.getString("visit_no"),
                        rs.getInt("version_no"), rs.getString("format"),
                        rs.getString("content_json"), rs.getString("edited_content_json"),
                        DatabaseDateTime.getInstant(rs, "confirmed_at"), rs.getString("definition_json")), exportId)
                .stream().findFirst();
    }

    public Optional<ExportDownload> findExportForDownload(UUID exportId, UUID doctorId) {
        return findExportForDownload(exportId, doctorId, false);
    }

    public Optional<ExportDownload> findExportForDownload(UUID exportId, UUID doctorId, boolean includeAll) {
        String scope = includeAll ? "" : " AND v.doctor_id=?";
        Object[] parameters = includeAll ? new Object[]{exportId} : new Object[]{exportId, doctorId};
        return jdbc.query("""
                SELECT e.id,e.format,e.status,e.object_key,e.error_message
                FROM record_export e
                JOIN medical_record r ON r.id=e.record_id
                JOIN visit v ON v.id=r.visit_id
                WHERE e.id=?
                """ + scope, (rs, n) -> new ExportDownload(rs.getObject("id", UUID.class),
                        rs.getString("format"), rs.getString("status"), rs.getString("object_key"),
                        rs.getString("error_message")), parameters).stream().findFirst();
    }

    public List<RecordExport> exportsByVisit(UUID visitId) {
        return jdbc.query("""
                SELECT e.id,e.record_id,ver.version_no,e.template_id,e.template_revision_id,
                       COALESCE(t.name,'模板 v' || COALESCE(e.template_version::text,'')) AS template_name,
                       COALESCE(tr.revision_no,e.template_version,0) AS template_revision_no,
                       e.format,e.status,d.display_name,e.created_at
                FROM record_export e
                JOIN medical_record r ON r.id=e.record_id
                JOIN visit v ON v.id=r.visit_id
                JOIN medical_record_version ver ON ver.id=e.version_id AND ver.record_id=r.id
                JOIN doctor d ON d.id=e.created_by
                LEFT JOIN export_template t ON t.id=e.template_id
                LEFT JOIN export_template_revision tr ON tr.id=e.template_revision_id
                WHERE r.visit_id=? ORDER BY e.created_at DESC
                """, this::recordExport, visitId);
    }

    private Optional<RecordExport> exportByRevision(UUID recordId, UUID versionId, UUID confirmationId, String format,
                                                     UUID templateRevisionId, String stateClause) {
        return jdbc.query("""
                SELECT e.id,e.record_id,v.version_no,e.template_id,e.template_revision_id,t.name AS template_name,
                       tr.revision_no AS template_revision_no,e.format,e.status,d.display_name,e.created_at
                FROM record_export e
                JOIN medical_record_version v ON v.id=e.version_id AND v.record_id=e.record_id
                JOIN doctor d ON d.id=e.created_by
                JOIN export_template t ON t.id=e.template_id
                JOIN export_template_revision tr ON tr.id=e.template_revision_id
                WHERE e.record_id=? AND e.version_id=? AND e.confirmation_id=? AND e.format=?
                  AND e.template_revision_id=? AND %s
                ORDER BY e.created_at DESC LIMIT 1
                """.formatted(stateClause), this::recordExport, recordId, versionId, confirmationId, format, templateRevisionId)
                .stream().findFirst();
    }

    private RecordExport recordExport(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RecordExport(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                rs.getInt("version_no"), rs.getObject("template_id", UUID.class),
                rs.getObject("template_revision_id", UUID.class), rs.getString("template_name"),
                rs.getInt("template_revision_no"), rs.getString("format"), rs.getString("status"),
                rs.getString("display_name"), DatabaseDateTime.getInstant(rs, "created_at"));
    }

    private UUID visitIdOf(UUID recordId) {
        return jdbc.queryForObject("SELECT visit_id FROM medical_record WHERE id=?", UUID.class, recordId);
    }

    public record ConfirmationRow(UUID id, int versionNo, String doctorName, Instant confirmedAt) {}
    public record ExportRow(UUID id, int versionNo, String format, String status, String doctorName, Instant createdAt) {}
    public record ConfirmedVersion(UUID recordId, UUID versionId, int versionNo, UUID confirmationId) {}
    public record ExportJob(UUID jobId, UUID exportId, int attempt) {}
    public record ExportAuditContext(UUID doctorId, UUID visitId, String format) {}
    public record ExportPayload(UUID id, UUID recordId, UUID versionId, UUID visitId, String visitNo,
                                int versionNo, String format, String contentJson, String editedContentJson,
                                Instant confirmedAt, String templateDefinitionJson) {
        public ExportPayload(UUID id, UUID recordId, UUID versionId, UUID visitId, String visitNo,
                             int versionNo, String format, String contentJson, String editedContentJson,
                             Instant confirmedAt) {
            this(id, recordId, versionId, visitId, visitNo, versionNo, format, contentJson, editedContentJson,
                    confirmedAt, null);
        }
    }
    public record ExportDownload(UUID id, String format, String status, String objectKey, String errorMessage) {}
}

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
    private static final RowMapper<MedicalRecordVersion> VERSION = (rs, n) -> new MedicalRecordVersion(
            rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class), rs.getInt("version_no"),
            rs.getObject("source_snapshot_id", UUID.class), rs.getString("source_snapshot_hash"),
            rs.getString("content_json"), rs.getString("edited_content_json"), rs.getString("generation_status"),
            rs.getString("generated_by"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    public MedicalRecordMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> findRecordId(UUID visitId) {
        return jdbc.query("SELECT id FROM medical_record WHERE visit_id=?",
                (rs,n) -> rs.getObject("id", UUID.class), visitId).stream().findFirst();
    }

    public String status(UUID visitId) {
        return jdbc.queryForObject("SELECT r.status FROM medical_record r WHERE r.visit_id=?", String.class, visitId);
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
                  confirmed_at=NULL,confirmed_by=NULL,updated_at=now() WHERE id=?
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
                UPDATE medical_record SET status='CONFIRMED',confirmed_version=?,confirmed_at=now(),confirmed_by=?,updated_at=now()
                WHERE id=?
                """, versionNo, doctorId, recordId);
    }

    public boolean confirmationExists(UUID versionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM medical_record_confirmation WHERE version_id=?)", Boolean.class, versionId));
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
                        rs.getString("display_name"), rs.getTimestamp("confirmed_at").toInstant()), visitId);
    }

    public RecordExport insertExport(UUID recordId, int versionNo, UUID confirmationId, UUID versionId,
                                     String format, UUID doctorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO record_export(id,record_id,version_id,confirmation_id,format,status,object_key,created_by)
                VALUES (?,?,?,?,?, 'SUCCEEDED', ?,?)
                """, id, recordId, versionId, confirmationId, format, "client-generated", doctorId);
        return exportsByVisit(visitIdOf(recordId)).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    public List<RecordExport> exportsByVisit(UUID visitId) {
        return jdbc.query("""
                SELECT e.id,e.record_id,ver.version_no,e.format,e.status,d.display_name,e.created_at
                FROM record_export e
                JOIN medical_record r ON r.id=e.record_id
                JOIN visit v ON v.id=r.visit_id
                JOIN medical_record_version ver ON ver.id=e.version_id AND ver.record_id=r.id
                JOIN doctor d ON d.id=e.created_by
                WHERE r.visit_id=? ORDER BY e.created_at DESC
                """, (rs,n) -> new RecordExport(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                        rs.getInt("version_no"),
                        rs.getString("format"), rs.getString("status"), rs.getString("display_name"),
                        rs.getTimestamp("created_at").toInstant()), visitId);
    }

    public RecordExport markSucceeded(UUID exportId, UUID doctorId) {
        jdbc.update("UPDATE record_export SET status='SUCCEEDED' WHERE id=?", exportId);
        return jdbc.query("""
                SELECT e.id,e.record_id,ver.version_no,e.format,e.status,d.display_name,e.created_at
                FROM record_export e JOIN medical_record r ON r.id=e.record_id
                JOIN visit v ON v.id=r.visit_id JOIN medical_record_version ver ON ver.id=e.version_id
                JOIN doctor d ON d.id=e.created_by WHERE e.id=? AND v.doctor_id=?
                """, (rs,n) -> new RecordExport(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                        rs.getInt("version_no"),
                        rs.getString("format"), rs.getString("status"), rs.getString("display_name"),
                        rs.getTimestamp("created_at").toInstant()), exportId, doctorId).stream().findFirst().orElseThrow();
    }

    public void audit(UUID doctorId, UUID visitId, String action, UUID resourceId) {
        jdbc.update("INSERT INTO audit_log(doctor_id,visit_id,action,resource_id) VALUES (?,?,?,?)",
                doctorId, visitId, action, resourceId);
    }

    private UUID visitIdOf(UUID recordId) {
        return jdbc.queryForObject("SELECT visit_id FROM medical_record WHERE id=?", UUID.class, recordId);
    }

    public record ConfirmationRow(UUID id, int versionNo, String doctorName, Instant confirmedAt) {}
    public record ExportRow(UUID id, int versionNo, String format, String status, String doctorName, Instant createdAt) {}
}

package com.medicalai.mapper;

import com.medicalai.domain.*;
import java.util.*;
import java.time.LocalDate;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class VisitMapper {
    private static final RowMapper<Visit> ROW = (rs,n) -> new Visit(
            rs.getObject("id",UUID.class),rs.getString("visit_no"),
            rs.getObject("patient_id",UUID.class),rs.getObject("doctor_id",UUID.class),
            rs.getString("status"),rs.getString("department_name"),
            rs.getString("patient_name_snapshot"),rs.getString("patient_gender_snapshot"),
            rs.getObject("patient_age_snapshot",Integer.class),rs.getString("doctor_name_snapshot"),
            rs.getString("chief_complaint"),rs.getInt("version"),DatabaseDateTime.getInstant(rs,"started_at"),
            DatabaseDateTime.getInstant(rs,"completed_at"),DatabaseDateTime.getInstant(rs,"created_at"));
    private final JdbcTemplate jdbc;

    public VisitMapper(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<Visit> findAll(UUID doctorId) {
        return jdbc.query("SELECT * FROM visit WHERE doctor_id=? ORDER BY created_at DESC,id", ROW, doctorId);
    }

    public List<Visit> findAll() {
        return jdbc.query("SELECT * FROM visit ORDER BY created_at DESC,id", ROW);
    }

    public Optional<Visit> find(UUID id, UUID doctorId, boolean lock) {
        return jdbc.query("SELECT * FROM visit WHERE id=? AND doctor_id=?" + (lock ? " FOR UPDATE" : ""),
                ROW,id,doctorId).stream().findFirst();
    }

    public UUID doctorId(UUID visitId) {
        return jdbc.queryForObject("SELECT doctor_id FROM visit WHERE id=?", UUID.class, visitId);
    }

    public Visit create(UUID id, Patient patient, Doctor doctor, Integer age, String chief, LocalDate date) {
        return jdbc.queryForObject("""
                INSERT INTO visit(id,visit_no,patient_id,doctor_id,department_id,department_name,visit_date,
                                  patient_name_snapshot,patient_gender_snapshot,patient_age_snapshot,
                                  patient_phone_snapshot,doctor_name_snapshot,chief_complaint,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING *
                """, ROW,id,"VIS-"+id.toString().replace("-","").toUpperCase(),
                patient.id(),doctor.id(),doctor.departmentId(),doctor.departmentName(),date,
                patient.name(),patient.gender(),age,patient.phoneMasked(),doctor.displayName(),chief,doctor.id(),doctor.id());
    }

    public boolean hasOtherActive(UUID doctorId, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM visit WHERE doctor_id=? AND status='ACTIVE' AND id<>?)",
                Boolean.class,doctorId,id));
    }

    public Optional<String> blockingStatusForPatient(UUID patientId, UUID doctorId) {
        return jdbc.query("""
                SELECT status FROM visit
                WHERE patient_id=? AND doctor_id=? AND status IN ('WAITING','ACTIVE','COMPLETED','ARCHIVED')
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, (rs, n) -> rs.getString("status"), patientId, doctorId).stream().findFirst();
    }

    public boolean hasConfirmedCurrentRecord(UUID visitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM medical_record r
                  JOIN medical_record_version v ON v.record_id=r.id AND v.version_no=r.current_version
                  JOIN medical_record_confirmation c ON c.record_id=r.id AND c.version_id=v.id
                  WHERE r.visit_id=? AND r.status='CONFIRMED' AND r.confirmed_version=r.current_version
                    AND c.declaration=true)
                """, Boolean.class,visitId));
    }

    public boolean hasSuccessfulExportForCurrentRecord(UUID visitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                  SELECT 1 FROM medical_record r
                  JOIN medical_record_version v ON v.record_id=r.id AND v.version_no=r.current_version
                  JOIN medical_record_confirmation c ON c.record_id=r.id AND c.version_id=v.id
                  JOIN record_export e ON e.record_id=r.id AND e.version_id=v.id AND e.confirmation_id=c.id
                  WHERE r.visit_id=? AND r.status='CONFIRMED' AND r.confirmed_version=r.current_version
                    AND c.declaration=true AND e.status='SUCCEEDED'
                    AND e.object_key IS NOT NULL AND e.object_key<>'' )
                """, Boolean.class, visitId));
    }

    public boolean hasOpenSession(UUID visitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM recording_session WHERE visit_id=? AND status IN ('OPEN','STOPPING'))
                """,Boolean.class,visitId));
    }

    public boolean hasIncompleteRecording(UUID visitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM recording WHERE visit_id=? AND status<>'DONE')
                """, Boolean.class, visitId));
    }

    public Visit updateStatus(UUID id, UUID doctorId, String status) {
        return jdbc.queryForObject("""
                UPDATE visit SET status=?,
                  started_at=CASE WHEN ?='ACTIVE' THEN COALESCE(started_at,medicalai_local_now()) ELSE started_at END,
                  completed_at=CASE WHEN ?='COMPLETED' THEN medicalai_local_now() ELSE completed_at END,
                  cancelled_at=CASE WHEN ?='CANCELLED' THEN medicalai_local_now() ELSE cancelled_at END,
                  updated_at=medicalai_local_now(),updated_by=?,version=version+1
                WHERE id=? AND doctor_id=? RETURNING *
                """,ROW,status,status,status,status,doctorId,id,doctorId);
    }

    /** 按外键安全顺序删除一次已取消接诊的全部持久化产物。 */
    public void deleteCancelledVisit(UUID visitId, UUID doctorId) {
        jdbc.update("DELETE FROM audit_log WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM ai_job WHERE visit_id=?", visitId);
        jdbc.update("""
                DELETE FROM record_export e USING medical_record r
                WHERE e.record_id=r.id AND r.visit_id=?
                """, visitId);
        jdbc.update("""
                DELETE FROM medical_record_confirmation c USING medical_record r
                WHERE c.record_id=r.id AND r.visit_id=?
                """, visitId);
        jdbc.update("""
                DELETE FROM medical_record_version v USING medical_record r
                WHERE v.record_id=r.id AND r.visit_id=?
                """, visitId);
        jdbc.update("DELETE FROM medical_record WHERE visit_id=?", visitId);
        // 提取版本以快照作为证据来源。取消接诊会清空整份问诊数据，必须先删除该引用，
        // 否则删除 dialogue_snapshot 时会触发外键保护，造成取消操作整体回滚。
        jdbc.update("""
                DELETE FROM clinical_extraction_version v USING clinical_extraction e
                WHERE v.extraction_id=e.id AND e.visit_id=?
                """, visitId);
        jdbc.update("DELETE FROM clinical_extraction WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM visit_transcript WHERE visit_id=?", visitId);
        jdbc.update("""
                DELETE FROM dialogue_snapshot_source s USING dialogue_snapshot d
                WHERE s.snapshot_id=d.id AND d.visit_id=?
                """, visitId);
        jdbc.update("DELETE FROM dialogue_snapshot WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM asr_utterance WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM recording_session WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM recording WHERE visit_id=?", visitId);
        jdbc.update("DELETE FROM visit WHERE id=? AND doctor_id=?", visitId, doctorId);
    }
}

package com.medicalai.mapper;

import com.medicalai.domain.Patient;
import com.medicalai.provider.PatientProfile;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class PatientMapper {
    private static final RowMapper<Patient> ROW = (rs, n) -> new Patient(
            rs.getObject("id", UUID.class), rs.getString("patient_no"), rs.getString("name"),
            rs.getString("gender"), parseDate(rs.getString("birth_date")),
            rs.getString("phone_masked"), rs.getString("id_no_masked"),
            rs.getString("department_name"), rs.getString("status"));
    private final JdbcTemplate jdbc;

    public PatientMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.substring(0, 10));
    }

    public List<Patient> find(String keyword) {
        String q = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                SELECT * FROM patient WHERE status='ACTIVE' AND (name ILIKE ? OR patient_no ILIKE ? OR id_no_masked ILIKE ?)
                ORDER BY patient_no,id
                """, ROW, q, q, q);
    }

    public Optional<Patient> findById(UUID id) {
        return jdbc.query("SELECT * FROM patient WHERE id=? AND status='ACTIVE'", ROW, id).stream().findFirst();
    }

    /** 创建新接诊时锁定患者，避免产生重复工作流。 */
    public Optional<Patient> findByIdForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM patient WHERE id=? AND status='ACTIVE' FOR UPDATE", ROW, id)
                .stream().findFirst();
    }

    public Patient insertManual(UUID id, String name, String gender, LocalDate birthDate,
                                String phoneMasked, String idNoMasked, String departmentName) {
        String sourcePatientId = id.toString();
        String patientNo = "M-" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        return jdbc.queryForObject("""
                INSERT INTO patient(id,source_system,source_patient_id,patient_no,name,gender,birth_date,
                                    phone_masked,id_no_masked,department_name,source_updated_at,raw_snapshot,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,medicalai_local_now(),'{"manual":true}','ACTIVE') RETURNING *
                """, ROW, id, "MANUAL", sourcePatientId, patientNo, name, gender,
                birthDate == null ? null : Date.valueOf(birthDate),
                phoneMasked, idNoMasked, departmentName);
    }

    public void upsert(PatientProfile p) {
        jdbc.update("""
                INSERT INTO patient(source_system,source_patient_id,patient_no,name,gender,
                                    birth_date,phone_masked,id_no_masked,department_name,source_updated_at,raw_snapshot)
                VALUES (?,?,?,?,?,?,?,?,'神经内科',medicalai_local_now(),'{"synthetic":true}')
                ON CONFLICT (source_system,source_patient_id) DO UPDATE SET
                    patient_no=EXCLUDED.patient_no,name=EXCLUDED.name,gender=EXCLUDED.gender,
                    birth_date=EXCLUDED.birth_date,phone_masked=EXCLUDED.phone_masked,id_no_masked=EXCLUDED.id_no_masked,
                    source_updated_at=EXCLUDED.source_updated_at,updated_at=medicalai_local_now()
                """, p.sourceSystem(), p.sourcePatientId(), p.patientNo(), p.name(), p.gender(),
                p.birthDate() == null ? null : Date.valueOf(p.birthDate()), p.phoneMasked(), p.idNoMasked());
    }
}

package com.medicalai.mapper;

import com.medicalai.domain.Patient;
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
            rs.getString("department_name"), rs.getString("status"), rs.getObject("created_by", UUID.class),
            rs.getString("created_by_name"));
    private final JdbcTemplate jdbc;

    private static final String SELECT_WITH_CREATOR = """
            SELECT p.*, creator.display_name AS created_by_name
            FROM patient p
            LEFT JOIN doctor creator ON creator.id=p.created_by
            """;

    public PatientMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.substring(0, 10));
    }

    public List<Patient> find(String keyword, UUID doctorId, boolean includeAll) {
        String q = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String scope = includeAll ? "" : " AND p.created_by=?";
        return jdbc.query(SELECT_WITH_CREATOR + """
                WHERE p.status='ACTIVE' AND (p.name ILIKE ? OR p.patient_no ILIKE ? OR p.id_no_masked ILIKE ?)
                """ + scope + " ORDER BY p.created_at DESC,p.id", ROW,
                includeAll ? new Object[]{q, q, q} : new Object[]{q, q, q, doctorId});
    }

    public Optional<Patient> findById(UUID id, UUID doctorId, boolean includeAll) {
        String scope = includeAll ? "" : " AND p.created_by=?";
        Object[] parameters = includeAll ? new Object[]{id} : new Object[]{id, doctorId};
        return jdbc.query(SELECT_WITH_CREATOR + " WHERE p.id=? AND p.status='ACTIVE'" + scope,
                ROW, parameters).stream().findFirst();
    }

    /** 工作流内部按接诊关联患者读取，不承担患者列表的数据范围判断。 */
    public Optional<Patient> findById(UUID id) {
        return jdbc.query(SELECT_WITH_CREATOR + " WHERE p.id=? AND p.status='ACTIVE'", ROW, id)
                .stream().findFirst();
    }

    /** 创建接诊时必须锁定且验证患者归属，防止通过患者 ID 越权建立接诊。 */
    public Optional<Patient> findOwnedByIdForUpdate(UUID id, UUID doctorId) {
        return jdbc.query(SELECT_WITH_CREATOR + " WHERE p.id=? AND p.created_by=? AND p.status='ACTIVE' FOR UPDATE OF p", ROW,
                id, doctorId)
                .stream().findFirst();
    }

    public Patient insertManual(UUID id, String name, String gender, LocalDate birthDate,
                                String phoneMasked, String idNoMasked, String departmentName, UUID createdBy) {
        String sourcePatientId = id.toString();
        String patientNo = "M-" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO patient(id,source_system,source_patient_id,patient_no,name,gender,birth_date,
                                    phone_masked,id_no_masked,department_name,created_by,source_updated_at,raw_snapshot,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,medicalai_local_now(),'{"manual":true}','ACTIVE')
                """, id, "MANUAL", sourcePatientId, patientNo, name, gender,
                birthDate == null ? null : Date.valueOf(birthDate),
                phoneMasked, idNoMasked, departmentName, createdBy);
        return findById(id).orElseThrow(() -> new IllegalStateException("患者创建后无法读取"));
    }
}

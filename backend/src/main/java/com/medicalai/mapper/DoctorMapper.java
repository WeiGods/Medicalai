package com.medicalai.mapper;

import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DoctorMapper {
    static final RowMapper<Doctor> ROW = (rs, n) -> new Doctor(
            rs.getObject("id", UUID.class), rs.getString("external_system"),
            rs.getString("external_user_id"), rs.getString("display_name"),
            rs.getString("department_id"), rs.getString("department_name"),
            rs.getString("role"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public DoctorMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Doctor upsertDemo(String externalId, String name, DoctorRole role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO doctor(id, external_system, external_user_id, display_name, department_name, role)
                VALUES (?, 'LOCAL_DEMO', ?, ?, '神经内科', ?)
                ON CONFLICT (external_system, external_user_id)
                DO NOTHING
                """, id, externalId, name, role.name());
        return jdbc.query("""
                SELECT * FROM doctor WHERE external_system='LOCAL_DEMO' AND external_user_id=?
                """, ROW, externalId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("演示医生账号创建后无法读取"));
    }

    /** 查询演示环境中的同名账号，角色限制由身份提供方统一处理。 */
    public List<Doctor> findLocalDemoByDisplayName(String displayName) {
        return jdbc.query("""
                SELECT * FROM doctor
                WHERE external_system='LOCAL_DEMO' AND display_name=?
                ORDER BY created_at, id
                """, ROW, displayName);
    }

    public void markLogin(UUID id) {
        jdbc.update("UPDATE doctor SET last_login_at=medicalai_local_now() WHERE id=?", id);
    }

    public void lock(UUID id) {
        jdbc.queryForObject("SELECT id FROM doctor WHERE id=?", UUID.class, id);
    }

    public Optional<Doctor> findById(UUID id) {
        return jdbc.query("SELECT * FROM doctor WHERE id=?", ROW, id).stream().findFirst();
    }
}

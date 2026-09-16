package com.medicalai.mapper;

import com.medicalai.domain.Doctor;
import java.util.*;
import org.springframework.jdbc.core.*;
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

    public Doctor upsertDemo(String externalId, String name) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO doctor(id, external_system, external_user_id, display_name, department_name)
                VALUES (?, 'LOCAL_DEMO', ?, ?, '神经内科')
                ON CONFLICT (external_system, external_user_id)
                DO UPDATE SET display_name=EXCLUDED.display_name, updated_at=medicalai_local_now()
                RETURNING *
                """, ROW, id, externalId, name);
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

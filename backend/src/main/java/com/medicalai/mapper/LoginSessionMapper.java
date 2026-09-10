package com.medicalai.mapper;

import com.medicalai.domain.AuthenticatedDoctor;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoginSessionMapper {
    private final JdbcTemplate jdbc;
    public LoginSessionMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(UUID doctorId, String tokenHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO login_session(doctor_id, login_type, access_token_hash, expires_at)
                VALUES (?, 'DEMO', ?, ?)
                """, doctorId, tokenHash, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    public Optional<AuthenticatedDoctor> authenticate(String tokenHash) {
        return jdbc.query("""
                SELECT d.*, s.id AS session_id FROM login_session s JOIN doctor d ON d.id=s.doctor_id
                WHERE s.access_token_hash=? AND s.revoked_at IS NULL
                  AND s.expires_at>now() AND d.status='ACTIVE'
                """, (rs, n) -> new AuthenticatedDoctor(
                rs.getObject("session_id", UUID.class), DoctorMapper.ROW.mapRow(rs, n)), tokenHash)
                .stream().findFirst();
    }

    public void touch(UUID sessionId) {
        jdbc.update("UPDATE login_session SET last_seen_at=now() WHERE id=? AND last_seen_at<now()-interval '1 minute'", sessionId);
    }

    public void revoke(UUID sessionId) {
        jdbc.update("UPDATE login_session SET revoked_at=COALESCE(revoked_at,now()) WHERE id=?", sessionId);
    }
}

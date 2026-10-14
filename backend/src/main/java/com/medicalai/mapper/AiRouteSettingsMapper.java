package com.medicalai.mapper;

import com.medicalai.domain.AiRouteSettings;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiRouteSettingsMapper {
    private final JdbcTemplate jdbc;

    public AiRouteSettingsMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Stored> find() {
        return jdbc.query("""
                SELECT s.route,s.updated_by,s.updated_at,d.display_name
                FROM ai_route_setting s LEFT JOIN doctor d ON d.id=s.updated_by WHERE s.id=1
                """, (rs, n) -> new Stored(rs.getString("route"), rs.getObject("updated_by", UUID.class),
                DatabaseDateTime.getInstant(rs, "updated_at"), rs.getString("display_name"))).stream().findFirst();
    }

    public Stored save(String route, UUID doctorId) {
        return jdbc.queryForObject("""
                INSERT INTO ai_route_setting(id,route,updated_by,updated_at) VALUES (1,?,?,medicalai_local_now())
                ON CONFLICT (id) DO UPDATE SET route=EXCLUDED.route,updated_by=EXCLUDED.updated_by,
                    updated_at=EXCLUDED.updated_at
                RETURNING route,updated_by,updated_at
                """, (rs, n) -> new Stored(rs.getString("route"), rs.getObject("updated_by", UUID.class),
                DatabaseDateTime.getInstant(rs, "updated_at"), null), route, doctorId);
    }

    public record Stored(String route, UUID updatedBy, Instant updatedAt, String updatedByName) { }
}

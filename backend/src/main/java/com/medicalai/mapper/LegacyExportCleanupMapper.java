package com.medicalai.mapper;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database half of the v1-v5 export retirement. Audit rows are intentionally never touched here. */
@Repository
public class LegacyExportCleanupMapper {
    static final String LEGACY_EXPORTS_SQL = """
            SELECT id,object_key FROM record_export
            WHERE template_revision_id IS NULL AND template_version BETWEEN 1 AND 5
            """;
    private final JdbcTemplate jdbc;

    public LegacyExportCleanupMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<LegacyExport> legacyExports() {
        return jdbc.query(LEGACY_EXPORTS_SQL,
                (rs, n) -> new LegacyExport(rs.getObject("id", UUID.class), rs.getString("object_key")));
    }

    public void deleteJobs(List<UUID> exportIds) {
        if (exportIds.isEmpty()) return;
        jdbc.update("DELETE FROM ai_job WHERE result_ref = ANY (?::uuid[])", uuidArray(exportIds));
    }

    public void deleteExports(List<UUID> exportIds) {
        if (exportIds.isEmpty()) return;
        jdbc.update("DELETE FROM record_export WHERE id = ANY (?::uuid[])", uuidArray(exportIds));
    }

    /** Once v1-v5 are gone, v6-v8 are fully represented by their immutable revision IDs. */
    public void clearLegacyVersionValues() {
        jdbc.update("UPDATE record_export SET template_version=NULL WHERE template_revision_id IS NOT NULL");
    }

    private String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    public record LegacyExport(UUID id, String objectKey) {}
}

package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.mockito.ArgumentCaptor;

class LegacyExportCleanupMapperTest {
    @Test
    void onlySelectsUnmappedLegacyVersionsForDestructiveCleanup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<RowMapper<LegacyExportCleanupMapper.LegacyExport>>any()))
                .thenReturn(List.of());

        new LegacyExportCleanupMapper(jdbc).legacyExports();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<LegacyExportCleanupMapper.LegacyExport>>any());
        String normalized = sql.getValue().replaceAll("\\s+", " ").trim();
        assertTrue(normalized.contains("template_revision_id IS NULL"));
        assertTrue(normalized.contains("template_version BETWEEN 1 AND 5"));
    }

    @Test
    void newRevisionExportsExplicitlyClearTheLegacyVersionColumn() {
        String normalized = MedicalRecordMapper.INSERT_REVISION_EXPORT_SQL.replaceAll("\\s+", " ").trim();

        assertTrue(normalized.contains("format,template_version,template_id,template_revision_id"));
        assertTrue(normalized.contains("VALUES (?,?,?,?,?,NULL,?,?,'PENDING',NULL,?)"));
    }
}

package com.medicalai.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.mapper.LegacyExportCleanupMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyExportCleanupServiceTest {
    @Test
    void removesOnlyLegacyExportsAndTheirJobsWhileLeavingAuditLogUntouched() {
        LegacyExportCleanupMapper mapper = mock(LegacyExportCleanupMapper.class);
        ExportFileService files = mock(ExportFileService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(mapper.legacyExports()).thenReturn(List.of(
                new LegacyExportCleanupMapper.LegacyExport(first, "exports/old.docx"),
                new LegacyExportCleanupMapper.LegacyExport(second, null)));
        LegacyExportCleanupService service = new LegacyExportCleanupService(mapper, files);

        service.removeLegacyExports();

        verify(files).deleteStoredExport("exports/old.docx");
        verify(mapper).deleteJobs(List.of(first, second));
        verify(mapper).deleteExports(List.of(first, second));
        verify(mapper).clearLegacyVersionValues();
    }
}

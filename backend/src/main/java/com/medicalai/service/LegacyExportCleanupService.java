package com.medicalai.service;

import com.medicalai.mapper.LegacyExportCleanupMapper;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyExportCleanupService {
    private final LegacyExportCleanupMapper exports;
    private final ExportFileService files;

    public LegacyExportCleanupService(LegacyExportCleanupMapper exports, ExportFileService files) {
        this.exports = exports;
        this.files = files;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanAtStartup() {
        removeLegacyExports();
    }

    @Transactional
    public void removeLegacyExports() {
        List<LegacyExportCleanupMapper.LegacyExport> legacy = exports.legacyExports();
        for (LegacyExportCleanupMapper.LegacyExport item : legacy) {
            if (item.objectKey() != null && item.objectKey().startsWith("exports/")) {
                files.deleteStoredExport(item.objectKey());
            }
        }
        List<java.util.UUID> ids = legacy.stream().map(LegacyExportCleanupMapper.LegacyExport::id).toList();
        exports.deleteJobs(ids);
        exports.deleteExports(ids);
        exports.clearLegacyVersionValues();
    }
}

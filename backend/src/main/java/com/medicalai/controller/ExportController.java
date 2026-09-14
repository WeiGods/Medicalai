package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.service.AudioStorageService;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {
    private final MedicalRecordMapper records;
    private final AudioStorageService storage;

    public ExportController(MedicalRecordMapper records, AudioStorageService storage) {
        this.records = records;
        this.storage = storage;
    }

    @GetMapping("/{exportId}")
    public MedicalRecordMapper.ExportDownload status(@PathVariable UUID exportId,
                                                     @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return records.findExportForDownload(exportId, current.doctor().id())
                .orElseThrow(BusinessException::notFound);
    }

    @GetMapping("/{exportId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID exportId,
                                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        var export = records.findExportForDownload(exportId, current.doctor().id())
                .orElseThrow(BusinessException::notFound);
        if (!"SUCCEEDED".equals(export.status()) || export.objectKey() == null || export.objectKey().isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,
                    "EXPORT_NOT_READY", "导出文件尚未生成完成");
        }
        Resource resource = storage.load(export.objectKey());
        MediaType type = "PDF".equalsIgnoreCase(export.format()) ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=medical-record-" + exportId
                        + ("PDF".equalsIgnoreCase(export.format()) ? ".pdf" : ".docx"))
                .body(resource);
    }
}

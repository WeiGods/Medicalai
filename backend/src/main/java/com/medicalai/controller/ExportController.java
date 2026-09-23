package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.service.ExportFileService;
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
    private final ExportFileService files;

    public ExportController(MedicalRecordMapper records, ExportFileService files) {
        this.records = records;
        this.files = files;
    }

    @GetMapping("/{exportId}")
    public MedicalRecordMapper.ExportDownload status(@PathVariable("exportId") UUID exportId,
                                                     @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return exportFor(current, exportId)
                .orElseThrow(BusinessException::notFound);
    }

    @GetMapping("/{exportId}/download")
    public ResponseEntity<Resource> download(@PathVariable("exportId") UUID exportId,
                                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        var export = exportFor(current, exportId)
                .orElseThrow(BusinessException::notFound);
        if (!"SUCCEEDED".equals(export.status()) || export.objectKey() == null || export.objectKey().isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,
                    "EXPORT_NOT_READY", "导出文件尚未生成完成");
        }
        // record_export.object_key 保存的是 ExportFileService 的本地相对路径，不是录音 MinIO 对象键。
        Resource resource;
        try {
            resource = files.load(export.objectKey());
        } catch (BusinessException error) {
            if (!"EXPORT_FILE_NOT_FOUND".equals(error.code())) throw error;
            resource = regenerateMissingExport(export);
        }
        MediaType type = "PDF".equalsIgnoreCase(export.format()) ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=medical-record-" + exportId
                        + ("PDF".equalsIgnoreCase(export.format()) ? ".pdf" : ".docx"))
                .body(resource);
    }

    /**
     * 历史导出记录只保存相对路径。部署切换、磁盘清理或异常中断可能使数据库仍为 SUCCEEDED，
     * 但本地文件已经不存在。此时不能把 404 直接交给医生：导出内容完全来自已确认病历，
     * 可以在已完成授权校验后即时重建同一 exportId 的文件并返回。
     */
    private Resource regenerateMissingExport(MedicalRecordMapper.ExportDownload export) {
        try {
            var payload = records.exportPayload(export.id()).orElseThrow(() -> new BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT, "EXPORT_REGENERATION_UNAVAILABLE",
                    "当前病历版本已变更，无法重新生成历史导出文件"));
            String regeneratedPath = files.generate(payload);
            // 生成路径必须仍与记录中的相对路径一致，避免错误版本文件覆盖当前下载请求。
            if (!export.objectKey().equals(regeneratedPath)) {
                throw new BusinessException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "EXPORT_REGENERATION_PATH_MISMATCH", "导出文件路径校验失败");
            }
            return files.load(regeneratedPath);
        } catch (BusinessException regenerationError) {
            // 即时重建也失败时才标记为失败，让前端后续重新提交异步导出任务。
            records.markExportFileMissing(export.id());
            throw regenerationError;
        }
    }

    private boolean isDepartmentHead(AuthenticatedDoctor current) {
        return current != null && DoctorRole.from(current.doctor().role()) == DoctorRole.DEPARTMENT_HEAD;
    }

    private java.util.Optional<MedicalRecordMapper.ExportDownload> exportFor(AuthenticatedDoctor current, UUID exportId) {
        return isDepartmentHead(current)
                ? records.findExportForDownload(exportId, current.doctor().id(), true)
                : records.findExportForDownload(exportId, current.doctor().id());
    }
}

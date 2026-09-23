package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.ConfirmMedicalRecordRequest;
import com.medicalai.dto.ExportMedicalRecordRequest;
import com.medicalai.dto.SaveMedicalRecordRequest;
import com.medicalai.service.ClinicalWorkflowService;
import com.medicalai.vo.ConfirmationVO;
import com.medicalai.vo.MedicalRecordVO;
import com.medicalai.vo.RecordExportVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/visits/{visitId}")
public class MedicalRecordController {
    private final ClinicalWorkflowService service;
    public MedicalRecordController(ClinicalWorkflowService service) { this.service = service; }

    @GetMapping("/medical-record")
    public MedicalRecordVO get(@PathVariable("visitId") UUID visitId,
                               @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.medicalRecord(visitId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
    }

    @PostMapping("/medical-record/generate")
    public MedicalRecordVO generate(@PathVariable("visitId") UUID visitId,
                                    @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.generate(visitId, current.doctor().id());
    }

    @PutMapping("/medical-record")
    public MedicalRecordVO save(@PathVariable("visitId") UUID visitId, @Valid @RequestBody SaveMedicalRecordRequest request,
                                @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.saveDraft(visitId, current.doctor().id(), request);
    }

    @PostMapping("/medical-record/edit")
    public MedicalRecordVO edit(@PathVariable("visitId") UUID visitId,
                                @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.editConfirmed(visitId, current.doctor().id());
    }

    @PostMapping("/medical-record/confirm")
    public MedicalRecordVO confirm(@PathVariable("visitId") UUID visitId, @Valid @RequestBody ConfirmMedicalRecordRequest request,
                                   HttpServletRequest request2,
                                   @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        String ip = request2.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request2.getRemoteAddr();
        return service.confirm(visitId, current.doctor().id(), request, ip);
    }

    @GetMapping("/confirmations")
    public List<ConfirmationVO> confirmations(@PathVariable("visitId") UUID visitId,
                                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.confirmations(visitId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
    }

    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<RecordExportVO> export(@PathVariable("visitId") UUID visitId,
                                       @Valid @RequestBody ExportMedicalRecordRequest request,
                                       @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.recordExport(visitId, current.doctor().id(), request);
    }

    @PostMapping("/exports/preview")
    public ResponseEntity<ByteArrayResource> previewExport(@PathVariable("visitId") UUID visitId,
                                                            @Valid @RequestBody ExportMedicalRecordRequest request,
                                                            @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        byte[] bytes = service.previewExport(visitId, current.doctor().id(), request);
        MediaType type = "PDF".equals(request.format()) ? MediaType.APPLICATION_PDF :
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok().contentType(type).body(new ByteArrayResource(bytes));
    }

    @PostMapping(value = "/exports/{exportId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<RecordExportVO> uploadExport(@PathVariable("visitId") UUID visitId,
                                             @PathVariable("exportId") UUID exportId,
                                             @RequestPart("file") MultipartFile file,
                                             @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.uploadExport(visitId, exportId, current.doctor().id(), file);
    }

    @GetMapping("/exports")
    public List<RecordExportVO> exports(@PathVariable("visitId") UUID visitId,
                                        @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.exports(visitId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
    }

    private void requireWrite(AuthenticatedDoctor current) {
        if (current == null || !DoctorRole.from(current.doctor().role()).canWriteClinicalData()) {
            throw new com.medicalai.exception.BusinessException(HttpStatus.FORBIDDEN,
                    "CLINICAL_READ_ONLY", "科室长只能查看接诊数据，不能执行修改操作");
        }
    }

}

package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/visits/{visitId}")
public class MedicalRecordController {
    private final ClinicalWorkflowService service;
    public MedicalRecordController(ClinicalWorkflowService service) { this.service = service; }

    @GetMapping("/medical-record")
    public MedicalRecordVO get(@PathVariable UUID visitId,
                               @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.medicalRecord(visitId, current.doctor().id());
    }

    @PostMapping("/medical-record/generate")
    public MedicalRecordVO generate(@PathVariable UUID visitId,
                                    @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.generate(visitId, current.doctor().id());
    }

    @PutMapping("/medical-record")
    public MedicalRecordVO save(@PathVariable UUID visitId, @Valid @RequestBody SaveMedicalRecordRequest request,
                                @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.saveDraft(visitId, current.doctor().id(), request);
    }

    @PostMapping("/medical-record/edit")
    public MedicalRecordVO edit(@PathVariable UUID visitId,
                                @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.editConfirmed(visitId, current.doctor().id());
    }

    @PostMapping("/medical-record/confirm")
    public MedicalRecordVO confirm(@PathVariable UUID visitId, @Valid @RequestBody ConfirmMedicalRecordRequest request,
                                   HttpServletRequest request2,
                                   @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        String ip = request2.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request2.getRemoteAddr();
        return service.confirm(visitId, current.doctor().id(), request, ip);
    }

    @GetMapping("/confirmations")
    public List<ConfirmationVO> confirmations(@PathVariable UUID visitId,
                                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.confirmations(visitId, current.doctor().id());
    }

    @PostMapping("/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<RecordExportVO> export(@PathVariable UUID visitId,
                                       @Valid @RequestBody ExportMedicalRecordRequest request,
                                       @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.recordExport(visitId, current.doctor().id(), request);
    }

    @GetMapping("/exports")
    public List<RecordExportVO> exports(@PathVariable UUID visitId,
                                        @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.exports(visitId, current.doctor().id());
    }

}

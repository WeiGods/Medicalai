package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.LlmRouteRequest;
import com.medicalai.service.ClinicalWorkflowService;
import com.medicalai.vo.ClinicalExtractionVO;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前转写快照的结构化提取与整体确认接口。 */
@RestController
@RequestMapping("/api/v1/visits/{visitId}/clinical-extraction")
public class ClinicalExtractionController {
    private final ClinicalWorkflowService service;

    public ClinicalExtractionController(ClinicalWorkflowService service) {
        this.service = service;
    }

    @GetMapping
    public ClinicalExtractionVO get(@PathVariable("visitId") UUID visitId,
                                    @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.clinicalExtraction(visitId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
    }

    @PostMapping("/generate")
    public ClinicalExtractionVO generate(@PathVariable("visitId") UUID visitId,
                                         @Valid @RequestBody(required = false) LlmRouteRequest request,
                                         @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.generateClinicalExtraction(visitId, current.doctor().id(),
                request == null ? null : request.provider());
    }

    @PostMapping("/confirm")
    public ClinicalExtractionVO confirm(@PathVariable("visitId") UUID visitId,
                                        @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.confirmClinicalExtraction(visitId, current.doctor().id());
    }

    private void requireWrite(AuthenticatedDoctor current) {
        if (current == null || !DoctorRole.from(current.doctor().role()).canWriteClinicalData()) {
            throw new com.medicalai.exception.BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "CLINICAL_READ_ONLY", "科室长只能查看接诊数据，不能执行修改操作");
        }
    }
}

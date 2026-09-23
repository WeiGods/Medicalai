package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.CreatePatientRequest;
import com.medicalai.service.PatientService;
import com.medicalai.vo.PatientVO;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {
    private final PatientService service;
    public PatientController(PatientService service) { this.service=service; }

    @GetMapping
    public List<PatientVO> list(@RequestParam(name = "keyword", defaultValue = "") String keyword,
                                @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.list(keyword, current.doctor());
    }

    @GetMapping("/{id}")
    public PatientVO get(@PathVariable("id") UUID id,
                         @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.get(id, current.doctor());
    }

    @PostMapping
    public PatientVO create(@Valid @RequestBody CreatePatientRequest request,
                            @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        if (current == null || !DoctorRole.from(current.doctor().role()).canWriteClinicalData()) {
            throw new com.medicalai.exception.BusinessException(HttpStatus.FORBIDDEN,
                    "CLINICAL_READ_ONLY", "科室长只能查看接诊数据，不能执行修改操作");
        }
        return service.create(request, current.doctor());
    }
}

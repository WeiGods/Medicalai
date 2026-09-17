package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.CreatePatientRequest;
import com.medicalai.service.PatientService;
import com.medicalai.vo.PatientVO;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {
    private final PatientService service;
    public PatientController(PatientService service) { this.service=service; }

    @GetMapping
    public List<PatientVO> list(@RequestParam(name = "keyword", defaultValue = "") String keyword) {
        return service.list(keyword);
    }

    @GetMapping("/{id}")
    public PatientVO get(@PathVariable("id") UUID id) { return service.get(id); }

    @PostMapping
    public PatientVO create(@Valid @RequestBody CreatePatientRequest request,
                            @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.create(request, current.doctor());
    }
}

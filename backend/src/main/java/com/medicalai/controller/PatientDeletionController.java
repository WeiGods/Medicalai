package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.PatientDeletionResult;
import com.medicalai.service.PatientDeletionService;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientDeletionController {
    private final PatientDeletionService service;

    public PatientDeletionController(PatientDeletionService service) {
        this.service = service;
    }

    @DeleteMapping("/{id}")
    public PatientDeletionResult delete(@PathVariable("id") UUID id,
                                        @RequestAttribute("currentDoctor") AuthenticatedDoctor current,
                                        HttpServletRequest request) {
        return service.delete(id, current.doctor(), clientIp(request));
    }

    @GetMapping("/deletions/{id}")
    public PatientDeletionResult status(@PathVariable("id") UUID id,
                                        @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.status(id, current.doctor());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].strip();
    }
}

package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.AuditLogQueryRequest;
import com.medicalai.service.AuditLogService;
import com.medicalai.vo.AuditLogPageVO;
import com.medicalai.vo.AuditOperatorVO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {
    private final AuditLogService service;

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    @GetMapping
    public AuditLogPageVO list(@Valid @ModelAttribute AuditLogQueryRequest request,
                               @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.list(current, request);
    }

    @GetMapping("/operators")
    public List<AuditOperatorVO> operators(@RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.operators(current);
    }
}

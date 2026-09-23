package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.CreateVisitRequest;
import com.medicalai.service.VisitService;
import com.medicalai.vo.VisitVO;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/visits")
public class VisitController {
    private final VisitService service;
    public VisitController(VisitService service) { this.service=service; }

    @GetMapping
    public List<VisitVO> list(@RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.list(current.doctor());
    }

    @GetMapping("/{id}")
    public VisitVO get(@PathVariable("id") UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current,
                       jakarta.servlet.http.HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        return service.get(id, current.doctor().id(), DoctorRole.from(current.doctor().role()), ip);
    }

    @PostMapping
    public VisitVO create(@Valid @RequestBody CreateVisitRequest request,
                          @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.create(request, current.doctor());
    }

    @PostMapping("/{id}/start")
    public VisitVO start(@PathVariable("id") UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.start(id, current.doctor().id());
    }

    @PostMapping("/{id}/complete")
    public VisitVO complete(@PathVariable("id") UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return service.complete(id, current.doctor().id());
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable("id") UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        service.cancel(id, current.doctor().id());
    }

    private void requireWrite(AuthenticatedDoctor current) {
        if (current == null || !DoctorRole.from(current.doctor().role()).canWriteClinicalData()) {
            throw new com.medicalai.exception.BusinessException(HttpStatus.FORBIDDEN,
                    "CLINICAL_READ_ONLY", "科室长只能查看接诊数据，不能执行修改操作");
        }
    }
}

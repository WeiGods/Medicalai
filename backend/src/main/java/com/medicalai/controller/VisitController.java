package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.CreateVisitRequest;
import com.medicalai.service.VisitService;
import com.medicalai.vo.VisitVO;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/visits")
public class VisitController {
    private final VisitService service;
    public VisitController(VisitService service) { this.service=service; }

    @GetMapping
    public List<VisitVO> list(@RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.list(current.doctor().id());
    }

    @GetMapping("/{id}")
    public VisitVO get(@PathVariable UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.get(id, current.doctor().id());
    }

    @PostMapping
    public VisitVO create(@Valid @RequestBody CreateVisitRequest request,
                          @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.create(request, current.doctor());
    }

    @PostMapping("/{id}/start")
    public VisitVO start(@PathVariable UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.start(id, current.doctor().id());
    }

    @PostMapping("/{id}/complete")
    public VisitVO complete(@PathVariable UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.complete(id, current.doctor().id());
    }

    @PostMapping("/{id}/cancel")
    public VisitVO cancel(@PathVariable UUID id, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.cancel(id, current.doctor().id());
    }
}

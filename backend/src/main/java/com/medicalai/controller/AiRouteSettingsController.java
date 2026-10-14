package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.AiRouteSettingsRequest;
import com.medicalai.service.AiRouteSettingsService;
import com.medicalai.vo.AiRouteSettingsVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings/ai-route")
public class AiRouteSettingsController {
    private final AiRouteSettingsService service;
    public AiRouteSettingsController(AiRouteSettingsService service) { this.service = service; }

    @GetMapping
    public AiRouteSettingsVO get() { return service.get(); }

    @PutMapping
    public AiRouteSettingsVO update(@Valid @RequestBody AiRouteSettingsRequest request,
                                    @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.update(current == null ? null : current.doctor(), request.route());
    }
}

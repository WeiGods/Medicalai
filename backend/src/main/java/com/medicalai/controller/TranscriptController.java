package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.SaveTranscriptRequest;
import com.medicalai.dto.LlmRouteRequest;
import com.medicalai.dto.UpdateUtteranceRoleRequest;
import com.medicalai.service.ClinicalWorkflowService;
import com.medicalai.vo.TranscriptVO;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/visits/{visitId}/transcript")
public class TranscriptController {
    private final ClinicalWorkflowService service;
    public TranscriptController(ClinicalWorkflowService service) { this.service = service; }

    @GetMapping
    public TranscriptVO get(@PathVariable("visitId") UUID visitId,
                            @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.transcript(visitId, current.doctor().id());
    }

    @PutMapping
    public TranscriptVO save(@PathVariable("visitId") UUID visitId, @Valid @RequestBody SaveTranscriptRequest request,
                             @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.saveTranscript(visitId, current.doctor().id(), request);
    }

    @PatchMapping("/utterances/{utteranceId}/role")
    public TranscriptVO updateRole(@PathVariable("visitId") UUID visitId, @PathVariable("utteranceId") UUID utteranceId,
                                   @Valid @RequestBody UpdateUtteranceRoleRequest request,
                                   @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.updateUtteranceRole(visitId, current.doctor().id(), utteranceId, request);
    }

    @PostMapping("/roles/reclassify")
    public TranscriptVO reclassifyRoles(@PathVariable("visitId") UUID visitId,
                                         @Valid @RequestBody(required = false) LlmRouteRequest request,
                                         @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return service.reclassifyTranscriptRoles(visitId, current.doctor().id(),
                request == null ? null : request.provider());
    }
}

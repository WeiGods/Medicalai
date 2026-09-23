package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.CreateExportTemplateRequest;
import com.medicalai.dto.ExportTemplateDraftPreviewRequest;
import com.medicalai.dto.ExportTemplatePreviewRequest;
import com.medicalai.dto.SaveExportTemplateRevisionRequest;
import com.medicalai.dto.RestoreExportTemplateRevisionRequest;
import com.medicalai.dto.SetExportTemplateStatusRequest;
import com.medicalai.service.ExportFileService;
import com.medicalai.service.ExportTemplateService;
import com.medicalai.vo.ExportTemplateRevisionVO;
import com.medicalai.vo.ExportTemplateVO;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/template-management/templates")
public class TemplateManagementController {
    private final ExportTemplateService templates;
    private final ExportFileService files;

    public TemplateManagementController(ExportTemplateService templates, ExportFileService files) {
        this.templates = templates;
        this.files = files;
    }

    @GetMapping
    public List<ExportTemplateVO> list(@RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return templates.managementTemplates(role(current)).stream().map(item -> ExportTemplateVO.from(item, true)).toList();
    }

    @GetMapping("/{templateId}")
    public ExportTemplateVO detail(@PathVariable UUID templateId, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return ExportTemplateVO.from(templates.template(templateId, role(current)), true);
    }

    @PostMapping
    public ExportTemplateVO create(@Valid @RequestBody CreateExportTemplateRequest request,
                                   @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return ExportTemplateVO.from(templates.create(request.name(), request.description(), request.definitionJson(),
                current.doctor().id(), role(current)), true);
    }

    @PostMapping("/preview")
    public ResponseEntity<ByteArrayResource> previewDraft(@Valid @RequestBody ExportTemplateDraftPreviewRequest request,
                                                           @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        String format = request.resolvedFormat();
        templates.previewDefinition(request.definitionJson(), role(current));
        return previewResponse(format, request.definitionJson());
    }

    @PutMapping("/{templateId}")
    public ExportTemplateRevisionVO save(@PathVariable UUID templateId, @Valid @RequestBody SaveExportTemplateRevisionRequest request,
                                         @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return ExportTemplateRevisionVO.from(templates.saveRevision(templateId, request.currentRevisionId(), request.definitionJson(),
                current.doctor().id(), role(current)));
    }

    @PatchMapping("/{templateId}/status")
    public void status(@PathVariable UUID templateId, @RequestBody SetExportTemplateStatusRequest request,
                       @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        templates.setStatus(templateId, request.enabled(), role(current));
    }

    @PatchMapping("/{templateId}/default")
    public void makeDefault(@PathVariable UUID templateId, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        templates.setDefault(templateId, role(current));
    }

    @DeleteMapping("/{templateId}")
    public void delete(@PathVariable UUID templateId, @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        templates.deleteTemplate(templateId, role(current));
    }

    @GetMapping("/{templateId}/revisions")
    public List<ExportTemplateRevisionVO> revisions(@PathVariable UUID templateId,
                                                     @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return templates.revisions(templateId, role(current)).stream().map(ExportTemplateRevisionVO::from).toList();
    }

    @PostMapping("/{templateId}/revisions/{revisionId}/restore")
    public ExportTemplateRevisionVO restore(@PathVariable UUID templateId, @PathVariable UUID revisionId,
                                            @Valid @RequestBody RestoreExportTemplateRevisionRequest request,
                                            @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return ExportTemplateRevisionVO.from(templates.restoreRevision(templateId, request.currentRevisionId(), revisionId,
                current.doctor().id(), role(current)));
    }

    @PostMapping("/{templateId}/preview")
    public ResponseEntity<ByteArrayResource> preview(@PathVariable UUID templateId,
                                                      @Valid @RequestBody ExportTemplatePreviewRequest request,
                                                      @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        var template = templates.template(templateId, role(current));
        String format = request.resolvedFormat();
        return previewResponse(format, template.revision().definitionJson());
    }

    private ResponseEntity<ByteArrayResource> previewResponse(String format, String definitionJson) {
        byte[] body = files.preview(format, definitionJson);
        MediaType mediaType = "PDF".equals(format) ? MediaType.APPLICATION_PDF :
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok().contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("template-preview." + format.toLowerCase()).build().toString())
                .body(new ByteArrayResource(body));
    }

    private DoctorRole role(AuthenticatedDoctor current) { return DoctorRole.from(current.doctor().role()); }
}

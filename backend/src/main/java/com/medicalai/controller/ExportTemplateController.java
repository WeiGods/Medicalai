package com.medicalai.controller;

import com.medicalai.service.ExportTemplateService;
import com.medicalai.vo.ExportTemplateVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/export-templates")
public class ExportTemplateController {
    private final ExportTemplateService templates;

    public ExportTemplateController(ExportTemplateService templates) { this.templates = templates; }

    /** Doctors can only discover enabled templates; definitions remain an implementation detail. */
    @GetMapping
    public List<ExportTemplateVO> activeTemplates() {
        return templates.activeTemplates().stream().map(item -> ExportTemplateVO.from(item, false)).toList();
    }
}

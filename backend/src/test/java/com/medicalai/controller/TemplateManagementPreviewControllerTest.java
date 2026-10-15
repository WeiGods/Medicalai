package com.medicalai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.dto.ExportTemplateDraftPreviewRequest;
import com.medicalai.service.ExportFileService;
import com.medicalai.service.ExportTemplateService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemplateManagementPreviewControllerTest {
    @Test
    void previewsAnUnsavedDefinitionWithoutResolvingOrSavingATemplateRevision() {
        ExportTemplateService templates = mock(ExportTemplateService.class);
        ExportFileService files = mock(ExportFileService.class);
        TemplateManagementController controller = new TemplateManagementController(templates, files);
        AuthenticatedDoctor head = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(UUID.randomUUID(), "DEMO", "head",
                "科室长", null, null, "DEPARTMENT_HEAD", "ACTIVE"));
        String definition = "{\"documentTitle\":\"草稿病历\"}";
        when(files.preview("PDF", definition)).thenReturn(new byte[] {'%', 'P', 'D', 'F'});

        var response = controller.previewDraft(new ExportTemplateDraftPreviewRequest("PDF", definition), head);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        verify(templates).previewDefinition(definition, com.medicalai.domain.DoctorRole.DEPARTMENT_HEAD);
        verify(files).preview("PDF", definition);
    }

    @Test
    void delegatesTemplateDeletionToTheManagerOnlyServicePath() {
        ExportTemplateService templates = mock(ExportTemplateService.class);
        TemplateManagementController controller = new TemplateManagementController(templates, mock(ExportFileService.class));
        AuthenticatedDoctor head = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(UUID.randomUUID(), "DEMO", "head",
                "科室长", null, null, "DEPARTMENT_HEAD", "ACTIVE"));
        UUID templateId = UUID.randomUUID();

        controller.delete(templateId, head);

        verify(templates).deleteTemplate(templateId, com.medicalai.domain.DoctorRole.DEPARTMENT_HEAD);
    }
}

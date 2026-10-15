package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.DoctorRole;
import com.medicalai.domain.ExportTemplate;
import com.medicalai.domain.ExportTemplateRevision;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.ExportTemplateMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportTemplateServiceTest {
    @Test
    void onlyDepartmentHeadCanSaveAndARevisionImmediatelyBecomesCurrent() {
        ExportTemplateMapper mapper = mock(ExportTemplateMapper.class);
        ExportTemplateDefinitionValidator validator = mock(ExportTemplateDefinitionValidator.class);
        ExportTemplateService service = new ExportTemplateService(mapper, validator);
        UUID templateId = UUID.randomUUID();
        UUID currentRevisionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ExportTemplate template = new ExportTemplate(templateId, "v6", "紧凑单页", "", "ACTIVE", true,
                currentRevisionId, Instant.now(), actorId);
        when(mapper.findForUpdate(templateId)).thenReturn(template);
        when(mapper.nextRevisionNo(templateId)).thenReturn(2);
        when(mapper.insertRevision(any(), any(), anyInt(), any(), any())).thenAnswer(invocation ->
                new ExportTemplateRevision(UUID.randomUUID(), templateId, 2, invocation.getArgument(3), actorId, Instant.now()));

        ExportTemplateRevision saved = service.saveRevision(templateId, currentRevisionId, definition(), actorId,
                DoctorRole.DEPARTMENT_HEAD);

        assertEquals(2, saved.revisionNo());
        verify(mapper).setCurrentRevision(templateId, saved.id());
        BusinessException forbidden = assertThrows(BusinessException.class, () -> service.saveRevision(templateId,
                currentRevisionId, definition(), actorId, DoctorRole.ADMIN));
        assertEquals("TEMPLATE_MANAGEMENT_FORBIDDEN", forbidden.code());
    }

    @Test
    void rejectsSaveWhenEditorUsedAnOldRevision() {
        ExportTemplateMapper mapper = mock(ExportTemplateMapper.class);
        ExportTemplateService service = new ExportTemplateService(mapper, new ExportTemplateDefinitionValidator());
        UUID templateId = UUID.randomUUID();
        when(mapper.findForUpdate(templateId)).thenReturn(new ExportTemplate(templateId, "v6", "紧凑单页", "",
                "ACTIVE", true, UUID.randomUUID(), Instant.now(), UUID.randomUUID()));

        BusinessException conflict = assertThrows(BusinessException.class, () -> service.saveRevision(templateId,
                UUID.randomUUID(), definition(), UUID.randomUUID(), DoctorRole.DEPARTMENT_HEAD));

        assertEquals("TEMPLATE_REVISION_CONFLICT", conflict.code());
    }

    @Test
    void validatesDraftPreviewOnlyForDepartmentHeadsWithoutCreatingARevision() {
        ExportTemplateMapper mapper = mock(ExportTemplateMapper.class);
        ExportTemplateDefinitionValidator validator = mock(ExportTemplateDefinitionValidator.class);
        ExportTemplateService service = new ExportTemplateService(mapper, validator);

        service.previewDefinition(definition(), DoctorRole.DEPARTMENT_HEAD);

        verify(validator).validate(definition());
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.previewDefinition(definition(), DoctorRole.DOCTOR));
        assertEquals("TEMPLATE_MANAGEMENT_FORBIDDEN", forbidden.code());
        verify(mapper, org.mockito.Mockito.never()).insertRevision(any(), any(), anyInt(), any(), any());
    }

    @Test
    void deletesAUserTemplateLogicallyWithoutRemovingItsRevisionHistory() {
        ExportTemplateMapper mapper = mock(ExportTemplateMapper.class);
        ExportTemplateService service = new ExportTemplateService(mapper, new ExportTemplateDefinitionValidator());
        UUID templateId = UUID.randomUUID();
        ExportTemplate template = new ExportTemplate(templateId, "custom-rounding", "测试模板", "", "ACTIVE", false,
                UUID.randomUUID(), Instant.now(), UUID.randomUUID());
        when(mapper.findForUpdate(templateId)).thenReturn(template);

        service.deleteTemplate(templateId, DoctorRole.DEPARTMENT_HEAD);

        verify(mapper).setStatus(templateId, "DELETED");
    }

    @Test
    void doesNotDeleteTheDefaultOrSystemTemplates() {
        ExportTemplateMapper mapper = mock(ExportTemplateMapper.class);
        ExportTemplateService service = new ExportTemplateService(mapper, new ExportTemplateDefinitionValidator());
        UUID defaultId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();
        when(mapper.findForUpdate(defaultId)).thenReturn(new ExportTemplate(defaultId, "custom-default", "默认模板", "",
                "ACTIVE", true, UUID.randomUUID(), Instant.now(), UUID.randomUUID()));
        when(mapper.findForUpdate(systemId)).thenReturn(new ExportTemplate(systemId, "v6", "紧凑单页", "",
                "ACTIVE", false, UUID.randomUUID(), Instant.now(), UUID.randomUUID()));

        assertEquals("TEMPLATE_DEFAULT_REQUIRED", assertThrows(BusinessException.class,
                () -> service.deleteTemplate(defaultId, DoctorRole.DEPARTMENT_HEAD)).code());
        assertEquals("TEMPLATE_SYSTEM_PROTECTED", assertThrows(BusinessException.class,
                () -> service.deleteTemplate(systemId, DoctorRole.DEPARTMENT_HEAD)).code());
        assertEquals("TEMPLATE_MANAGEMENT_FORBIDDEN", assertThrows(BusinessException.class,
                () -> service.deleteTemplate(UUID.randomUUID(), DoctorRole.DOCTOR)).code());
        verify(mapper, org.mockito.Mockito.never()).setStatus(defaultId, "DELETED");
        verify(mapper, org.mockito.Mockito.never()).setStatus(systemId, "DELETED");
    }

    private static String definition() {
        return ExportTemplateSeeds.initialTemplates().getFirst().definitionJson();
    }
}

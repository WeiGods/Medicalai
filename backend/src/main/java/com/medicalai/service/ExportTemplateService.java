package com.medicalai.service;

import com.medicalai.domain.DoctorRole;
import com.medicalai.domain.ExportTemplate;
import com.medicalai.domain.ExportTemplateRevision;
import com.medicalai.domain.ExportTemplateWithRevision;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.ExportTemplateMapper;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportTemplateService {
    private static final Set<String> SYSTEM_TEMPLATE_KEYS = Set.of("v6", "v7", "v8");
    private final ExportTemplateMapper templates;
    private final ExportTemplateDefinitionValidator validator;

    public ExportTemplateService(ExportTemplateMapper templates, ExportTemplateDefinitionValidator validator) {
        this.templates = templates;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<ExportTemplateWithRevision> activeTemplates() { return templates.activeTemplates(); }

    @Transactional(readOnly = true)
    public List<ExportTemplateWithRevision> managementTemplates(DoctorRole role) {
        requireManager(role);
        return templates.allTemplates();
    }

    @Transactional(readOnly = true)
    public ExportTemplateWithRevision template(UUID templateId, DoctorRole role) {
        requireManager(role);
        return templates.template(templateId).orElseThrow(BusinessException::notFound);
    }

    @Transactional(readOnly = true)
    public List<ExportTemplateRevision> revisions(UUID templateId, DoctorRole role) {
        requireManager(role);
        template(templateId, role);
        return templates.revisions(templateId);
    }

    @Transactional
    public ExportTemplateWithRevision create(String name, String description, String definitionJson, UUID actorId, DoctorRole role) {
        requireManager(role);
        validator.validate(definitionJson);
        String cleanName = requiredName(name);
        UUID templateId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        templates.insertTemplate(templateId, "custom-" + templateId.toString().substring(0, 8), cleanName,
                description == null ? "" : description.strip(), templates.activeTemplates().isEmpty(), actorId);
        templates.insertRevision(revisionId, templateId, 1, definitionJson, actorId);
        templates.setCurrentRevision(templateId, revisionId);
        return templates.template(templateId).orElseThrow();
    }

    @Transactional
    public ExportTemplateRevision saveRevision(UUID templateId, UUID expectedRevisionId, String definitionJson,
                                               UUID actorId, DoctorRole role) {
        requireManager(role);
        validator.validate(definitionJson);
        ExportTemplate template = templates.findForUpdate(templateId);
        if (template == null) throw BusinessException.notFound();
        requireNotDeleted(template);
        if (!template.currentRevisionId().equals(expectedRevisionId)) {
            throw BusinessException.conflict("TEMPLATE_REVISION_CONFLICT", "模板已被其他人更新，请刷新后再保存");
        }
        ExportTemplateRevision revision = templates.insertRevision(UUID.randomUUID(), templateId,
                templates.nextRevisionNo(templateId), definitionJson, actorId);
        templates.setCurrentRevision(templateId, revision.id());
        return revision;
    }

    @Transactional
    public ExportTemplateRevision restoreRevision(UUID templateId, UUID expectedRevisionId, UUID sourceRevisionId,
                                                  UUID actorId, DoctorRole role) {
        requireManager(role);
        ExportTemplateRevision source = templates.revision(templateId, sourceRevisionId).orElseThrow(BusinessException::notFound);
        return saveRevision(templateId, expectedRevisionId, source.definitionJson(), actorId, role);
    }

    @Transactional
    public void setStatus(UUID templateId, boolean enabled, DoctorRole role) {
        requireManager(role);
        ExportTemplate template = templates.findForUpdate(templateId);
        if (template == null) throw BusinessException.notFound();
        requireNotDeleted(template);
        if (!enabled && template.defaultTemplate()) {
            throw new BusinessException(HttpStatus.CONFLICT, "TEMPLATE_DEFAULT_REQUIRED", "默认模板不能停用，请先设置另一个启用模板为默认模板");
        }
        templates.setStatus(templateId, enabled ? "ACTIVE" : "DISABLED");
    }

    @Transactional
    public void setDefault(UUID templateId, DoctorRole role) {
        requireManager(role);
        ExportTemplate template = templates.findForUpdate(templateId);
        if (template == null) throw BusinessException.notFound();
        requireNotDeleted(template);
        if (!"ACTIVE".equals(template.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "TEMPLATE_DISABLED", "停用模板不能设为默认模板");
        }
        templates.setDefault(templateId);
    }

    /**
     * Removes a custom template from operational lists while retaining its immutable revisions and export references.
     * Historical files can therefore still be downloaded or regenerated with the frozen revision that produced them.
     */
    @Transactional
    public void deleteTemplate(UUID templateId, DoctorRole role) {
        requireManager(role);
        ExportTemplate template = templates.findForUpdate(templateId);
        if (template == null || "DELETED".equals(template.status())) throw BusinessException.notFound();
        if (SYSTEM_TEMPLATE_KEYS.contains(template.templateKey())) {
            throw new BusinessException(HttpStatus.CONFLICT, "TEMPLATE_SYSTEM_PROTECTED", "系统初始模板不能删除");
        }
        if (template.defaultTemplate()) {
            throw new BusinessException(HttpStatus.CONFLICT, "TEMPLATE_DEFAULT_REQUIRED", "默认模板不能删除，请先设置另一个启用模板为默认模板");
        }
        templates.setStatus(templateId, "DELETED");
    }

    @Transactional(readOnly = true)
    public ExportTemplateWithRevision activeRevision(UUID revisionId) {
        return templates.activeRevision(revisionId).orElseThrow(() ->
                new BusinessException(HttpStatus.BAD_REQUEST, "TEMPLATE_REVISION_UNAVAILABLE", "所选模板不存在或已停用"));
    }

    /** Validates a draft for a transient preview. It never creates a revision or changes any template pointer. */
    @Transactional(readOnly = true)
    public void previewDefinition(String definitionJson, DoctorRole role) {
        requireManager(role);
        validator.validate(definitionJson);
    }

    private void requireManager(DoctorRole role) {
        if (role == null || !role.canManageTemplates()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "TEMPLATE_MANAGEMENT_FORBIDDEN", "仅科室长可以维护导出模板");
        }
    }

    private void requireNotDeleted(ExportTemplate template) {
        if ("DELETED".equals(template.status())) throw BusinessException.notFound();
    }

    private String requiredName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > 80) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TEMPLATE_NAME_INVALID", "模板名称长度必须在 1 到 80 个字符之间");
        }
        return value;
    }
}

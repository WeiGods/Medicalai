package com.medicalai.mapper;

import com.medicalai.domain.ExportTemplate;
import com.medicalai.domain.ExportTemplateRevision;
import com.medicalai.domain.ExportTemplateWithRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExportTemplateMapper {
    private final JdbcTemplate jdbc;

    public ExportTemplateMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ExportTemplateWithRevision> activeTemplates() {
        return list("WHERE t.status='ACTIVE'");
    }

    public List<ExportTemplateWithRevision> allTemplates() {
        return list("WHERE t.status <> 'DELETED'");
    }

    public Optional<ExportTemplateWithRevision> template(UUID id) {
        return list("WHERE t.id=? AND t.status <> 'DELETED'", id).stream().findFirst();
    }

    public Optional<ExportTemplateWithRevision> activeRevision(UUID revisionId) {
        return jdbc.query("""
                SELECT t.id AS template_id,t.template_key AS template_key,t.name AS template_name,t.description AS template_description,t.status AS template_status,t.is_default AS template_is_default,t.current_revision_id AS template_current_revision_id,
                       t.created_at AS template_created_at,t.created_by AS template_created_by,
                       r.id AS revision_id,r.template_id AS revision_template_id,r.revision_no,r.definition_json,
                       r.created_by AS revision_created_by,r.created_at AS revision_created_at
                FROM export_template_revision r
                JOIN export_template t ON t.id=r.template_id AND t.current_revision_id=r.id
                WHERE r.id=? AND t.status='ACTIVE'
                """, this::joined, revisionId).stream().findFirst();
    }

    public Optional<ExportTemplateRevision> revision(UUID templateId, UUID revisionId) {
        return jdbc.query("""
                SELECT id,template_id,revision_no,definition_json,created_by,created_at
                FROM export_template_revision WHERE template_id=? AND id=?
                """, (rs, n) -> revision(rs), templateId, revisionId).stream().findFirst();
    }

    public List<ExportTemplateRevision> revisions(UUID templateId) {
        return jdbc.query("""
                SELECT id,template_id,revision_no,definition_json,created_by,created_at
                FROM export_template_revision WHERE template_id=? ORDER BY revision_no DESC
                """, (rs, n) -> revision(rs), templateId);
    }

    public ExportTemplate findForUpdate(UUID id) {
        return jdbc.query("""
                SELECT id,template_key,name,description,status,is_default,current_revision_id,created_at,created_by
                FROM export_template WHERE id=? FOR UPDATE
                """, (rs, n) -> template(rs), id).stream().findFirst().orElse(null);
    }

    public int nextRevisionNo(UUID templateId) {
        Integer value = jdbc.queryForObject("SELECT coalesce(max(revision_no),0)+1 FROM export_template_revision WHERE template_id=?",
                Integer.class, templateId);
        return value == null ? 1 : value;
    }

    public ExportTemplateRevision insertRevision(UUID id, UUID templateId, int revisionNo, String definitionJson, UUID createdBy) {
        jdbc.update("""
                INSERT INTO export_template_revision(id,template_id,revision_no,definition_json,created_by)
                VALUES (?,?,?,?::jsonb,?)
                """, id, templateId, revisionNo, definitionJson, createdBy);
        return revision(templateId, id).orElseThrow();
    }

    public void setCurrentRevision(UUID templateId, UUID revisionId) {
        jdbc.update("UPDATE export_template SET current_revision_id=?,updated_at=medicalai_local_now() WHERE id=?", revisionId, templateId);
    }

    public ExportTemplate insertTemplate(UUID id, String key, String name, String description, boolean defaultTemplate,
                                         UUID createdBy) {
        if (defaultTemplate) jdbc.update("UPDATE export_template SET is_default=false WHERE is_default=true");
        jdbc.update("""
                INSERT INTO export_template(id,template_key,name,description,status,is_default,current_revision_id,created_by)
                VALUES (?,?,?,?,'ACTIVE',?,NULL,?)
                """, id, key, name, description, defaultTemplate, createdBy);
        return findForUpdate(id);
    }

    public void setStatus(UUID templateId, String status) {
        jdbc.update("UPDATE export_template SET status=?,updated_at=medicalai_local_now() WHERE id=?", status, templateId);
    }

    public void setDefault(UUID templateId) {
        jdbc.update("UPDATE export_template SET is_default=false WHERE is_default=true");
        jdbc.update("UPDATE export_template SET is_default=true,updated_at=medicalai_local_now() WHERE id=? AND status='ACTIVE'", templateId);
    }

    private List<ExportTemplateWithRevision> list(String where, Object... args) {
        return jdbc.query("""
                SELECT t.id AS template_id,t.template_key AS template_key,t.name AS template_name,t.description AS template_description,t.status AS template_status,t.is_default AS template_is_default,t.current_revision_id AS template_current_revision_id,
                       t.created_at AS template_created_at,t.created_by AS template_created_by,
                       r.id AS revision_id,r.template_id AS revision_template_id,r.revision_no,r.definition_json,
                       r.created_by AS revision_created_by,r.created_at AS revision_created_at
                FROM export_template t JOIN export_template_revision r ON r.id=t.current_revision_id
                %s ORDER BY t.is_default DESC,t.name
                """.formatted(where), this::joined, args);
    }

    private ExportTemplateWithRevision joined(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ExportTemplateWithRevision(new ExportTemplate(rs.getObject("template_id", UUID.class),
                rs.getString("template_key"), rs.getString("template_name"), rs.getString("template_description"),
                rs.getString("template_status"), rs.getBoolean("template_is_default"),
                rs.getObject("template_current_revision_id", UUID.class), DatabaseDateTime.getInstant(rs, "template_created_at"),
                rs.getObject("template_created_by", UUID.class)), new ExportTemplateRevision(rs.getObject("revision_id", UUID.class),
                rs.getObject("revision_template_id", UUID.class), rs.getInt("revision_no"), rs.getString("definition_json"),
                rs.getObject("revision_created_by", UUID.class), DatabaseDateTime.getInstant(rs, "revision_created_at")));
    }

    private ExportTemplate template(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExportTemplate(rs.getObject("id", UUID.class), rs.getString("template_key"),
                rs.getString("name"), rs.getString("description"), rs.getString("status"),
                rs.getBoolean("is_default"), rs.getObject("current_revision_id", UUID.class),
                DatabaseDateTime.getInstant(rs, "created_at"), rs.getObject("created_by", UUID.class));
    }

    private ExportTemplateRevision revision(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExportTemplateRevision(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("revision_no"), rs.getString("definition_json"), rs.getObject("created_by", UUID.class),
                DatabaseDateTime.getInstant(rs, "created_at"));
    }
}

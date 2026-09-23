package com.medicalai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates the deliberately small, data-only export template format. */
@Component
public class ExportTemplateDefinitionValidator {
    private static final Set<String> ROOT_FIELDS = Set.of("documentTitle", "layout", "page", "style",
            "headerEnabled", "footerEnabled", "appearance", "sections");
    private static final Set<String> PAGE_FIELDS = Set.of("marginTop", "marginRight", "marginBottom", "marginLeft");
    private static final Set<String> STYLE_FIELDS = Set.of("fontSize", "lineHeight", "labelSize", "sectionSize", "cellPadding");
    private static final Set<String> SECTION_FIELDS = Set.of("key", "title", "layout", "fields");
    private static final Set<String> FIELD_FIELDS = Set.of("key", "label", "visible");
    private static final Set<String> FIELDS = Set.of("name", "gender", "age", "phone", "chief", "present",
            "past", "opinion", "medication", "followup", "doctor", "date", "visit_no", "record_version",
            "confirmed_at");
    private static final Set<String> CORE_FIELDS = Set.of("name", "gender", "age", "phone", "chief", "doctor", "date");
    private static final Set<String> LAYOUTS = Set.of("MERGED_TABLE", "SEPARATE_TABLES");
    private static final Set<String> SECTION_LAYOUTS = Set.of("GRID_2", "TABLE");
    private static final Set<String> THEMES = Set.of("MEDICAL_GREEN", "CLINICAL_BLUE", "MINIMAL_GRAY");
    private static final Set<String> BORDERS = Set.of("THIN");
    private static final Set<String> SECTION_HEADERS = Set.of("SHADED_ROW", "TEXT_LABEL", "NONE");
    private static final Set<String> HEADER_CONTENTS = Set.of("NONE", "TITLE", "TITLE_WITH_VERSION");
    private static final Set<String> FOOTER_CONTENTS = Set.of("NONE", "PAGE_X_OF_Y");
    private static final Set<String> ALIGNMENTS = Set.of("LEFT", "CENTER", "RIGHT", "SPLIT");
    private static final Set<String> METADATA_POSITIONS = Set.of("NONE", "BELOW_TITLE");
    private static final Set<String> METADATA_FIELDS = Set.of("VISIT_NO", "RECORD_VERSION", "CONFIRMED_AT");
    private final ObjectMapper objectMapper;

    public ExportTemplateDefinitionValidator() { this(new ObjectMapper()); }

    public ExportTemplateDefinitionValidator(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public JsonNode validate(String definitionJson) {
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (root == null || !root.isObject()) invalid("TEMPLATE_DEFINITION_INVALID", "模板定义必须是 JSON 对象");
            allowedFields(root, ROOT_FIELDS, "TEMPLATE_DEFINITION_INVALID", "模板包含不支持的定义字段");
            nonBlank(root, "documentTitle");
            allowed(root.path("layout").asText(), LAYOUTS, "TEMPLATE_LAYOUT_INVALID", "文档布局无效");
            page(root.path("page"));
            style(root.path("style"));
            if (!root.path("headerEnabled").isBoolean() || !root.path("footerEnabled").isBoolean()) {
                invalid("TEMPLATE_DEFINITION_INVALID", "页眉页脚开关无效");
            }
            if (root.has("appearance")) appearance(root.path("appearance"));

            JsonNode sections = root.path("sections");
            if (!sections.isArray() || sections.isEmpty() || sections.size() > 8) {
                invalid("TEMPLATE_SECTION_INVALID", "模板章节数量必须在 1 到 8 之间");
            }
            Set<String> seenSections = new HashSet<>();
            Set<String> seenFields = new HashSet<>();
            Set<String> visibleFields = new HashSet<>();
            for (JsonNode section : sections) {
                allowedFields(section, SECTION_FIELDS, "TEMPLATE_DEFINITION_INVALID", "章节包含不支持的字段");
                String sectionKey = nonBlank(section, "key");
                if (!seenSections.add(sectionKey)) invalid("TEMPLATE_SECTION_DUPLICATE", "模板章节不能重复");
                nonBlank(section, "title");
                allowed(section.path("layout").asText(), SECTION_LAYOUTS, "TEMPLATE_SECTION_LAYOUT_INVALID", "章节布局无效");
                JsonNode fields = section.path("fields");
                if (!fields.isArray() || fields.isEmpty() || fields.size() > 20) {
                    invalid("TEMPLATE_FIELD_INVALID", "每个章节必须包含 1 到 20 个字段");
                }
                for (JsonNode field : fields) {
                    allowedFields(field, FIELD_FIELDS, "TEMPLATE_FIELD_INVALID", "字段包含不支持的属性");
                    String key = nonBlank(field, "key");
                    if (!FIELDS.contains(key)) invalid("TEMPLATE_FIELD_INVALID", "模板包含不支持的字段：" + key);
                    if (!seenFields.add(key)) invalid("TEMPLATE_FIELD_DUPLICATE", "模板字段不能重复：" + key);
                    nonBlank(field, "label");
                    if (!field.path("visible").isBoolean()) invalid("TEMPLATE_FIELD_INVALID", "字段显示状态无效");
                    if (field.path("visible").asBoolean()) visibleFields.add(key);
                }
            }
            if (!seenFields.containsAll(CORE_FIELDS) || !visibleFields.containsAll(CORE_FIELDS)) {
                invalid("TEMPLATE_CORE_FIELD_REQUIRED", "模板必须包含完整的核心病历字段");
            }
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TEMPLATE_DEFINITION_INVALID", "模板定义不是合法 JSON", exception);
        }
    }

    private void page(JsonNode page) {
        if (!page.isObject()) invalid("TEMPLATE_STYLE_OUT_OF_RANGE", "页面边距无效");
        allowedFields(page, PAGE_FIELDS, "TEMPLATE_DEFINITION_INVALID", "页面包含不支持的属性");
        between(page, "marginTop", 36, 90);
        between(page, "marginRight", 36, 90);
        between(page, "marginBottom", 36, 90);
        between(page, "marginLeft", 36, 90);
    }

    private void style(JsonNode style) {
        if (!style.isObject()) invalid("TEMPLATE_STYLE_OUT_OF_RANGE", "模板样式无效");
        allowedFields(style, STYLE_FIELDS, "TEMPLATE_DEFINITION_INVALID", "样式包含不支持的属性");
        between(style, "fontSize", 8, 16);
        between(style, "lineHeight", 10, 28);
        between(style, "labelSize", 7, 16);
        between(style, "sectionSize", 8, 18);
        between(style, "cellPadding", 0, 12);
    }

    private void appearance(JsonNode appearance) {
        if (!appearance.isObject()) invalid("TEMPLATE_APPEARANCE_INVALID", "模板外观无效");
        allowedFields(appearance, Set.of("theme", "table", "docx", "pdf"), "TEMPLATE_APPEARANCE_INVALID", "外观包含不支持的属性");
        allowed(appearance.path("theme").asText(), THEMES, "TEMPLATE_APPEARANCE_INVALID", "主题色板无效");
        table(appearance.path("table"));
        formatAppearance(appearance.path("docx"), "DOCX");
        formatAppearance(appearance.path("pdf"), "PDF");
    }

    private void table(JsonNode table) {
        if (!table.isObject()) invalid("TEMPLATE_APPEARANCE_INVALID", "表格外观无效");
        allowedFields(table, Set.of("border", "cellPadding", "labelColumnRatio", "sectionHeader"),
                "TEMPLATE_APPEARANCE_INVALID", "表格包含不支持的属性");
        allowed(table.path("border").asText(), BORDERS, "TEMPLATE_APPEARANCE_INVALID", "表格边框无效");
        between(table, "cellPadding", 0, 12);
        between(table, "labelColumnRatio", 20, 40);
        allowed(table.path("sectionHeader").asText(), SECTION_HEADERS, "TEMPLATE_APPEARANCE_INVALID", "分节标题样式无效");
    }

    private void formatAppearance(JsonNode format, String kind) {
        if (!format.isObject()) invalid("TEMPLATE_APPEARANCE_INVALID", kind + " 版式无效");
        allowedFields(format, Set.of("header", "footer", "metadata", "basicInfo"),
                "TEMPLATE_APPEARANCE_INVALID", kind + " 版式包含不支持的属性");
        header(format.path("header"), kind);
        footer(format.path("footer"));
        metadata(format.path("metadata"));
        if (!"INLINE_PAIR".equals(format.path("basicInfo").asText())) {
            invalid("TEMPLATE_APPEARANCE_INVALID", "基本信息展示方式无效");
        }
    }

    private void header(JsonNode header, String kind) {
        if (!header.isObject()) invalid("TEMPLATE_APPEARANCE_INVALID", "页眉配置无效");
        allowedFields(header, Set.of("enabled", "content", "alignment"), "TEMPLATE_APPEARANCE_INVALID", "页眉包含不支持的属性");
        if (!header.path("enabled").isBoolean()) invalid("TEMPLATE_APPEARANCE_INVALID", "页眉开关无效");
        String content = header.path("content").asText();
        String alignment = header.path("alignment").asText();
        allowed(content, HEADER_CONTENTS, "TEMPLATE_APPEARANCE_INVALID", "页眉内容无效");
        allowed(alignment, ALIGNMENTS, "TEMPLATE_APPEARANCE_INVALID", "页眉对齐无效");
        if (!header.path("enabled").asBoolean() && !"NONE".equals(content)) invalid("TEMPLATE_APPEARANCE_INVALID", "关闭页眉时不能保留页眉内容");
        if (header.path("enabled").asBoolean() && "NONE".equals(content)) invalid("TEMPLATE_APPEARANCE_INVALID", "启用页眉时必须选择页眉内容");
        if ("DOCX".equals(kind) && ("TITLE_WITH_VERSION".equals(content) || "SPLIT".equals(alignment))) {
            invalid("TEMPLATE_APPEARANCE_INVALID", "DOCX 页眉不支持该内容或对齐方式");
        }
        if ("SPLIT".equals(alignment) && !"TITLE_WITH_VERSION".equals(content)) {
            invalid("TEMPLATE_APPEARANCE_INVALID", "分列页眉仅支持标题和版本号");
        }
    }

    private void footer(JsonNode footer) {
        if (!footer.isObject()) invalid("TEMPLATE_APPEARANCE_INVALID", "页脚配置无效");
        allowedFields(footer, Set.of("enabled", "content", "alignment"), "TEMPLATE_APPEARANCE_INVALID", "页脚包含不支持的属性");
        if (!footer.path("enabled").isBoolean()) invalid("TEMPLATE_APPEARANCE_INVALID", "页脚开关无效");
        String content = footer.path("content").asText();
        allowed(content, FOOTER_CONTENTS, "TEMPLATE_APPEARANCE_INVALID", "页脚内容无效");
        allowed(footer.path("alignment").asText(), Set.of("LEFT", "CENTER", "RIGHT"), "TEMPLATE_APPEARANCE_INVALID", "页脚对齐无效");
        if (!footer.path("enabled").asBoolean() && !"NONE".equals(content)) invalid("TEMPLATE_APPEARANCE_INVALID", "关闭页脚时不能保留页脚内容");
        if (footer.path("enabled").asBoolean() && "NONE".equals(content)) invalid("TEMPLATE_APPEARANCE_INVALID", "启用页脚时必须选择页脚内容");
    }

    private void metadata(JsonNode metadata) {
        if (!metadata.isObject()) invalid("TEMPLATE_APPEARANCE_INVALID", "元数据配置无效");
        allowedFields(metadata, Set.of("position", "fields"), "TEMPLATE_APPEARANCE_INVALID", "元数据包含不支持的属性");
        String position = metadata.path("position").asText();
        allowed(position, METADATA_POSITIONS, "TEMPLATE_APPEARANCE_INVALID", "元数据位置无效");
        JsonNode fields = metadata.path("fields");
        if (!fields.isArray()) invalid("TEMPLATE_APPEARANCE_INVALID", "元数据字段无效");
        Set<String> seen = new HashSet<>();
        for (JsonNode field : fields) {
            String name = field.asText();
            allowed(name, METADATA_FIELDS, "TEMPLATE_APPEARANCE_INVALID", "元数据字段无效");
            if (!seen.add(name)) invalid("TEMPLATE_APPEARANCE_INVALID", "元数据字段不能重复");
        }
        if ("NONE".equals(position) && !fields.isEmpty()) invalid("TEMPLATE_APPEARANCE_INVALID", "隐藏元数据时不能保留字段");
        if ("BELOW_TITLE".equals(position) && fields.isEmpty()) invalid("TEMPLATE_APPEARANCE_INVALID", "显示元数据时必须选择字段");
    }

    private void between(JsonNode node, String property, double min, double max) {
        JsonNode value = node.path(property);
        if (!value.isNumber() || value.asDouble() < min || value.asDouble() > max) {
            invalid("TEMPLATE_STYLE_OUT_OF_RANGE", "模板样式超出允许范围：" + property);
        }
    }

    private String nonBlank(JsonNode node, String property) {
        String value = node.path(property).asText("").strip();
        if (value.isEmpty() || value.length() > 80) invalid("TEMPLATE_DEFINITION_INVALID", "模板缺少有效的 " + property);
        return value;
    }

    private void allowedFields(JsonNode node, Set<String> fields, String code, String message) {
        if (!node.isObject()) invalid(code, message);
        node.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) invalid(code, message + "：" + field);
        });
    }

    private void allowed(String value, Set<String> allowed, String code, String message) {
        if (!allowed.contains(value)) invalid(code, message);
    }

    private void invalid(String code, String message) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}

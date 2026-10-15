package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medicalai.exception.BusinessException;
import org.junit.jupiter.api.Test;

class ExportTemplateDefinitionValidatorTest {
    private final ExportTemplateDefinitionValidator validator = new ExportTemplateDefinitionValidator();

    @Test
    void acceptsAllThreeInitialDefinitions() {
        assertEquals(3, ExportTemplateSeeds.initialTemplates().size());
        ExportTemplateSeeds.initialTemplates().forEach(seed ->
                assertDoesNotThrow(() -> validator.validate(seed.definitionJson())));
    }

    @Test
    void rejectsUnknownOrRepeatedFields() {
        BusinessException unknown = assertThrows(BusinessException.class, () ->
                validator.validate(validDefinition().replace("\"chief\"", "\"unsafe_script\"")));
        assertEquals("TEMPLATE_FIELD_INVALID", unknown.code());

        BusinessException duplicate = assertThrows(BusinessException.class, () ->
                validator.validate(validDefinition().replace("\"present\"", "\"chief\"")));
        assertEquals("TEMPLATE_FIELD_DUPLICATE", duplicate.code());
    }

    @Test
    void rejectsDefinitionsMissingRequiredClinicalFieldsOrOutOfRangeStyle() {
        BusinessException missing = assertThrows(BusinessException.class, () ->
                validator.validate(validDefinition().replace("{\"key\":\"chief\",\"label\":\"患者主诉\",\"visible\":true},", "")));
        assertEquals("TEMPLATE_CORE_FIELD_REQUIRED", missing.code());

        BusinessException style = assertThrows(BusinessException.class, () ->
                validator.validate(validDefinition().replace("\"fontSize\":10", "\"fontSize\":42")));
        assertEquals("TEMPLATE_STYLE_OUT_OF_RANGE", style.code());
    }

    @Test
    void acceptsControlledAppearanceAndRejectsUnknownOrUnsafeAppearanceValues() {
        assertDoesNotThrow(() -> validator.validate(completeDefinition()));

        BusinessException unknown = assertThrows(BusinessException.class, () ->
                validator.validate(completeDefinition().replace("\"theme\":\"MEDICAL_GREEN\"",
                        "\"theme\":\"MEDICAL_GREEN\",\"css\":\"body{display:none}\"")));
        assertEquals("TEMPLATE_APPEARANCE_INVALID", unknown.code());

        BusinessException theme = assertThrows(BusinessException.class, () ->
                validator.validate(completeDefinition().replace("MEDICAL_GREEN", "RAINBOW_SCRIPT")));
        assertEquals("TEMPLATE_APPEARANCE_INVALID", theme.code());

        BusinessException ratio = assertThrows(BusinessException.class, () ->
                validator.validate(completeDefinition().replace("\"labelColumnRatio\":24", "\"labelColumnRatio\":48")));
        assertEquals("TEMPLATE_STYLE_OUT_OF_RANGE", ratio.code());

        BusinessException combination = assertThrows(BusinessException.class, () ->
                validator.validate(completeDefinition().replace("\"enabled\":true,\"content\":\"TITLE\"",
                        "\"enabled\":false,\"content\":\"TITLE\"")));
        assertEquals("TEMPLATE_APPEARANCE_INVALID", combination.code());
    }

    private String validDefinition() {
        return """
                {"documentTitle":"门诊病历","layout":"MERGED_TABLE",
                 "page":{"marginTop":60,"marginRight":54,"marginBottom":50,"marginLeft":54},
                 "style":{"fontSize":10,"lineHeight":14,"labelSize":9,"sectionSize":10,"cellPadding":4},
                 "headerEnabled":true,"footerEnabled":true,
                 "sections":[
                   {"key":"basic","title":"一、基本信息","layout":"GRID_2","fields":[
                     {"key":"name","label":"患者姓名","visible":true},
                     {"key":"gender","label":"性别","visible":true},
                     {"key":"age","label":"年龄","visible":true},
                     {"key":"phone","label":"联系方式","visible":true}]},
                   {"key":"visit","title":"二、就诊内容","layout":"TABLE","fields":[
                     {"key":"chief","label":"患者主诉","visible":true},
                     {"key":"present","label":"患者现病史","visible":true},
                     {"key":"past","label":"患者既往史","visible":true}]},
                   {"key":"treatment","title":"三、诊疗记录","layout":"TABLE","fields":[
                     {"key":"opinion","label":"医生处理意见","visible":true},
                     {"key":"medication","label":"用药情况","visible":true},
                     {"key":"followup","label":"复诊建议","visible":true}]},
                   {"key":"sign","title":"四、签署信息","layout":"TABLE","fields":[
                     {"key":"doctor","label":"接诊医生","visible":true},
                     {"key":"date","label":"接诊日期","visible":true}]}
                 ]}
                """;
    }

    private String completeDefinition() {
        return validDefinition().replace("\"headerEnabled\":true,\"footerEnabled\":true,",
                """
                \"headerEnabled\":true,\"footerEnabled\":true,
                \"appearance\":{
                  \"theme\":\"MEDICAL_GREEN\",
                  \"table\":{\"border\":\"THIN\",\"cellPadding\":4,\"labelColumnRatio\":24,\"sectionHeader\":\"SHADED_ROW\"},
                  \"docx\":{\"header\":{\"enabled\":true,\"content\":\"TITLE\",\"alignment\":\"CENTER\"},\"footer\":{\"enabled\":true,\"content\":\"PAGE_X_OF_Y\",\"alignment\":\"CENTER\"},\"metadata\":{\"position\":\"BELOW_TITLE\",\"fields\":[\"VISIT_NO\",\"RECORD_VERSION\",\"CONFIRMED_AT\"]},\"basicInfo\":\"INLINE_PAIR\"},
                  \"pdf\":{\"header\":{\"enabled\":true,\"content\":\"TITLE_WITH_VERSION\",\"alignment\":\"SPLIT\"},\"footer\":{\"enabled\":true,\"content\":\"PAGE_X_OF_Y\",\"alignment\":\"CENTER\"},\"metadata\":{\"position\":\"BELOW_TITLE\",\"fields\":[\"VISIT_NO\",\"CONFIRMED_AT\"]},\"basicInfo\":\"INLINE_PAIR\"}
                },
                """);
    }
}

package com.medicalai.service;

import java.util.List;

/** Built-in data used only to initialise the global template library. */
public final class ExportTemplateSeeds {
    private ExportTemplateSeeds() {}

    public static List<Seed> initialTemplates() {
        return List.of(
                new Seed("v6", "紧凑单页", "合并表格、小字号，正常病历单页呈现", true,
                        definition("MERGED_TABLE", 9.5, 13.5, 9, 10, 4, 60, 54, 50, 54, "SHADED_ROW")),
                new Seed("v7", "标准病历", "独立节标题与分节表格，经典文档层次", false,
                        definition("SEPARATE_TABLES", 10.5, 16, 9.5, 13, 7, 72, 54, 62, 54, "TEXT_LABEL")),
                new Seed("v8", "宽松阅读", "字号与行距更大，可读性优先，长内容自动分页", false,
                        definition("MERGED_TABLE", 11, 18, 10.5, 11.5, 8, 66, 58, 58, 58, "SHADED_ROW")));
    }

    private static String definition(String layout, double fontSize, double lineHeight, double labelSize,
                                     double sectionSize, int padding, int top, int right, int bottom, int left,
                                     String sectionHeader) {
        return """
                {"documentTitle":"门诊病历","layout":"%s","page":{"marginTop":%s,"marginRight":%s,"marginBottom":%s,"marginLeft":%s},"style":{"fontSize":%s,"lineHeight":%s,"labelSize":%s,"sectionSize":%s,"cellPadding":%s},"headerEnabled":true,"footerEnabled":true,"appearance":{"theme":"MEDICAL_GREEN","table":{"border":"THIN","cellPadding":%s,"labelColumnRatio":24,"sectionHeader":"%s"},"docx":{"header":{"enabled":true,"content":"TITLE","alignment":"CENTER"},"footer":{"enabled":true,"content":"PAGE_X_OF_Y","alignment":"CENTER"},"metadata":{"position":"BELOW_TITLE","fields":["VISIT_NO","RECORD_VERSION","CONFIRMED_AT"]},"basicInfo":"INLINE_PAIR"},"pdf":{"header":{"enabled":true,"content":"TITLE_WITH_VERSION","alignment":"SPLIT"},"footer":{"enabled":true,"content":"PAGE_X_OF_Y","alignment":"CENTER"},"metadata":{"position":"BELOW_TITLE","fields":["VISIT_NO","CONFIRMED_AT"]},"basicInfo":"INLINE_PAIR"}},"sections":[
                {"key":"basic","title":"一、基本信息","layout":"GRID_2","fields":[{"key":"name","label":"患者姓名","visible":true},{"key":"gender","label":"性别","visible":true},{"key":"age","label":"年龄","visible":true},{"key":"phone","label":"联系方式","visible":true}]},
                {"key":"visit","title":"二、就诊内容","layout":"TABLE","fields":[{"key":"chief","label":"患者主诉","visible":true},{"key":"present","label":"患者现病史","visible":true},{"key":"past","label":"患者既往史","visible":true}]},
                {"key":"treatment","title":"三、诊疗记录","layout":"TABLE","fields":[{"key":"opinion","label":"医生处理意见","visible":true},{"key":"medication","label":"用药情况","visible":true},{"key":"followup","label":"复诊建议","visible":true}]},
                {"key":"sign","title":"四、签署信息","layout":"TABLE","fields":[{"key":"doctor","label":"接诊医生","visible":true},{"key":"date","label":"接诊日期","visible":true},{"key":"record_version","label":"病历版本","visible":true},{"key":"confirmed_at","label":"确认时间","visible":true}]}]}
                """.formatted(layout, top, right, bottom, left, fontSize, lineHeight, labelSize, sectionSize, padding, padding, sectionHeader);
    }

    public record Seed(String key, String name, String description, boolean defaultTemplate, String definitionJson) {}
}

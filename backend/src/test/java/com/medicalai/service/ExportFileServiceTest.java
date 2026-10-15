package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper.ExportPayload;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportFileServiceTest {
    @TempDir Path root;

    @Test
    void generatesEditableChineseDocxAndEmbeddedChinesePdfFromEffectiveContent() throws Exception {
        ExportFileService files = new ExportFileService(root.toString(), new ObjectMapper());

        byte[] docx = generate(files, payload("DOCX", "患者现病史内容"));
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String text = documentText(document);
            assertTrue(text.contains("门诊病历"));
            assertTrue(text.contains("基本信息"));
            assertTrue(text.contains("患者姓名：病历患者"));
            assertTrue(text.contains("患者主诉：编辑后的主诉"));
            assertTrue(text.contains("接诊医生：病历医生"));
            assertEquals(1, occurrences(text, "病历版本：v1"));
            assertEquals(1, occurrences(text, "确认时间：2026-09-17 10:30:00"));
            assertTrue(!text.contains("chief："));
            assertTrue(!text.contains("确认信息"));
        }

        byte[] pdf = generate(files, payload("PDF", "患者现病史内容"));
        try (PDDocument document = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("门诊病历"));
            assertTrue(text.contains("病历患者"));
            assertTrue(text.contains("编辑后的主诉"));
            assertEquals(1, occurrences(text, "病历版本：v1"));
            assertEquals(1, occurrences(text, "确认时间：2026-09-17 10:30:00"));
            assertTrue(hasEmbeddedFont(document));
            assertTrue(!text.contains("确认信息"));
        }
    }

    @Test
    void wrapsLongChineseContentAcrossPdfPages() throws Exception {
        ExportFileService files = new ExportFileService(root.toString(), new ObjectMapper());
        String present = "患者反复头痛伴恶心，需继续观察。".repeat(900);
        byte[] pdf = generate(files, payload("PDF", present));

        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() > 1);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("患者反复头痛伴恶心"));
        }
    }

    @Test
    void rejectsPathsOutsideLocalExportDirectory() {
        ExportFileService files = new ExportFileService(root.toString(), new ObjectMapper());

        BusinessException error = assertThrows(BusinessException.class, () -> files.load("../application.yml"));

        assertEquals("EXPORT_FILE_NOT_FOUND", error.code());
    }

    @Test
    void rendersTheStoredStructuredTemplateInsteadOfABrowserHardcodedLayout() throws Exception {
        ExportFileService files = new ExportFileService(root.toString(), new ObjectMapper());
        ExportPayload payload = new ExportPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VIS-002", 2, "DOCX", "{\"name\":\"模板患者\",\"gender\":\"男\",\"age\":40,\"phone\":\"1\",\"chief\":\"头痛\",\"doctor\":\"医生\",\"date\":\"2026-09-22\"}",
                null, Instant.parse("2026-09-22T02:30:00Z"), structuredDefinition());

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(generate(files, payload)))) {
            String text = documentText(document);
            assertTrue(text.contains("自定义门诊记录"));
            assertTrue(text.contains("本次症状"));
        }
    }

    @Test
    void appliesStructuredLayoutAndHeaderFooterToBothFormats() throws Exception {
        ExportFileService files = new ExportFileService(root.toString(), new ObjectMapper());
        ExportPayload merged = structuredPayload("DOCX", "MERGED_TABLE", true, true);
        ExportPayload separate = structuredPayload("DOCX", "SEPARATE_TABLES", false, false);

        byte[] mergedDocx = generate(files, merged);
        byte[] separateDocx = generate(files, separate);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(mergedDocx))) {
            assertEquals(1, document.getTables().size(), "合并布局应使用单个连续表格");
            assertTrue(document.getHeaderList().stream().anyMatch(header -> header.getText().contains("自定义门诊记录")));
            assertTrue(document.getFooterList().stream().anyMatch(footer -> footer.getText().contains("电子病历导出")));
        }
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(separateDocx))) {
            assertTrue(document.getTables().size() > 1, "分节布局应为每个章节创建独立表格");
            assertTrue(document.getHeaderList().isEmpty());
            assertTrue(document.getFooterList().isEmpty());
        }

        byte[] mergedPdf = files.render(structuredPayload("PDF", "MERGED_TABLE", true, true));
        byte[] separatePdf = files.render(structuredPayload("PDF", "SEPARATE_TABLES", false, false));
        try (PDDocument document = PDDocument.load(mergedPdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("电子病历导出"));
            assertTrue(text.contains("本次症状"));
        }
        try (PDDocument document = PDDocument.load(separatePdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(!text.contains("电子病历导出"));
        }
        assertTrue(!Arrays.equals(mergedPdf, separatePdf), "两种整体布局应生成不同的 PDF 结构");
    }

    @Test
    void rendersEachSeededV6ToV8DefinitionAsDocxAndPdf() {
        ExportFileService files = new ExportFileService(root.toString(), new ObjectMapper());
        for (ExportTemplateSeeds.Seed seed : ExportTemplateSeeds.initialTemplates()) {
            assertTrue(files.preview("DOCX", seed.definitionJson()).length > 4, seed.key() + " DOCX");
            byte[] pdf = files.preview("PDF", seed.definitionJson());
            assertTrue(pdf.length > 4 && pdf[0] == '%', seed.key() + " PDF");
        }
    }

    @Test
    void preservesTheMedicalGreenVisualStructureForTheSeededTemplates() throws Exception {
        ExportFileService files = new ExportFileService(root.toString(), new ObjectMapper());

        for (ExportTemplateSeeds.Seed seed : ExportTemplateSeeds.initialTemplates()) {
            byte[] docx = files.preview("DOCX", seed.definitionJson());
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
                XWPFTable firstTable = document.getTables().getFirst();
                assertTrue(Arrays.equals(new byte[] {(byte) 0xD8, (byte) 0xE4, (byte) 0xDC},
                        (byte[]) firstTable.getCTTbl().getTblPr().getTblBorders().getTop().getColor()), seed.key());
                assertTrue(firstTable.getRow(0).getCell(0).getCTTc().getTcPr().isSetShd() || "v7".equals(seed.key()),
                        seed.key() + " should use a shaded table section row unless it is the separate-table preset");
                if (!"v7".equals(seed.key())) {
                    assertEquals(2, firstTable.getRow(0).getCell(0).getCTTc().getTcPr().getGridSpan().getVal().intValue());
                }
                assertTrue(archiveEntries(docx).stream()
                        .anyMatch(footer -> footer.contains("PAGE") && footer.contains("NUMPAGES")));
            }

            try (PDDocument document = PDDocument.load(files.preview("PDF", seed.definitionJson()))) {
                String content = new String(document.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertTrue(content.contains(" RG") || content.contains(" rg"), seed.key() + " should draw themed borders or fills");
                String text = new PDFTextStripper().getText(document);
                assertTrue(text.contains("接诊编号："), seed.key());
            }
        }
    }

    private PDFont font(PDDocument document, org.apache.pdfbox.cos.COSName name) {
        try {
            return document.getPage(0).getResources().getFont(name);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private List<String> archiveEntries(byte[] document) throws java.io.IOException {
        List<String> entries = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(document))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().startsWith("word/footer")) entries.add(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private boolean hasEmbeddedFont(PDDocument document) {
        for (org.apache.pdfbox.cos.COSName name : document.getPage(0).getResources().getFontNames()) {
            if (font(document, name).isEmbedded()) return true;
        }
        return false;
    }

    private String documentText(XWPFDocument document) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : document.getParagraphs()) text.append(paragraph.getText());
        for (XWPFTable table : document.getTables()) {
            for (var row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) text.append(cell.getText());
            }
        }
        return text.toString();
    }

    private int occurrences(String text, String token) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(token, offset)) >= 0; offset += token.length()) count++;
        return count;
    }

    private byte[] generate(ExportFileService files, ExportPayload payload) throws java.io.IOException {
        String storedPath = files.generate(payload);
        byte[] downloaded = files.load(storedPath).getContentAsByteArray();
        assertTrue(storedPath.startsWith("exports/"));
        assertTrue(downloaded.length > 0);
        return downloaded;
    }

    private ExportPayload payload(String format, String present) {
        String content = """
                {"name":"病历患者","gender":"女","age":53,"phone":"13800000000",
                 "chief":"原始主诉","present":"%s","past":"无特殊既往史",
                 "opinion":"对症处理","medication":"按医嘱用药","followup":"一周后复诊",
                 "doctor":"病历医生","date":"2026-09-17"}
                """.formatted(present);
        return new ExportPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VIS-001", 1, format, content, "{\"chief\":\"编辑后的主诉\"}",
                Instant.parse("2026-09-17T02:30:00Z"));
    }

    private ExportPayload structuredPayload(String format, String layout, boolean header, boolean footer) {
        return new ExportPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VIS-003", 3, format, "{\"name\":\"模板患者\",\"gender\":\"男\",\"age\":40,\"phone\":\"1\",\"chief\":\"头痛\",\"doctor\":\"医生\",\"date\":\"2026-09-22\"}",
                null, Instant.parse("2026-09-22T02:30:00Z"), structuredDefinition(layout, header, footer));
    }

    private String structuredDefinition() {
        return structuredDefinition("MERGED_TABLE", true, true);
    }

    private String structuredDefinition(String layout, boolean header, boolean footer) {
        return """
                {"documentTitle":"自定义门诊记录","layout":"%s",
                 "page":{"marginTop":60,"marginRight":54,"marginBottom":50,"marginLeft":54},
                 "style":{"fontSize":10,"lineHeight":14,"labelSize":9,"sectionSize":10,"cellPadding":4},
                 "headerEnabled":%s,"footerEnabled":%s,"sections":[
                 {"key":"basic","title":"基本资料","layout":"GRID_2","fields":[
                 {"key":"name","label":"姓名","visible":true},{"key":"gender","label":"性别","visible":true},{"key":"age","label":"年龄","visible":true},{"key":"phone","label":"电话","visible":true}]},
                 {"key":"visit","title":"就诊内容","layout":"TABLE","fields":[
                 {"key":"chief","label":"本次症状","visible":true},{"key":"present","label":"现病史","visible":true},{"key":"past","label":"既往史","visible":true}]},
                 {"key":"treatment","title":"诊疗","layout":"TABLE","fields":[
                 {"key":"opinion","label":"处理","visible":true},{"key":"medication","label":"用药","visible":true},{"key":"followup","label":"复诊","visible":true}]},
                 {"key":"sign","title":"签署","layout":"TABLE","fields":[
                 {"key":"doctor","label":"医生","visible":true},{"key":"date","label":"日期","visible":true}]}]}
                """.formatted(layout, header, footer);
    }
}

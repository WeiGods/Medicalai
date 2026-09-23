package com.medicalai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.medicalai.domain.MedicalRecordContent;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper.ExportPayload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFldChar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExportFileService {
    private static final Logger LOG = LoggerFactory.getLogger(ExportFileService.class);
    private static final String DOCX_FONT = "Microsoft YaHei";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CONFIRMED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(SHANGHAI);

    private final Path root;
    private final ObjectMapper objectMapper;
    private final long uploadMaxBytes;

    @org.springframework.beans.factory.annotation.Autowired
    public ExportFileService(@Value("${medicalai.storage.root:./data/recordings}") String root,
                             ObjectMapper objectMapper,
                             @Value("${medicalai.export.upload-max-bytes:20971520}") long uploadMaxBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.uploadMaxBytes = uploadMaxBytes;
    }

    /** 兼容不需要前端上传大小覆盖值的旧组装代码。 */
    public ExportFileService(String root, ObjectMapper objectMapper) {
        this(root, objectMapper, 20L * 1024 * 1024);
    }

    /** Stores a browser-generated export file using the same safe local layout as backend generation. */
    public String storeUploaded(UUID exportId, String format, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EXPORT_FILE_EMPTY", "导出文件不能为空");
        }
        if (file.getSize() > uploadMaxBytes) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EXPORT_FILE_TOO_LARGE", "导出文件超过大小限制");
        }
        String extension = "DOCX".equalsIgnoreCase(format) ? "docx" : "pdf";
        try {
            Path dir = root.resolve("exports").normalize();
            Path target = dir.resolve(exportId + "." + extension).normalize();
            if (!target.startsWith(dir)) throw new IOException("invalid export path");
            Files.createDirectories(dir);
            Path temporary = Files.createTempFile(dir, exportId.toString(), ".tmp");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                validateSignature(temporary, extension);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            LOG.info("前端导出文件已归档: exportId={}, path={}, bytes={}", exportId, target, file.getSize());
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_STORAGE_FAILED", "导出文件归档失败", e);
        }
    }

    /**
     * Deletes a retired export only when its stored key resolves under the managed exports directory.
     * A missing file is an already-completed cleanup, while malformed keys are ignored rather than widened.
     */
    public void deleteStoredExport(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            Path exports = root.resolve("exports").normalize();
            Path target = root.resolve(objectKey).normalize();
            if (!target.startsWith(exports)) {
                LOG.warn("拒绝删除导出目录外的文件: {}", objectKey);
                return;
            }
            Files.deleteIfExists(target);
        } catch (IOException error) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_STORAGE_FAILED", "历史导出文件清理失败", error);
        }
    }

    private void validateSignature(Path file, String extension) throws IOException {
        byte[] header = new byte[4];
        try (InputStream input = Files.newInputStream(file)) {
            int read = input.readNBytes(header, 0, header.length);
            if (read < 4) throw new IOException("export file is too small");
        }
        boolean valid = "pdf".equals(extension)
                ? header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F'
                : header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4;
        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EXPORT_FILE_INVALID", "导出文件格式不正确");
        }
    }

    public String generate(ExportPayload payload) {
        try {
            Path dir = root.resolve("exports").resolve(payload.visitId().toString()).normalize();
            if (!dir.startsWith(root)) throw new IOException("invalid export path");
            Files.createDirectories(dir);
            String ext = "DOCX".equalsIgnoreCase(payload.format()) ? "docx" : "pdf";
            Path target = dir.resolve(payload.id() + "." + ext);
            ExportDocument record = document(payload);
            byte[] bytes = render(payload);
            writeAtomically(target, bytes);
            if (!Files.isRegularFile(target) || Files.size(target) <= 0) {
                throw new IOException("export file was not persisted: " + target);
            }
            LOG.info("导出文件已写入: exportId={}, path={}, bytes={}", payload.id(), target, bytes.length);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "EXPORT_GENERATION_FAILED", "病历文件生成失败", e);
        }
    }

    /** Reads only generated files under STORAGE_ROOT/exports. */
    public Resource load(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) throw exportNotFound(null);
        Path exportRoot = root.resolve("exports").normalize();
        Path target = root.resolve(storedPath).normalize();
        if (!target.startsWith(exportRoot) || !Files.isRegularFile(target)) {
            LOG.warn("导出文件不存在: storedPath={}, resolvedPath={}", storedPath, target);
            throw exportNotFound(null);
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            return new ByteArrayResource(bytes) {
                @Override public String getFilename() { return target.getFileName().toString(); }
            };
        } catch (IOException error) {
            throw exportNotFound(error);
        }
    }

    private ExportDocument document(ExportPayload payload) throws IOException {
        return new ExportDocument(payload.visitNo(), payload.versionNo(), payload.confirmedAt(),
                MedicalRecordContentCodec.parse(objectMapper, payload.contentJson(), payload.editedContentJson()),
                payload.templateDefinitionJson());
    }

    /** Renders a de-identified document for template management without persisting an export record or file. */
    public byte[] preview(String format, String definitionJson) {
        ExportPayload payload = new ExportPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "PREVIEW-0001", 1, format,
                "{\"name\":\"示例患者\",\"gender\":\"女\",\"age\":42,\"phone\":\"138****0000\",\"chief\":\"反复头痛三天\",\"present\":\"三日前出现头痛，伴轻度恶心。\",\"past\":\"否认重大疾病史。\",\"opinion\":\"建议对症处理并观察。\",\"medication\":\"遵医嘱用药。\",\"followup\":\"一周后复诊。\",\"doctor\":\"示例医生\",\"date\":\"2026-09-22\"}",
                null, Instant.parse("2026-09-22T02:30:00Z"), definitionJson);
        try {
            return render(payload);
        } catch (IOException error) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "TEMPLATE_PREVIEW_FAILED", "模板预览生成失败", error);
        }
    }

    /** Renders bytes without persisting them; used by previews and the atomic file-generation path. */
    public byte[] render(ExportPayload payload) throws IOException {
        ExportDocument record = document(payload);
        return "DOCX".equalsIgnoreCase(payload.format()) ? docx(record) : pdf(record);
    }

    private byte[] docx(ExportDocument record) throws IOException {
        if (record.templateDefinitionJson() != null && !record.templateDefinitionJson().isBlank()) return structuredDocx(record);
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            configureA4(document);
            addParagraph(document, "门诊病历", 16, true, ParagraphAlignment.CENTER, 120, 200);
            addParagraph(document, metadata(record), 9, false, ParagraphAlignment.CENTER, 0, 180);

            addSection(document, "基本信息");
            XWPFTable table = document.createTable(2, 2);
            table.setStyleID("TableGrid");
            setCell(table.getRow(0).getCell(0), "患者姓名：", value(record.content().name()), true);
            setCell(table.getRow(0).getCell(1), "性别：", value(record.content().gender()), true);
            setCell(table.getRow(1).getCell(0), "年龄：", record.content().age() == null ? "" : record.content().age().toString(), true);
            setCell(table.getRow(1).getCell(1), "联系方式：", value(record.content().phone()), true);

            addSection(document, "就诊内容");
            addField(document, "患者主诉", record.content().chief());
            addField(document, "患者现病史", record.content().present());
            addField(document, "患者既往史", record.content().past());

            addSection(document, "诊疗记录");
            addField(document, "医生处理意见", record.content().opinion());
            addField(document, "用药情况", record.content().medication());
            addField(document, "复诊建议", record.content().followup());

            addParagraph(document, "接诊医生：" + value(record.content().doctor()), 10, false,
                    ParagraphAlignment.LEFT, 120, 60);
            addParagraph(document, "接诊日期：" + value(record.content().date()), 10, false,
                    ParagraphAlignment.LEFT, 0, 60);
            document.write(out);
            return out.toByteArray();
        }
    }

    private void configureA4(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr() : document.getDocument().getBody().addNewSectPr();
        CTPageSz pageSize = section.isSetPgSz() ? section.getPgSz() : section.addNewPgSz();
        pageSize.setW(BigInteger.valueOf(11906));
        pageSize.setH(BigInteger.valueOf(16838));
        CTPageMar margins = section.isSetPgMar() ? section.getPgMar() : section.addNewPgMar();
        margins.setTop(BigInteger.valueOf(1134));
        margins.setRight(BigInteger.valueOf(1134));
        margins.setBottom(BigInteger.valueOf(1134));
        margins.setLeft(BigInteger.valueOf(1134));
    }

    private void addSection(XWPFDocument document, String title) {
        addParagraph(document, title, 12, true, ParagraphAlignment.LEFT, 180, 80);
    }

    private void addField(XWPFDocument document, String label, String fieldValue) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(60);
        paragraph.setSpacingAfter(90);
        XWPFRun labelRun = paragraph.createRun();
        applyFont(labelRun, 10, true);
        labelRun.setText(label + "：");
        XWPFRun valueRun = paragraph.createRun();
        applyFont(valueRun, 10, false);
        valueRun.setText(value(fieldValue));
    }

    private void addParagraph(XWPFDocument document, String text, int size, boolean bold,
                              ParagraphAlignment alignment, int before, int after) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        paragraph.setSpacingBefore(before);
        paragraph.setSpacingAfter(after);
        XWPFRun run = paragraph.createRun();
        applyFont(run, size, bold);
        run.setText(text);
    }

    private void setCell(XWPFTableCell cell, String label, String fieldValue, boolean labelBold) {
        XWPFParagraph paragraph = cell.getParagraphs().getFirst();
        paragraph.setSpacingBefore(40);
        paragraph.setSpacingAfter(40);
        XWPFRun labelRun = paragraph.createRun();
        applyFont(labelRun, 9, labelBold);
        labelRun.setText(label);
        XWPFRun valueRun = paragraph.createRun();
        applyFont(valueRun, 9, false);
        valueRun.setText(value(fieldValue));
    }

    private void applyFont(XWPFRun run, int size, boolean bold) {
        applyFont(run, size, bold, "000000");
    }

    private void applyFont(XWPFRun run, int size, boolean bold, String color) {
        run.setFontSize(size);
        run.setBold(bold);
        run.setColor(color);
        CTRPr properties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = properties.sizeOfRFontsArray() > 0
                ? properties.getRFontsArray(0) : properties.addNewRFonts();
        fonts.setAscii(DOCX_FONT);
        fonts.setHAnsi(DOCX_FONT);
        fonts.setEastAsia(DOCX_FONT);
    }

    private byte[] pdf(ExportDocument record) throws IOException {
        if (record.templateDefinitionJson() != null && !record.templateDefinitionJson().isBlank()) return structuredPdf(record);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             InputStream fontStream = new ClassPathResource("fonts/NotoSansSC-Regular.ttf").getInputStream()) {
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            PdfLayout layout = new PdfLayout(document, font);
            layout.centered("门诊病历", 18, 26);
            layout.wrapped(metadata(record), 9, 0, 14);
            layout.space(8);

            layout.section("基本信息");
            layout.wrapped("患者姓名：" + value(record.content().name()) + "    性别：" + value(record.content().gender()),
                    10, 0, 16);
            layout.wrapped("年龄：" + (record.content().age() == null ? "" : record.content().age())
                    + "    联系方式：" + value(record.content().phone()), 10, 0, 16);

            layout.section("就诊内容");
            layout.field("患者主诉", record.content().chief());
            layout.field("患者现病史", record.content().present());
            layout.field("患者既往史", record.content().past());

            layout.section("诊疗记录");
            layout.field("医生处理意见", record.content().opinion());
            layout.field("用药情况", record.content().medication());
            layout.field("复诊建议", record.content().followup());

            layout.space(12);
            layout.wrapped("接诊医生：" + value(record.content().doctor()), 10, 0, 16);
            layout.wrapped("接诊日期：" + value(record.content().date()), 10, 0, 16);
            String unicodeCMap = toUnicodeCMap(toUnicodeMappings(font, layout.drawnText()));
            document.save(out);
            return patchToUnicode(out.toByteArray(), unicodeCMap);
        }
    }

    /**
     * PDFBox 2 does not build a correct ToUnicode map for this CJK TrueType font. Keep the embedded
     * glyph font for print fidelity while writing the exact Unicode map needed for search and copy.
     */
    private Map<String, String> toUnicodeMappings(PDType0Font font, Set<String> drawnText) throws IOException {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String text : drawnText) {
            byte[] encoded = font.encode(text);
            try (ByteArrayInputStream input = new ByteArrayInputStream(encoded)) {
                for (int offset = 0; offset < text.length();) {
                    int before = input.available();
                    font.readCode(input);
                    int byteCount = before - input.available();
                    int byteOffset = encoded.length - before;
                    int codePoint = text.codePointAt(offset);
                    mappings.putIfAbsent(hex(encoded, byteOffset, byteCount), unicodeHex(codePoint));
                    offset += Character.charCount(codePoint);
                }
            }
        }
        return mappings;
    }

    private byte[] patchToUnicode(byte[] pdf, String cmap) throws IOException {
        try (PDDocument document = PDDocument.load(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            boolean patched = false;
            for (PDPage page : document.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    if (!(font instanceof PDType0Font)) continue;
                    writeToUnicode(document, font, cmap);
                    patched = true;
                }
            }
            if (!patched) throw new IOException("CJK font was not found in generated PDF");
            document.save(out);
            return out.toByteArray();
        }
    }

    private void writeToUnicode(PDDocument document, PDFont font, String cmap) throws IOException {
        COSStream toUnicode = document.getDocument().createCOSStream();
        try (OutputStream output = toUnicode.createOutputStream()) {
            output.write(cmap.getBytes(StandardCharsets.US_ASCII));
        }
        font.getCOSObject().setItem(COSName.TO_UNICODE, toUnicode);
    }

    private String toUnicodeCMap(Map<String, String> mappings) {
        StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                .append("/CMapName /MedicalAi-UCS def\n/CMapType 2 def\n")
                .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        List<Map.Entry<String, String>> entries = new ArrayList<>(mappings.entrySet());
        for (int start = 0; start < entries.size(); start += 100) {
            int end = Math.min(start + 100, entries.size());
            cmap.append(end - start).append(" beginbfchar\n");
            for (int index = start; index < end; index++) {
                Map.Entry<String, String> entry = entries.get(index);
                cmap.append('<').append(entry.getKey()).append("> <").append(entry.getValue()).append(">\n");
            }
            cmap.append("endbfchar\n");
        }
        return cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n").toString();
    }

    private String hex(byte[] value, int offset, int length) {
        StringBuilder hex = new StringBuilder(length * 2);
        for (int index = offset; index < offset + length; index++) hex.append(String.format("%02X", value[index] & 0xff));
        return hex.toString();
    }

    private String unicodeHex(int codePoint) {
        StringBuilder hex = new StringBuilder();
        for (char value : Character.toChars(codePoint)) hex.append(String.format("%04X", (int) value));
        return hex.toString();
    }

    private String metadata(ExportDocument record) {
        return metadata(record, List.of("VISIT_NO", "RECORD_VERSION", "CONFIRMED_AT"));
    }

    private String metadata(ExportDocument record, List<String> fields) {
        List<String> values = new ArrayList<>();
        for (String field : fields) {
            switch (field) {
                case "VISIT_NO" -> values.add("接诊编号：" + value(record.visitNo()));
                case "RECORD_VERSION" -> values.add("病历版本：v" + record.versionNo());
                case "CONFIRMED_AT" -> values.add("确认时间：" + confirmedAt(record.confirmedAt()));
                default -> { }
            }
        }
        return String.join("    ", values);
    }

    private String confirmedAt(Instant instant) {
        return instant == null ? "" : CONFIRMED_TIME.format(instant);
    }

    private String value(String text) {
        return text == null ? "" : text;
    }

    private BusinessException exportNotFound(Exception cause) {
        return cause == null
                ? new BusinessException(HttpStatus.NOT_FOUND, "EXPORT_FILE_NOT_FOUND", "导出文件不存在，请重新导出")
                : new BusinessException(HttpStatus.NOT_FOUND, "EXPORT_FILE_NOT_FOUND", "导出文件不存在，请重新导出", cause);
    }

    private void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] structuredDocx(ExportDocument record) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            JsonNode definition = objectMapper.readTree(record.templateDefinitionJson());
            Appearance appearance = appearance(definition);
            configureA4(document, definition.path("page"));
            JsonNode style = definition.path("style");
            int fontSize = style.path("fontSize").asInt(10);
            int labelSize = style.path("labelSize").asInt(Math.max(8, fontSize - 1));
            int sectionSize = style.path("sectionSize").asInt(fontSize + 1);
            int padding = appearance.table().cellPadding() * 20;
            int line = style.path("lineHeight").asInt(14) * 20;
            String documentTitle = definition.path("documentTitle").asText("门诊病历");
            FormatAppearance format = appearance.docx();
            configureHeaderFooter(document, documentTitle, record, format, appearance.theme(), Math.max(8, labelSize));
            if (!format.header().enabled() || !definition.has("appearance")) {
                addStyledParagraph(document, documentTitle, Math.max(12, sectionSize + 4), true,
                        ParagraphAlignment.CENTER, 120, 160, appearance.theme().ink());
            }
            if (format.metadata().visible()) {
                addStyledParagraph(document, metadata(record, format.metadata().fields()), Math.max(8, labelSize), false,
                        ParagraphAlignment.CENTER, 0, 140, appearance.theme().muted());
            }
            boolean mergedLayout = "MERGED_TABLE".equals(definition.path("layout").asText());
            XWPFTable mergedTable = mergedLayout ? structuredTable(document, appearance) : null;
            for (JsonNode section : definition.path("sections")) {
                List<JsonNode> fields = visibleFields(section.path("fields"));
                if (fields.isEmpty()) continue;
                if (mergedLayout) {
                    appendSectionTable(mergedTable, section, fields, record, labelSize, fontSize, sectionSize, padding, line,
                            true, appearance);
                } else {
                    if (!"NONE".equals(appearance.table().sectionHeader())) {
                        addStyledParagraph(document, section.path("title").asText(), sectionSize, true,
                                ParagraphAlignment.LEFT, 160, 80, appearance.theme().ink());
                    }
                    appendSectionTable(structuredTable(document, appearance), section, fields, record, labelSize, fontSize,
                            sectionSize, padding, line, false, appearance);
                }
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    private byte[] structuredPdf(ExportDocument record) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream();
             InputStream fontStream = new ClassPathResource("fonts/NotoSansSC-Regular.ttf").getInputStream()) {
            JsonNode definition = objectMapper.readTree(record.templateDefinitionJson());
            Appearance appearance = appearance(definition);
            PDType0Font font = PDType0Font.load(document, fontStream, true);
            JsonNode style = definition.path("style");
            float fontSize = (float) style.path("fontSize").asDouble(10);
            float labelSize = (float) style.path("labelSize").asDouble(Math.max(8, fontSize - 1));
            float sectionSize = (float) style.path("sectionSize").asDouble(fontSize + 1);
            float leading = (float) style.path("lineHeight").asDouble(14);
            String documentTitle = definition.path("documentTitle").asText("门诊病历");
            boolean mergedLayout = "MERGED_TABLE".equals(definition.path("layout").asText());
            FormatAppearance format = appearance.pdf();
            PdfLayout layout = new PdfLayout(document, font, definition.path("page"),
                    format.header().enabled() ? documentTitle : null,
                    format.footer().enabled() ? format.footer().content() : null,
                    appearance.theme(), appearance.table(), format, record.versionNo());
            if (!format.header().enabled()) layout.centered(documentTitle, sectionSize + 4, leading + 10, appearance.theme().ink());
            if (format.metadata().visible()) layout.coloredWrapped(metadata(record, format.metadata().fields()),
                    Math.max(8, labelSize), 0, leading, appearance.theme().muted());
            for (JsonNode section : definition.path("sections")) {
                List<JsonNode> fields = visibleFields(section.path("fields"));
                if (fields.isEmpty()) continue;
                if (mergedLayout && "SHADED_ROW".equals(appearance.table().sectionHeader())) {
                    layout.sectionTableRow(section.path("title").asText(), sectionSize, leading + 3);
                } else if (!"NONE".equals(appearance.table().sectionHeader())) {
                    layout.space(6);
                    layout.coloredSection(section.path("title").asText(), sectionSize, leading + 3, appearance.theme().ink());
                }
                if ("GRID_2".equals(section.path("layout").asText())) {
                    for (int index = 0; index < fields.size(); index += 2) {
                        JsonNode left = fields.get(index);
                        String leftValue = left.path("label").asText() + "：" + templateValue(record, left.path("key").asText());
                        String rightValue = "";
                        if (index + 1 < fields.size()) {
                            JsonNode right = fields.get(index + 1);
                            rightValue = right.path("label").asText() + "：" + templateValue(record, right.path("key").asText());
                        }
                        layout.styledTableRow(leftValue, rightValue, fontSize, fontSize, leading);
                    }
                } else {
                    for (JsonNode field : fields) {
                        layout.styledTableRow(field.path("label").asText(), templateValue(record, field.path("key").asText()),
                                labelSize, fontSize, leading);
                    }
                }
            }
            layout.writePageFooter();
            String unicodeCMap = toUnicodeCMap(toUnicodeMappings(font, layout.drawnText()));
            document.save(out);
            return patchToUnicode(out.toByteArray(), unicodeCMap);
        }
    }

    private List<com.fasterxml.jackson.databind.JsonNode> visibleFields(com.fasterxml.jackson.databind.JsonNode fields) {
        List<com.fasterxml.jackson.databind.JsonNode> result = new ArrayList<>();
        if (fields.isArray()) for (com.fasterxml.jackson.databind.JsonNode field : fields) if (field.path("visible").asBoolean()) result.add(field);
        return result;
    }

    private String templateValue(ExportDocument record, String key) {
        MedicalRecordContent content = record.content();
        return switch (key) {
            case "name" -> value(content.name()); case "gender" -> value(content.gender());
            case "age" -> content.age() == null ? "" : String.valueOf(content.age()); case "phone" -> value(content.phone());
            case "chief" -> value(content.chief()); case "present" -> value(content.present()); case "past" -> value(content.past());
            case "opinion" -> value(content.opinion()); case "medication" -> value(content.medication()); case "followup" -> value(content.followup());
            case "doctor" -> value(content.doctor()); case "date" -> value(content.date()); case "visit_no" -> value(record.visitNo());
            case "record_version" -> "v" + record.versionNo(); case "confirmed_at" -> confirmedAt(record.confirmedAt());
            default -> "";
        };
    }

    private void configureA4(XWPFDocument document, com.fasterxml.jackson.databind.JsonNode page) {
        configureA4(document);
        CTSectPr section = document.getDocument().getBody().getSectPr();
        CTPageMar margins = section.getPgMar();
        margins.setTop(BigInteger.valueOf(page.path("marginTop").asInt(57) * 20L));
        margins.setRight(BigInteger.valueOf(page.path("marginRight").asInt(57) * 20L));
        margins.setBottom(BigInteger.valueOf(page.path("marginBottom").asInt(57) * 20L));
        margins.setLeft(BigInteger.valueOf(page.path("marginLeft").asInt(57) * 20L));
    }

    private void configureHeaderFooter(XWPFDocument document, String documentTitle, ExportDocument record,
                                       FormatAppearance format, Theme theme, int fontSize) {
        if (!format.header().enabled() && !format.footer().enabled()) return;
        XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document);
        if (format.header().enabled()) {
            XWPFHeader header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
            XWPFParagraph paragraph = header.createParagraph();
            paragraph.setAlignment(wordAlignment(format.header().alignment()));
            XWPFRun run = paragraph.createRun();
            applyFont(run, Math.max(8, fontSize), true, theme.ink());
            run.setText("TITLE_WITH_VERSION".equals(format.header().content())
                    ? documentTitle + "  v" + record.versionNo() : documentTitle);
        }
        if (format.footer().enabled()) {
            XWPFFooter footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
            XWPFParagraph paragraph = footer.createParagraph();
            paragraph.setAlignment(wordAlignment(format.footer().alignment()));
            if ("PAGE_X_OF_Y".equals(format.footer().content())) {
                addStyledRun(paragraph, "第 ", fontSize, false, theme.muted());
                addWordField(paragraph, "PAGE", fontSize, theme.muted());
                addStyledRun(paragraph, " 页 / 共 ", fontSize, false, theme.muted());
                addWordField(paragraph, "NUMPAGES", fontSize, theme.muted());
                addStyledRun(paragraph, " 页", fontSize, false, theme.muted());
            } else {
                addStyledRun(paragraph, "电子病历导出", fontSize, false, theme.muted());
            }
        }
    }

    private XWPFTable structuredTable(XWPFDocument document, Appearance appearance) {
        XWPFTable table = document.createTable(1, 2);
        table.removeRow(0);
        table.setStyleID("TableGrid");
        configureTable(table, appearance);
        return table;
    }

    private void appendSectionTable(XWPFTable table, JsonNode section,
                                    List<JsonNode> fields, ExportDocument record,
                                    int labelSize, int fontSize, int sectionSize, int padding, int line,
                                    boolean includeSectionRow, Appearance appearance) {
        if (includeSectionRow && !"NONE".equals(appearance.table().sectionHeader())) {
            XWPFTableRow row = tableRow(table);
            setCell(row.getCell(0), section.path("title").asText(), "", true, sectionSize, sectionSize, padding, line,
                    appearance.theme(), "SHADED_ROW".equals(appearance.table().sectionHeader()));
            mergeSectionCells(row);
        }
        if ("GRID_2".equals(section.path("layout").asText())) {
            for (int index = 0; index < fields.size(); index += 2) {
                XWPFTableRow row = tableRow(table);
                JsonNode left = fields.get(index);
                setCell(row.getCell(0), left.path("label").asText() + "：",
                        templateValue(record, left.path("key").asText()), true, labelSize, fontSize, padding, line,
                        appearance.theme(), false);
                if (index + 1 < fields.size()) {
                    JsonNode right = fields.get(index + 1);
                    setCell(row.getCell(1), right.path("label").asText() + "：",
                            templateValue(record, right.path("key").asText()), true, labelSize, fontSize, padding, line,
                            appearance.theme(), false);
                } else setCell(row.getCell(1), "", "", false, labelSize, fontSize, padding, line, appearance.theme(), false);
            }
            return;
        }
        for (JsonNode field : fields) {
            XWPFTableRow row = tableRow(table);
            setCell(row.getCell(0), field.path("label").asText() + "：", "", true, labelSize, fontSize, padding, line,
                    appearance.theme(), false);
            setCell(row.getCell(1), "", templateValue(record, field.path("key").asText()), false,
                    labelSize, fontSize, padding, line, appearance.theme(), false);
        }
    }

    private XWPFTableRow tableRow(XWPFTable table) {
        XWPFTableRow row = table.createRow();
        while (row.getTableCells().size() < 2) row.addNewTableCell();
        return row;
    }

    private void setCell(XWPFTableCell cell, String label, String fieldValue, boolean labelBold,
                         int labelSize, int valueSize, int padding, int line, Theme theme, boolean sectionShading) {
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        setCellMargins(cell, padding);
        if (sectionShading) setCellShading(cell, theme.section());
        XWPFParagraph paragraph = cell.getParagraphs().getFirst();
        paragraph.setSpacingBefore(padding);
        paragraph.setSpacingAfter(padding);
        paragraph.setSpacingBetween(Math.max(1f, line / 240f));
        XWPFRun labelRun = paragraph.createRun();
        applyFont(labelRun, labelSize, labelBold, sectionShading ? theme.ink() : theme.muted());
        labelRun.setText(label);
        XWPFRun valueRun = paragraph.createRun();
        applyFont(valueRun, valueSize, false, theme.ink());
        valueRun.setText(value(fieldValue));
    }

    private void addField(XWPFDocument document, String label, String fieldValue, int fontSize, int line) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(60);
        paragraph.setSpacingAfter(90);
        paragraph.setSpacingBetween(Math.max(1f, line / 240f));
        XWPFRun labelRun = paragraph.createRun();
        applyFont(labelRun, fontSize, true);
        labelRun.setText(label + "：");
        XWPFRun valueRun = paragraph.createRun();
        applyFont(valueRun, fontSize, false);
        valueRun.setText(value(fieldValue));
    }

    private Appearance appearance(JsonNode definition) {
        JsonNode configured = definition.path("appearance");
        if (!configured.isObject()) return legacyAppearance(definition);
        return new Appearance(theme(configured.path("theme").asText()),
                new TableAppearance(configured.path("table").path("cellPadding").asInt(4),
                        configured.path("table").path("labelColumnRatio").asInt(24),
                        configured.path("table").path("sectionHeader").asText("SHADED_ROW")),
                formatAppearance(configured.path("docx")), formatAppearance(configured.path("pdf")));
    }

    private Appearance legacyAppearance(JsonNode definition) {
        boolean header = definition.path("headerEnabled").asBoolean(false);
        boolean footer = definition.path("footerEnabled").asBoolean(false);
        MetadataAppearance metadata = new MetadataAppearance("BELOW_TITLE",
                List.of("VISIT_NO", "RECORD_VERSION", "CONFIRMED_AT"));
        return new Appearance(theme("MEDICAL_GREEN"), new TableAppearance(definition.path("style").path("cellPadding").asInt(4),
                24, "SHADED_ROW"),
                new FormatAppearance(new HeaderAppearance(header, header ? "TITLE" : "NONE", "RIGHT"),
                        new FooterAppearance(footer, footer ? "LEGACY_LABEL" : "NONE", "CENTER"), metadata, "INLINE_PAIR"),
                new FormatAppearance(new HeaderAppearance(header, header ? "TITLE" : "NONE", "LEFT"),
                        new FooterAppearance(footer, footer ? "LEGACY_LABEL" : "NONE", "CENTER"), metadata, "INLINE_PAIR"));
    }

    private FormatAppearance formatAppearance(JsonNode format) {
        JsonNode header = format.path("header");
        JsonNode footer = format.path("footer");
        JsonNode metadata = format.path("metadata");
        List<String> fields = new ArrayList<>();
        if (metadata.path("fields").isArray()) metadata.path("fields").forEach(value -> fields.add(value.asText()));
        return new FormatAppearance(new HeaderAppearance(header.path("enabled").asBoolean(false),
                header.path("content").asText("NONE"), header.path("alignment").asText("CENTER")),
                new FooterAppearance(footer.path("enabled").asBoolean(false), footer.path("content").asText("NONE"),
                        footer.path("alignment").asText("CENTER")),
                new MetadataAppearance(metadata.path("position").asText("NONE"), fields),
                format.path("basicInfo").asText("INLINE_PAIR"));
    }

    private Theme theme(String value) {
        return switch (value) {
            case "CLINICAL_BLUE" -> new Theme("123B62", "587489", "D6E2EC", "EAF3F8");
            case "MINIMAL_GRAY" -> new Theme("2F3437", "687076", "D9DEE1", "F1F3F4");
            default -> new Theme("1F2E2B", "667C72", "D8E4DC", "EAF2ED");
        };
    }

    private void configureTable(XWPFTable table, Appearance appearance) {
        CTTblPr properties = table.getCTTbl().getTblPr() == null ? table.getCTTbl().addNewTblPr() : table.getCTTbl().getTblPr();
        CTTblBorders borders = properties.isSetTblBorders() ? properties.getTblBorders() : properties.addNewTblBorders();
        setBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop(), appearance.theme().line());
        setBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom(), appearance.theme().line());
        setBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft(), appearance.theme().line());
        setBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight(), appearance.theme().line());
        setBorder(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH(), appearance.theme().line());
        setBorder(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV(), appearance.theme().line());
        if (!properties.isSetTblLayout()) properties.addNewTblLayout();
        properties.getTblLayout().setType(STTblLayoutType.FIXED);
        CTTblGrid grid = table.getCTTbl().getTblGrid() == null ? table.getCTTbl().addNewTblGrid() : table.getCTTbl().getTblGrid();
        grid.getGridColList().clear();
        grid.addNewGridCol().setW(BigInteger.valueOf(appearance.table().labelColumnRatio() * 100L));
        grid.addNewGridCol().setW(BigInteger.valueOf((100 - appearance.table().labelColumnRatio()) * 100L));
    }

    private void setBorder(CTBorder border, String color) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor(color);
    }

    private void mergeSectionCells(XWPFTableRow row) {
        XWPFTableCell first = row.getCell(0);
        CTTcPr properties = first.getCTTc().isSetTcPr() ? first.getCTTc().getTcPr() : first.getCTTc().addNewTcPr();
        properties.addNewGridSpan().setVal(BigInteger.valueOf(2));
        row.removeCell(1);
    }

    private void setCellMargins(XWPFTableCell cell, int padding) {
        CTTcPr properties = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTcMar margins = properties.isSetTcMar() ? properties.getTcMar() : properties.addNewTcMar();
        BigInteger value = BigInteger.valueOf(Math.max(0, padding));
        margins.addNewTop().setW(value);
        margins.addNewBottom().setW(value);
        margins.addNewLeft().setW(BigInteger.valueOf(Math.max(80, padding)));
        margins.addNewRight().setW(BigInteger.valueOf(Math.max(80, padding)));
    }

    private void setCellShading(XWPFTableCell cell, String fill) {
        CTTcPr properties = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shading = properties.isSetShd() ? properties.getShd() : properties.addNewShd();
        shading.setFill(fill);
    }

    private ParagraphAlignment wordAlignment(String alignment) {
        return switch (alignment) {
            case "LEFT" -> ParagraphAlignment.LEFT;
            case "RIGHT" -> ParagraphAlignment.RIGHT;
            default -> ParagraphAlignment.CENTER;
        };
    }

    private void addStyledParagraph(XWPFDocument document, String text, int size, boolean bold,
                                    ParagraphAlignment alignment, int before, int after, String color) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment);
        paragraph.setSpacingBefore(before);
        paragraph.setSpacingAfter(after);
        addStyledRun(paragraph, text, size, bold, color);
    }

    private void addStyledRun(XWPFParagraph paragraph, String text, int size, boolean bold, String color) {
        XWPFRun run = paragraph.createRun();
        applyFont(run, Math.max(8, size), bold, color);
        run.setText(text);
    }

    private void addWordField(XWPFParagraph paragraph, String instruction, int size, String color) {
        XWPFRun begin = paragraph.createRun();
        applyFont(begin, Math.max(8, size), false, color);
        CTFldChar beginChar = begin.getCTR().addNewFldChar();
        beginChar.setFldCharType(STFldCharType.BEGIN);
        XWPFRun command = paragraph.createRun();
        applyFont(command, Math.max(8, size), false, color);
        command.getCTR().addNewInstrText().setStringValue(instruction);
        XWPFRun separate = paragraph.createRun();
        applyFont(separate, Math.max(8, size), false, color);
        separate.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
        XWPFRun display = paragraph.createRun();
        applyFont(display, Math.max(8, size), false, color);
        display.setText("1");
        XWPFRun end = paragraph.createRun();
        applyFont(end, Math.max(8, size), false, color);
        end.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
    }

    private record Theme(String ink, String muted, String line, String section) {}
    private record TableAppearance(int cellPadding, int labelColumnRatio, String sectionHeader) {}
    private record HeaderAppearance(boolean enabled, String content, String alignment) {}
    private record FooterAppearance(boolean enabled, String content, String alignment) {}
    private record MetadataAppearance(String position, List<String> fields) {
        boolean visible() { return "BELOW_TITLE".equals(position) && !fields.isEmpty(); }
    }
    private record FormatAppearance(HeaderAppearance header, FooterAppearance footer, MetadataAppearance metadata,
                                    String basicInfo) {}
    private record Appearance(Theme theme, TableAppearance table, FormatAppearance docx, FormatAppearance pdf) {}

    private record ExportDocument(String visitNo, int versionNo, Instant confirmedAt, MedicalRecordContent content,
                                  String templateDefinitionJson) {}

    /** A small flow layout that measures the embedded CJK font before wrapping text. */
    private static final class PdfLayout {
        private final float left;
        private final float right;
        private final float top;
        private final float bottom;

        private final PDDocument document;
        private final PDType0Font font;
        private final String headerText;
        private final String footerText;
        private final Theme theme;
        private final TableAppearance tableAppearance;
        private final FormatAppearance formatAppearance;
        private final int versionNo;
        private final Set<String> drawnText = new LinkedHashSet<>();
        private PDPage page;
        private float y;

        private PdfLayout(PDDocument document, PDType0Font font) {
            this(document, font, null, null, null);
        }

        private PdfLayout(PDDocument document, PDType0Font font, com.fasterxml.jackson.databind.JsonNode pageMargins) {
            this(document, font, pageMargins, null, null);
        }

        private PdfLayout(PDDocument document, PDType0Font font, com.fasterxml.jackson.databind.JsonNode pageMargins,
                           String headerText, String footerText) {
            this(document, font, pageMargins, headerText, footerText,
                    new Theme("000000", "505050", "787878", "F2F2F2"), new TableAppearance(4, 50, "NONE"), null, 0);
        }

        private PdfLayout(PDDocument document, PDType0Font font, JsonNode pageMargins,
                          String headerText, String footerText, Theme theme, TableAppearance tableAppearance,
                          FormatAppearance formatAppearance, int versionNo) {
            this.document = document;
            this.font = font;
            this.headerText = headerText;
            this.footerText = footerText;
            this.theme = theme;
            this.tableAppearance = tableAppearance;
            this.formatAppearance = formatAppearance;
            this.versionNo = versionNo;
            float marginTop = pageMargins == null ? 56.7f : (float) pageMargins.path("marginTop").asDouble(56.7);
            float marginRight = pageMargins == null ? 56.7f : (float) pageMargins.path("marginRight").asDouble(56.7);
            float marginBottom = pageMargins == null ? 56.7f : (float) pageMargins.path("marginBottom").asDouble(56.7);
            float marginLeft = pageMargins == null ? 56.7f : (float) pageMargins.path("marginLeft").asDouble(56.7);
            this.left = marginLeft;
            this.right = PDRectangle.A4.getWidth() - marginRight;
            this.top = PDRectangle.A4.getHeight() - marginTop - (headerText == null ? 0 : 16);
            this.bottom = marginBottom + (footerText == null ? 0 : 16);
            newPage();
        }

        private void newPage() {
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            y = top;
            if (headerText != null) drawHeader();
            if ("LEGACY_LABEL".equals(footerText)) fixedText(footerText.equals("LEGACY_LABEL") ? "电子病历导出" : footerText,
                    left, Math.max(8, bottom - 12), 8, theme.muted());
        }

        private void centered(String text, float size, float leading) throws IOException {
            float width = width(text, size);
            line(text, Math.max(left, (PDRectangle.A4.getWidth() - width) / 2), size, leading);
        }

        private void centered(String text, float size, float leading, String color) throws IOException {
            float width = width(text, size);
            coloredLine(text, Math.max(left, (PDRectangle.A4.getWidth() - width) / 2), size, leading, color);
        }

        private void section(String title) throws IOException {
            space(8);
            line(title, left, 12, 18);
        }

        private void section(String title, float size, float leading) throws IOException {
            space(8);
            line(title, left, size, leading);
        }

        private void field(String label, String content) throws IOException {
            line(label + "：", left, 10, 16);
            wrapped(value(content), 10, 14, 16);
            space(3);
        }

        private void field(String label, String content, float size, float leading) throws IOException {
            line(label + "：", left, size, leading);
            wrapped(value(content), size, 14, leading);
            space(3);
        }

        private void coloredWrapped(String text, float size, float indent, float leading, String color) throws IOException {
            float availableWidth = right - left - indent;
            for (String paragraph : value(text).split("\\R", -1)) {
                for (String line : wrap(paragraph, size, availableWidth)) {
                    coloredLine(line, left + indent, size, leading, color);
                }
            }
        }

        private void coloredSection(String title, float size, float leading, String color) throws IOException {
            space(8);
            coloredLine(title, left, size, leading, color);
        }

        private void sectionTableRow(String title, float size, float leading) throws IOException {
            float padding = Math.max(4f, tableAppearance.cellPadding());
            List<String> lines = wrap(value(title), size, right - left - padding * 2);
            float height = Math.max(leading + padding * 2, lines.size() * leading + padding * 2);
            if (y - height < bottom) newPage();
            float lower = y - height;
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                setFill(stream, theme.section());
                stream.addRect(left, lower, right - left, height);
                stream.fill();
                setStroke(stream, theme.line());
                stream.addRect(left, lower, right - left, height);
                stream.stroke();
                tableCell(stream, lines, left + padding, y - padding - leading + 2, size, leading, theme.ink());
            }
            y = lower;
        }

        private void styledTableRow(String leftText, String rightText, float leftSize, float rightSize, float leading) throws IOException {
            float width = right - left;
            float padding = Math.max(4f, tableAppearance.cellPadding());
            float split = left + width * tableAppearance.labelColumnRatio() / 100f;
            List<String> leftLines = wrap(value(leftText), leftSize, split - left - padding * 2);
            List<String> rightLines = wrap(value(rightText), rightSize, right - split - padding * 2);
            int lineCount = Math.max(leftLines.size(), rightLines.size());
            float height = Math.max(leading + padding * 2, lineCount * leading + padding * 2);
            if (y - height < bottom) newPage();
            float lower = y - height;
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                setStroke(stream, theme.line());
                stream.addRect(left, lower, width, height);
                stream.moveTo(split, lower);
                stream.lineTo(split, y);
                stream.stroke();
                tableCell(stream, leftLines, left + padding, y - padding - leading + 2, leftSize, leading, theme.muted());
                tableCell(stream, rightLines, split + padding, y - padding - leading + 2, rightSize, leading, theme.ink());
            }
            y = lower;
        }

        private void drawHeader() {
            try {
                float headerY = Math.min(PDRectangle.A4.getHeight() - 8, top + 20);
                if (formatAppearance != null && "TITLE_WITH_VERSION".equals(formatAppearance.header().content())
                        && "SPLIT".equals(formatAppearance.header().alignment())) {
                    fixedText(headerText, left, headerY, 9, theme.ink());
                    String version = "v" + versionNo;
                    fixedText(version, right - width(version, 8), headerY, 8, theme.muted());
                    return;
                }
                String text = formatAppearance != null && "TITLE_WITH_VERSION".equals(formatAppearance.header().content())
                        ? headerText + "  v" + versionNo : headerText;
                float x = alignedX(text, formatAppearance == null ? "LEFT" : formatAppearance.header().alignment(), 9);
                fixedText(text, x, headerY, 9, theme.ink());
            } catch (IOException error) {
                throw new IllegalStateException("unable to render PDF header", error);
            }
        }

        private void writePageFooter() throws IOException {
            if (!"PAGE_X_OF_Y".equals(footerText)) return;
            int pages = document.getNumberOfPages();
            for (int index = 0; index < pages; index++) {
                String text = "第 " + (index + 1) + " 页 / 共 " + pages + " 页";
                drawText(document.getPage(index), text, alignedX(text,
                        formatAppearance == null ? "CENTER" : formatAppearance.footer().alignment(), 8),
                        Math.max(8, bottom - 12), 8, theme.muted());
            }
        }

        private float alignedX(String text, String alignment, float size) throws IOException {
            return switch (alignment) {
                case "RIGHT" -> right - width(text, size);
                case "CENTER" -> Math.max(left, (PDRectangle.A4.getWidth() - width(text, size)) / 2);
                default -> left;
            };
        }

        private void tableRow(String leftText, String rightText, float size, float leading) throws IOException {
            float width = right - left;
            float half = width / 2f;
            List<String> leftLines = wrap(value(leftText), size, half - 8);
            List<String> rightLines = wrap(value(rightText), size, half - 8);
            int lineCount = Math.max(leftLines.size(), rightLines.size());
            float height = Math.max(leading + 8, lineCount * leading + 8);
            if (y - height < bottom) newPage();
            float lower = y - height;
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.setStrokingColor(120, 120, 120);
                stream.addRect(left, lower, width, height);
                stream.moveTo(left + half, lower);
                stream.lineTo(left + half, y);
                stream.stroke();
                tableCell(stream, leftLines, left + 4, y - leading, size, leading);
                tableCell(stream, rightLines, left + half + 4, y - leading, size, leading);
            }
            y = lower;
        }

        private void tableCell(PDPageContentStream stream, List<String> lines, float x, float startY,
                               float size, float leading) throws IOException {
            tableCell(stream, lines, x, startY, size, leading, "000000");
        }

        private void tableCell(PDPageContentStream stream, List<String> lines, float x, float startY,
                               float size, float leading, String color) throws IOException {
            for (int index = 0; index < lines.size(); index++) {
                String text = lines.get(index);
                if (text.isEmpty()) continue;
                drawnText.add(text);
                stream.beginText();
                setFill(stream, color);
                stream.setFont(font, size);
                stream.newLineAtOffset(x, startY - index * leading);
                stream.showText(text);
                stream.endText();
            }
        }

        private void fixedText(String text, float x, float textY, float size) {
            fixedText(text, x, textY, size, "505050");
        }

        private void fixedText(String text, float x, float textY, float size, String color) {
            if (text.isEmpty()) return;
            try {
                drawText(page, text, x, textY, size, color);
            } catch (IOException error) {
                throw new IllegalStateException("unable to render PDF header or footer", error);
            }
        }

        private void wrapped(String text, float size, float indent, float leading) throws IOException {
            float availableWidth = right - left - indent;
            for (String paragraph : value(text).split("\\R", -1)) {
                for (String line : wrap(paragraph, size, availableWidth)) {
                    line(line, left + indent, size, leading);
                }
            }
        }

        private List<String> wrap(String text, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            if (text.isEmpty()) {
                lines.add("");
                return lines;
            }
            StringBuilder line = new StringBuilder();
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                String next = new String(Character.toChars(codePoint));
                if (line.length() > 0 && width(line + next, size) > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(next);
                offset += Character.charCount(codePoint);
            }
            if (line.length() > 0) lines.add(line.toString());
            return lines;
        }

        private float width(String text, float size) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        private void line(String text, float x, float size, float leading) throws IOException {
            coloredLine(text, x, size, leading, "000000");
        }

        private void coloredLine(String text, float x, float size, float leading, String color) throws IOException {
            if (y - leading < bottom) newPage();
            if (!text.isEmpty()) {
                drawText(page, text, x, y, size, color);
            }
            y -= leading;
        }

        private void drawText(PDPage target, String text, float x, float textY, float size, String color) throws IOException {
            drawnText.add(text);
            try (PDPageContentStream stream = new PDPageContentStream(document, target,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.beginText();
                setFill(stream, color);
                stream.setFont(font, size);
                stream.newLineAtOffset(x, textY);
                stream.showText(text);
                stream.endText();
            }
        }

        private void setStroke(PDPageContentStream stream, String hex) throws IOException {
            int value = Integer.parseInt(hex, 16);
            stream.setStrokingColor((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
        }

        private void setFill(PDPageContentStream stream, String hex) throws IOException {
            int value = Integer.parseInt(hex, 16);
            stream.setNonStrokingColor((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
        }

        private void space(float amount) {
            if (y - amount < bottom) newPage();
            y -= amount;
        }

        private static String value(String text) {
            return text == null ? "" : text;
        }

        private Set<String> drawnText() {
            return drawnText;
        }
    }
}

package com.medicalai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
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

    public ExportFileService(@Value("${medicalai.storage.root:./data/recordings}") String root,
                             ObjectMapper objectMapper,
                             @Value("${medicalai.export.upload-max-bytes:20971520}") long uploadMaxBytes) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.uploadMaxBytes = uploadMaxBytes;
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
            byte[] bytes = "DOCX".equalsIgnoreCase(payload.format()) ? docx(record) : pdf(record);
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
                MedicalRecordContentCodec.parse(objectMapper, payload.contentJson(), payload.editedContentJson()));
    }

    private byte[] docx(ExportDocument record) throws IOException {
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
        run.setFontSize(size);
        run.setBold(bold);
        run.setColor("000000");
        CTRPr properties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = properties.sizeOfRFontsArray() > 0
                ? properties.getRFontsArray(0) : properties.addNewRFonts();
        fonts.setAscii(DOCX_FONT);
        fonts.setHAnsi(DOCX_FONT);
        fonts.setEastAsia(DOCX_FONT);
    }

    private byte[] pdf(ExportDocument record) throws IOException {
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
        return "接诊编号：" + value(record.visitNo()) + "    病历版本：v" + record.versionNo()
                + "    确认时间：" + confirmedAt(record.confirmedAt());
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

    private record ExportDocument(String visitNo, int versionNo, Instant confirmedAt, MedicalRecordContent content) {}

    /** A small flow layout that measures the embedded CJK font before wrapping text. */
    private static final class PdfLayout {
        private static final float LEFT = 56.7f;
        private static final float RIGHT = PDRectangle.A4.getWidth() - 56.7f;
        private static final float TOP = PDRectangle.A4.getHeight() - 56.7f;
        private static final float BOTTOM = 56.7f;

        private final PDDocument document;
        private final PDType0Font font;
        private final Set<String> drawnText = new LinkedHashSet<>();
        private PDPage page;
        private float y;

        private PdfLayout(PDDocument document, PDType0Font font) {
            this.document = document;
            this.font = font;
            newPage();
        }

        private void newPage() {
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            y = TOP;
        }

        private void centered(String text, float size, float leading) throws IOException {
            float width = width(text, size);
            line(text, Math.max(LEFT, (PDRectangle.A4.getWidth() - width) / 2), size, leading);
        }

        private void section(String title) throws IOException {
            space(8);
            line(title, LEFT, 12, 18);
        }

        private void field(String label, String content) throws IOException {
            line(label + "：", LEFT, 10, 16);
            wrapped(value(content), 10, 14, 16);
            space(3);
        }

        private void wrapped(String text, float size, float indent, float leading) throws IOException {
            float availableWidth = RIGHT - LEFT - indent;
            for (String paragraph : value(text).split("\\R", -1)) {
                for (String line : wrap(paragraph, size, availableWidth)) {
                    line(line, LEFT + indent, size, leading);
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
            if (y - leading < BOTTOM) newPage();
            if (!text.isEmpty()) {
                drawnText.add(text);
                try (PDPageContentStream stream = new PDPageContentStream(document, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText();
                    stream.setNonStrokingColor(0, 0, 0);
                    stream.setFont(font, size);
                    stream.newLineAtOffset(x, y);
                    stream.showText(text);
                    stream.endText();
                }
            }
            y -= leading;
        }

        private void space(float amount) {
            if (y - amount < BOTTOM) newPage();
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

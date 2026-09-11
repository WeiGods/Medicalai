package com.medicalai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper.ExportPayload;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExportFileService {
    private final Path root;
    private final ObjectMapper objectMapper;

    public ExportFileService(@Value("${medicalai.storage.root:./data/recordings}") String root,
                             ObjectMapper objectMapper) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public String generate(ExportPayload payload) {
        try {
            Path dir = root.resolve("exports").resolve(payload.visitId().toString()).normalize();
            if (!dir.startsWith(root)) throw new IOException("invalid export path");
            Files.createDirectories(dir);
            String ext = "DOCX".equalsIgnoreCase(payload.format()) ? "docx" : "pdf";
            Path target = dir.resolve(payload.id() + "." + ext);
            byte[] bytes = "DOCX".equalsIgnoreCase(payload.format()) ? docx(payload) : pdf(payload);
            Files.write(target, bytes);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "EXPORT_GENERATION_FAILED", "病历文件生成失败", e);
        }
    }

    private byte[] docx(ExportPayload p) throws IOException {
        Map<String, Object> content = content(p);
        StringBuilder body = new StringBuilder(paragraph("病历记录", true, 32));
        body.append(paragraph("患者姓名：" + p.patientName()));
        body.append(paragraph("接诊编号：" + p.visitNo()));
        body.append(paragraph("版本：v" + p.versionNo()));
        body.append(paragraph("接诊医生：" + p.doctorName()));
        for (Map.Entry<String, Object> e : content.entrySet()) {
            body.append(paragraph(e.getKey() + "：" + String.valueOf(e.getValue())));
        }
        body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>");
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>";
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", types); put(zip, "_rels/.rels", rels); put(zip, "word/document.xml", xml);
        }
        return out.toByteArray();
    }

    private byte[] pdf(ExportPayload p) throws IOException {
        Map<String, Object> content = content(p);
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4); doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 11);
                stream.setLeading(16); stream.newLineAtOffset(50, 780);
                String[] lines = {"Medical Record", "Patient: " + p.patientName(), "Visit: " + p.visitNo(), "Version: " + p.versionNo(), "Doctor: " + p.doctorName()};
                for (String line : lines) { stream.showText(ascii(line)); stream.newLine(); }
                for (Map.Entry<String, Object> e : content.entrySet()) { stream.showText(ascii(e.getKey() + ": " + e.getValue())); stream.newLine(); }
                stream.endText();
            }
            doc.save(out); return out.toByteArray();
        }
    }

    private Map<String, Object> content(ExportPayload p) throws IOException {
        String json = p.editedContentJson() == null || p.editedContentJson().isBlank() ? p.contentJson() : p.editedContentJson();
        return objectMapper.readValue(json == null ? "{}" : json, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private String paragraph(String text) { return paragraph(text, false, 22); }
    private String paragraph(String text, boolean bold, int size) {
        String escaped = escape(text);
        return "<w:p><w:r><w:rPr>" + (bold ? "<w:b/>" : "") + "<w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size + "\"/></w:rPr><w:t xml:space=\"preserve\">" + escaped + "</w:t></w:r></w:p>";
    }

    private String escape(String value) { return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private String ascii(String value) { return value.replaceAll("[^\\x20-\\x7E]", "?").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)"); }
    private void put(ZipOutputStream zip, String name, String value) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
}

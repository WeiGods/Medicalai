package com.medicalai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.MedicalRecordMapper.ExportPayload;
import com.medicalai.service.ExportFileService;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class ExportControllerTest {
    @Test
    void downloadsSucceededExportFromLocalExportStorage() throws Exception {
        MedicalRecordMapper records = org.mockito.Mockito.mock(MedicalRecordMapper.class);
        ExportFileService files = org.mockito.Mockito.mock(ExportFileService.class);
        ExportController controller = new ExportController(records, files);
        UUID exportId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        String storedPath = "exports/visit-1/record.docx";
        AuthenticatedDoctor doctor = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(doctorId,
                "DEMO", "doctor-1", "测试医生", null, null, "DOCTOR", "ACTIVE"));
        when(records.findExportForDownload(exportId, doctorId)).thenReturn(Optional.of(
                new MedicalRecordMapper.ExportDownload(exportId, "DOCX", "SUCCEEDED", storedPath, null)));
        when(files.load(storedPath)).thenReturn(new ByteArrayResource("docx".getBytes(StandardCharsets.UTF_8)));

        var response = controller.download(exportId, doctor);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                response.getHeaders().getContentType().toString());
        verify(files).load(storedPath);
    }

    @Test
    void downloadsSucceededPdfFromLocalExportStorage() throws Exception {
        MedicalRecordMapper records = org.mockito.Mockito.mock(MedicalRecordMapper.class);
        ExportFileService files = org.mockito.Mockito.mock(ExportFileService.class);
        ExportController controller = new ExportController(records, files);
        UUID exportId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        String storedPath = "exports/visit-1/record.pdf";
        AuthenticatedDoctor doctor = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(doctorId,
                "DEMO", "doctor-1", "测试医生", null, null, "DOCTOR", "ACTIVE"));
        when(records.findExportForDownload(exportId, doctorId)).thenReturn(Optional.of(
                new MedicalRecordMapper.ExportDownload(exportId, "PDF", "SUCCEEDED", storedPath, null)));
        when(files.load(storedPath)).thenReturn(new ByteArrayResource("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));

        var response = controller.download(exportId, doctor);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").endsWith(".pdf"));
        verify(files).load(storedPath);
    }

    @Test
    void letsDepartmentHeadDownloadASucceededExportFromAnotherDoctor() throws Exception {
        MedicalRecordMapper records = org.mockito.Mockito.mock(MedicalRecordMapper.class);
        ExportFileService files = org.mockito.Mockito.mock(ExportFileService.class);
        ExportController controller = new ExportController(records, files);
        UUID exportId = UUID.randomUUID();
        UUID departmentHeadId = UUID.randomUUID();
        String storedPath = "exports/visit-1/record.pdf";
        AuthenticatedDoctor departmentHead = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(departmentHeadId,
                "DEMO", "head-1", "科室长", null, null, "DEPARTMENT_HEAD", "ACTIVE"));
        when(records.findExportForDownload(exportId, departmentHeadId, true)).thenReturn(Optional.of(
                new MedicalRecordMapper.ExportDownload(exportId, "PDF", "SUCCEEDED", storedPath, null)));
        when(files.load(storedPath)).thenReturn(new ByteArrayResource("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));

        var response = controller.download(exportId, departmentHead);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        verify(records).findExportForDownload(exportId, departmentHeadId, true);
        verify(files).load(storedPath);
    }

    @Test
    void marksMissingLegacyFileAsFailedSoItCanBeRegenerated() {
        MedicalRecordMapper records = org.mockito.Mockito.mock(MedicalRecordMapper.class);
        ExportFileService files = org.mockito.Mockito.mock(ExportFileService.class);
        ExportController controller = new ExportController(records, files);
        UUID exportId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        String storedPath = "exports/visit-1/missing.pdf";
        AuthenticatedDoctor doctor = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(doctorId,
                "DEMO", "doctor-1", "测试医生", null, null, "DOCTOR", "ACTIVE"));
        when(records.findExportForDownload(exportId, doctorId)).thenReturn(Optional.of(
                new MedicalRecordMapper.ExportDownload(exportId, "PDF", "SUCCEEDED", storedPath, null)));
        when(files.load(storedPath)).thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                "EXPORT_FILE_NOT_FOUND", "导出文件不存在，请重新导出"));

        assertThrows(BusinessException.class, () -> controller.download(exportId, doctor));

        verify(records).markExportFileMissing(exportId);
    }

    @Test
    void regeneratesMissingPdfFromConfirmedRecordBeforeDownload() {
        MedicalRecordMapper records = org.mockito.Mockito.mock(MedicalRecordMapper.class);
        ExportFileService files = org.mockito.Mockito.mock(ExportFileService.class);
        ExportController controller = new ExportController(records, files);
        UUID exportId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        String storedPath = "exports/visit-1/record.pdf";
        AuthenticatedDoctor doctor = new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(doctorId,
                "DEMO", "doctor-1", "测试医生", null, null, "DOCTOR", "ACTIVE"));
        ExportPayload payload = new ExportPayload(exportId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "VIS-001", 1, "PDF", "{\"chief\":\"头痛\"}", null, Instant.now());
        when(records.findExportForDownload(exportId, doctorId)).thenReturn(Optional.of(
                new MedicalRecordMapper.ExportDownload(exportId, "PDF", "SUCCEEDED", storedPath, null)));
        when(files.load(storedPath))
                .thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "EXPORT_FILE_NOT_FOUND", "导出文件不存在，请重新导出"))
                .thenReturn(new ByteArrayResource("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        when(records.exportPayload(exportId)).thenReturn(Optional.of(payload));
        when(files.generate(payload)).thenReturn(storedPath);

        var response = controller.download(exportId, doctor);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        verify(files).generate(payload);
        verify(records, org.mockito.Mockito.never()).markExportFileMissing(exportId);
    }
}

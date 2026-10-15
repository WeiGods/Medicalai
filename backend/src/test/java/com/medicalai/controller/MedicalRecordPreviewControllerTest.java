package com.medicalai.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.ExportMedicalRecordRequest;
import com.medicalai.exception.BusinessException;
import com.medicalai.service.ClinicalWorkflowService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MedicalRecordPreviewControllerTest {
    @Test
    void forwardsDepartmentHeadRoleForReadOnlyPdfPreview() {
        ClinicalWorkflowService workflow = mock(ClinicalWorkflowService.class);
        MedicalRecordController controller = new MedicalRecordController(workflow);
        AuthenticatedDoctor head = departmentHead();
        UUID visitId = UUID.randomUUID();
        ExportMedicalRecordRequest request = new ExportMedicalRecordRequest("PDF", UUID.randomUUID());
        byte[] pdf = new byte[] {'%', 'P', 'D', 'F'};
        when(workflow.previewExport(visitId, head.doctor().id(), DoctorRole.DEPARTMENT_HEAD, request)).thenReturn(pdf);

        var response = controller.previewExport(visitId, request, head);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        assertArrayEquals(pdf, response.getBody().getByteArray());
        verify(workflow).previewExport(visitId, head.doctor().id(), DoctorRole.DEPARTMENT_HEAD, request);
    }

    @Test
    void departmentHeadStillCannotCreateAnExportTaskOrModifyARecord() {
        ClinicalWorkflowService workflow = mock(ClinicalWorkflowService.class);
        MedicalRecordController controller = new MedicalRecordController(workflow);
        AuthenticatedDoctor head = departmentHead();
        UUID visitId = UUID.randomUUID();

        BusinessException exportError = assertThrows(BusinessException.class,
                () -> controller.export(visitId, new ExportMedicalRecordRequest("PDF", UUID.randomUUID()), head));
        BusinessException editError = assertThrows(BusinessException.class, () -> controller.edit(visitId, head));

        assertEquals("CLINICAL_READ_ONLY", exportError.code());
        assertEquals("CLINICAL_READ_ONLY", editError.code());
        verifyNoInteractions(workflow);
    }

    private AuthenticatedDoctor departmentHead() {
        return new AuthenticatedDoctor(UUID.randomUUID(), new Doctor(UUID.randomUUID(), "DEMO", "head",
                "科室长", null, "内科", "DEPARTMENT_HEAD", "ACTIVE"));
    }
}

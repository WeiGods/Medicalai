package com.medicalai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.PatientDeletionMapper;
import com.medicalai.mapper.PatientMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits the audit trail, durable file-cleanup request and patient-wide database purge atomically. */
@Service
public class PatientDeletionTransaction {
    private final PatientMapper patients;
    private final PatientDeletionMapper deletions;
    private final AuditLogService auditLogs;
    private final ObjectMapper objectMapper;

    public PatientDeletionTransaction(PatientMapper patients, PatientDeletionMapper deletions,
                                      AuditLogService auditLogs, ObjectMapper objectMapper) {
        this.patients = patients;
        this.deletions = deletions;
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID delete(UUID patientId, Doctor doctor, String clientIp) {
        if (!DoctorRole.from(doctor.role()).canDeletePatients()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PATIENT_DELETE_FORBIDDEN", "仅科室长可以删除患者");
        }
        var patient = patients.findByIdForUpdate(patientId).orElseThrow(BusinessException::notFound);
        List<PatientDeletionMapper.VisitRef> visits = deletions.lockVisits(patientId);
        List<UUID> visitIds = visits.stream().map(PatientDeletionMapper.VisitRef::id).toList();
        if (deletions.hasOpenSession(visitIds)) {
            throw BusinessException.conflict("RECORDING_OPEN", "该患者存在进行中录音，请先停止录音后再删除");
        }

        // Writers lock visits before jobs/exports. Waiting here lets active work finish before paths are captured.
        deletions.lockJobs(visitIds);
        List<PatientDeletionMapper.ExportAsset> exports = deletions.lockExportAssets(visitIds);
        LinkedHashSet<String> exportKeys = new LinkedHashSet<>();
        for (PatientDeletionMapper.ExportAsset export : exports) {
            if (export.objectKey() != null && !export.objectKey().isBlank()) exportKeys.add(export.objectKey());
            String extension = "DOCX".equalsIgnoreCase(export.format()) ? "docx" : "pdf";
            exportKeys.add("exports/" + export.id() + "." + extension);
            exportKeys.add("exports/" + export.visitId() + "/" + export.id() + "." + extension);
        }

        UUID deletionId = UUID.randomUUID();
        deletions.insertCleanupTask(deletionId, json(visitIds), json(List.copyOf(exportKeys)), doctor.id());
        for (PatientDeletionMapper.VisitRef visit : visits) {
            deletions.snapshotAuditVisit(patient.id(), patient.name(), patient.patientNo(), visit.id(), visit.visitNo());
        }
        deletions.detachAuditLogs(patient.id(), patient.name(), patient.patientNo());
        auditLogs.recordPatientDeleted(doctor, patient.id(), patient.name(), patient.patientNo(), visits.size(), clientIp);
        deletions.deletePatientChain(patient.id(), visitIds);
        return deletionId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "PATIENT_DELETE_FAILED",
                    "患者删除任务创建失败", exception);
        }
    }
}

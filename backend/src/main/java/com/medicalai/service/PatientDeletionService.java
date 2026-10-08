package com.medicalai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.PatientDeletionResult;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.PatientDeletionMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PatientDeletionService {
    private static final Logger LOG = LoggerFactory.getLogger(PatientDeletionService.class);
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final PatientDeletionTransaction transaction;
    private final PatientDeletionMapper deletions;
    private final AudioStorageService audioStorage;
    private final ExportFileService exportFiles;
    private final ObjectMapper objectMapper;

    public PatientDeletionService(PatientDeletionTransaction transaction, PatientDeletionMapper deletions,
                                  AudioStorageService audioStorage, ExportFileService exportFiles,
                                  ObjectMapper objectMapper) {
        this.transaction = transaction;
        this.deletions = deletions;
        this.audioStorage = audioStorage;
        this.exportFiles = exportFiles;
        this.objectMapper = objectMapper;
    }

    public PatientDeletionResult delete(UUID patientId, Doctor doctor, String clientIp) {
        if (!DoctorRole.from(doctor.role()).canDeletePatients()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PATIENT_DELETE_FORBIDDEN", "仅科室长可以删除患者");
        }
        UUID deletionId = transaction.delete(patientId, doctor, clientIp);
        try {
            processTask(deletionId);
        } catch (RuntimeException exception) {
            LOG.warn("患者业务数据已提交，但即时文件清理未能启动：deletionId={}", deletionId, exception);
        }
        return new PatientDeletionResult(deletionId, completedOrPending(deletionId));
    }

    public PatientDeletionResult status(UUID deletionId, Doctor doctor) {
        if (!DoctorRole.from(doctor.role()).canDeletePatients()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PATIENT_DELETE_FORBIDDEN", "仅科室长可以查询患者删除状态");
        }
        return new PatientDeletionResult(deletionId, status(deletionId));
    }

    @Scheduled(fixedDelayString = "${medicalai.patient-deletion.worker-delay-ms:5000}")
    public void processNext() {
        deletions.claimNextCleanupTask().ifPresent(this::clean);
    }

    private void processTask(UUID deletionId) {
        deletions.claimCleanupTask(deletionId).ifPresent(this::clean);
    }

    private void clean(PatientDeletionMapper.CleanupTask task) {
        try {
            List<UUID> visitIds = objectMapper.readValue(task.visitIdsJson(), UUID_LIST);
            List<String> exportKeys = objectMapper.readValue(task.exportKeysJson(), STRING_LIST);
            for (UUID visitId : visitIds) audioStorage.deleteVisitAssets(visitId);
            for (String exportKey : exportKeys) exportFiles.deleteStoredExport(exportKey);
            deletions.completeCleanupTask(task.id());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            boolean retryRegistered = false;
            try {
                retryRegistered = deletions.retryCleanupTask(task.id(), task.attempt(), message);
            } catch (RuntimeException retryException) {
                // A RUNNING lease is recovered automatically after five minutes if the database is temporarily down.
                LOG.error("患者文件清理失败，暂时无法登记重试：deletionId={}, attempt={}",
                        task.id(), task.attempt(), retryException);
            }
            if (retryRegistered) {
                LOG.warn("患者文件清理失败，已登记重试：deletionId={}, attempt={}, reason={}",
                        task.id(), task.attempt(), message);
            } else {
                LOG.warn("患者文件清理任务状态已变化，等待调度器检查：deletionId={}, attempt={}, reason={}",
                        task.id(), task.attempt(), message);
            }
        }
    }

    private String completedOrPending(UUID deletionId) {
        try {
            return status(deletionId);
        } catch (RuntimeException exception) {
            LOG.warn("患者业务数据已提交，但无法读取即时文件清理状态：deletionId={}", deletionId, exception);
            return "PENDING";
        }
    }

    private String status(UUID deletionId) {
        return deletions.cleanupStatus(deletionId).map(value -> "COMPLETED".equals(value) ? value : "PENDING")
                .orElseThrow(BusinessException::notFound);
    }
}

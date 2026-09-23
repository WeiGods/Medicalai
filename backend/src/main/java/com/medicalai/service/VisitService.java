package com.medicalai.service;

import com.medicalai.domain.*;
import com.medicalai.dto.CreateVisitRequest;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import com.medicalai.vo.VisitVO;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VisitService {
    private final VisitMapper visits;
    private final PatientMapper patients;
    private final DoctorMapper doctors;
    private final AudioStorageService storage;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditLogService auditLogs;

    public VisitService(VisitMapper visits, PatientMapper patients, DoctorMapper doctors,
                        AudioStorageService storage, Clock clock) {
        this.visits=visits; this.patients=patients; this.doctors=doctors; this.storage=storage; this.clock=clock;
    }

    public List<VisitVO> list(Doctor doctor) {
        List<Visit> visibleVisits = DoctorRole.from(doctor.role()).canReadAllPatientStatuses()
                ? visits.findAll() : visits.findAll(doctor.id());
        return visibleVisits.stream().map(VisitVO::from).toList();
    }

    public VisitVO get(UUID id, UUID doctorId) { return VisitVO.from(owned(id,doctorId,false)); }

    public VisitVO get(UUID id, UUID doctorId, DoctorRole role, String clientIp) {
        Visit visit = readable(id, doctorId, role);
        if (role == DoctorRole.DEPARTMENT_HEAD && !doctorId.equals(visit.doctorId()) && auditLogs != null) {
            auditLogs.recordVisitDetailViewed(doctorId, visit.id(), visit.visitNo(), visit.doctorNameSnapshot(), clientIp);
        }
        return VisitVO.from(visit);
    }

    @Transactional
    public VisitVO create(CreateVisitRequest request, Doctor doctor) {
        Patient p=patients.findOwnedByIdForUpdate(request.patientId(), doctor.id())
                .orElseThrow(BusinessException::notFound);
        visits.blockingStatusForPatient(p.id(), doctor.id()).ifPresent(status -> {
            if ("COMPLETED".equals(status) || "ARCHIVED".equals(status)) {
                throw BusinessException.conflict("PATIENT_VISIT_COMPLETED", "该患者接诊已完成，暂不支持重复接诊");
            }
            throw BusinessException.conflict("PATIENT_VISIT_EXISTS", "该患者已有进行中的接诊");
        });
        LocalDate today=LocalDate.now(clock);
        Integer age=p.birthDate()==null ? null : Period.between(p.birthDate(),today).getYears();
        return VisitVO.from(visits.create(UUID.randomUUID(),p,doctor,age,request.chiefComplaint(),today));
    }

    @Transactional
    public VisitVO start(UUID id, UUID doctorId) {
        doctors.lock(doctorId);
        Visit visit=owned(id,doctorId,true);
        if ("ACTIVE".equals(visit.status())) return VisitVO.from(visit);
        if (!"WAITING".equals(visit.status())) throw invalidState();
        if (visits.hasOtherActive(doctorId,id)) {
            throw BusinessException.conflict("ACTIVE_VISIT_EXISTS","请先完成或取消当前接诊");
        }
        return VisitVO.from(visits.updateStatus(id,doctorId,"ACTIVE"));
    }

    @Transactional
    public VisitVO complete(UUID id, UUID doctorId) {
        Visit visit=owned(id,doctorId,true);
        if ("COMPLETED".equals(visit.status())) return VisitVO.from(visit);
        if (!"ACTIVE".equals(visit.status())) throw invalidState();
        if (visits.hasOpenSession(id)) throw BusinessException.conflict("RECORDING_OPEN","请先停止录音");
        if (!visits.hasConfirmedCurrentRecord(id)) {
            throw BusinessException.conflict("CONFIRMED_RECORD_REQUIRED","请先生成并确认当前病历");
        }
        if (!visits.hasSuccessfulExportForCurrentRecord(id)) {
            throw BusinessException.conflict("EXPORT_REQUIRED", "请至少成功导出一份当前确认病历");
        }
        return VisitVO.from(visits.updateStatus(id,doctorId,"COMPLETED"));
    }

    @Transactional
    public void cancel(UUID id, UUID doctorId) {
        Visit visit=owned(id,doctorId,true);
        if (!Set.of("WAITING","ACTIVE").contains(visit.status())) throw invalidState();
        if (visits.hasOpenSession(id)) throw BusinessException.conflict("RECORDING_OPEN","请先停止录音");
        visits.deleteCancelledVisit(id, doctorId);
        storage.deleteVisitAssets(id);
    }

    private Visit owned(UUID id, UUID doctorId, boolean lock) {
        return visits.find(id,doctorId,lock).orElseThrow(BusinessException::notFound);
    }

    private Visit readable(UUID id, UUID doctorId, DoctorRole role) {
        if (role == DoctorRole.DEPARTMENT_HEAD) {
            return visits.find(id, false).orElseThrow(BusinessException::notFound);
        }
        return owned(id, doctorId, false);
    }

    private static BusinessException invalidState() {
        return BusinessException.conflict("INVALID_VISIT_STATE","当前接诊状态不允许此操作");
    }
}

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
    private final Clock clock;

    public VisitService(VisitMapper visits, PatientMapper patients, DoctorMapper doctors, Clock clock) {
        this.visits=visits; this.patients=patients; this.doctors=doctors; this.clock=clock;
    }

    public List<VisitVO> list(UUID doctorId) {
        return visits.findAll(doctorId).stream().map(VisitVO::from).toList();
    }

    public VisitVO get(UUID id, UUID doctorId) { return VisitVO.from(owned(id,doctorId,false)); }

    @Transactional
    public VisitVO create(CreateVisitRequest request, Doctor doctor) {
        Patient p=patients.findById(request.patientId()).orElseThrow(BusinessException::notFound);
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
        return VisitVO.from(visits.updateStatus(id,doctorId,"COMPLETED"));
    }

    @Transactional
    public VisitVO cancel(UUID id, UUID doctorId) {
        Visit visit=owned(id,doctorId,true);
        if ("CANCELLED".equals(visit.status())) return VisitVO.from(visit);
        if (!Set.of("WAITING","ACTIVE").contains(visit.status())) throw invalidState();
        if (visits.hasOpenSession(id)) throw BusinessException.conflict("RECORDING_OPEN","请先停止录音");
        return VisitVO.from(visits.updateStatus(id,doctorId,"CANCELLED"));
    }

    private Visit owned(UUID id, UUID doctorId, boolean lock) {
        return visits.find(id,doctorId,lock).orElseThrow(BusinessException::notFound);
    }

    private static BusinessException invalidState() {
        return BusinessException.conflict("INVALID_VISIT_STATE","当前接诊状态不允许此操作");
    }
}

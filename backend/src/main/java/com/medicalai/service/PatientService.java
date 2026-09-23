package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.dto.CreatePatientRequest;
import com.medicalai.mapper.PatientMapper;
import com.medicalai.vo.PatientVO;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {
    private final PatientMapper mapper;
    private final Clock clock;

    public PatientService(PatientMapper mapper, Clock clock) {
        this.mapper=mapper; this.clock=clock;
    }

    public List<PatientVO> list(String keyword, Doctor doctor) {
        return mapper.find(keyword.strip(), doctor.id(), canReadAllPatientStatuses(doctor)).stream()
                .map(p -> PatientVO.from(p, LocalDate.now(clock))).toList();
    }

    public PatientVO get(UUID id, Doctor doctor) {
        return PatientVO.from(mapper.findById(id, doctor.id(), canReadAllPatientStatuses(doctor))
                .orElseThrow(BusinessException::notFound), LocalDate.now(clock));
    }

    @Transactional
    public PatientVO create(CreatePatientRequest request, Doctor doctor) {
        String name = request.name() == null ? "" : request.name().strip();
        String gender = request.gender() == null ? "" : request.gender().strip();
        if (name.isBlank()) throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "PATIENT_NAME_REQUIRED", "请输入患者姓名");
        if (gender.isBlank()) throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "PATIENT_GENDER_REQUIRED", "请选择患者性别");
        if (request.phone() == null || request.phone().isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "PATIENT_PHONE_REQUIRED", "请输入联系方式");
        }
        LocalDate today = LocalDate.now(clock);
        String phone = PatientFieldValidator.normalizePhone(request.phone());
        String idNo = PatientFieldValidator.normalizeIdNumber(request.idNo(), today);
        LocalDate birthDate = request.age() == null ? null : today.minusYears(request.age());
        PatientVO created = PatientVO.from(mapper.insertManual(
                UUID.randomUUID(),
                name,
                gender,
                birthDate,
                maskPhone(phone),
                maskIdNo(idNo),
                doctor.departmentName(), doctor.id()), today);
        return created;
    }

    private boolean canReadAllPatientStatuses(Doctor doctor) {
        return DoctorRole.from(doctor.role()).canReadAllPatientStatuses();
    }
    private String maskPhone(String value) {
        return mask(value, 3, 4);
    }

    private String maskIdNo(String value) {
        String normalized = value == null ? "" : value.strip();
        // A complete ID ending in X must be masked; the generic mask cannot distinguish it
        // from a value that an upstream system has already desensitized.
        if (normalized.matches("\\d{17}X")) {
            return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 4);
        }
        return mask(value, 3, 4);
    }

    private String mask(String value, int prefix, int suffix) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isBlank()) return null;
        // 保留上游系统已完成脱敏的值。
        if (normalized.indexOf('*') >= 0 || normalized.indexOf('X') >= 0 || normalized.indexOf('x') >= 0) {
            return normalized;
        }
        if (normalized.length() <= prefix + suffix) return normalized;
        return normalized.substring(0, prefix) + "****" + normalized.substring(normalized.length() - suffix);
    }
}

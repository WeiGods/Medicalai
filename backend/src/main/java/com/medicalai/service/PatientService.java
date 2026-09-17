package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import com.medicalai.domain.Doctor;
import com.medicalai.dto.CreatePatientRequest;
import com.medicalai.mapper.PatientMapper;
import com.medicalai.provider.PatientDirectoryProvider;
import com.medicalai.vo.PatientVO;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {
    private final PatientMapper mapper;
    private final PatientDirectoryProvider provider;
    private final Clock clock;

    public PatientService(PatientMapper mapper, PatientDirectoryProvider provider, Clock clock) {
        this.mapper=mapper; this.provider=provider; this.clock=clock;
    }

    public List<PatientVO> list(String keyword) {
        return mapper.find(keyword.strip()).stream().map(p -> PatientVO.from(p, LocalDate.now(clock))).toList();
    }

    public PatientVO get(UUID id) {
        return PatientVO.from(mapper.findById(id).orElseThrow(BusinessException::notFound), LocalDate.now(clock));
    }

    @Transactional
    public PatientVO create(CreatePatientRequest request, Doctor doctor) {
        String name = request.name() == null ? "" : request.name().strip();
        String gender = request.gender() == null ? "" : request.gender().strip();
        if (name.isBlank()) throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "PATIENT_NAME_REQUIRED", "请输入患者姓名");
        if (gender.isBlank()) throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "PATIENT_GENDER_REQUIRED", "请选择患者性别");
        LocalDate today = LocalDate.now(clock);
        LocalDate birthDate = request.age() == null ? null : today.minusYears(request.age());
        PatientVO created = PatientVO.from(mapper.insertManual(
                UUID.randomUUID(),
                name,
                gender,
                birthDate,
                maskPhone(request.phone()),
                maskIdNo(request.idNo()),
                doctor == null ? "" : doctor.departmentName()), today);
        return created;
    }

    @Transactional
    public void syncDemoPatients() { provider.listPatients().forEach(mapper::upsert); }

    private String maskPhone(String value) {
        return mask(value, 3, 4);
    }

    private String maskIdNo(String value) {
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

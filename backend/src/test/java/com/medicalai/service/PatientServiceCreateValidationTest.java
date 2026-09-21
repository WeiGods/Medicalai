package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.medicalai.domain.Doctor;
import com.medicalai.dto.CreatePatientRequest;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.PatientMapper;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatientServiceCreateValidationTest {
    PatientMapper mapper = mock(PatientMapper.class);
    PatientService service = new PatientService(mapper, Clock.systemUTC());
    Doctor doctor = new Doctor(UUID.randomUUID(), "his", "u1", "张医生", "dep1", "心内科", "DOCTOR", "ACTIVE");

    @Test
    void blankPhoneRejectedBeforePersistence() {
        var request = new CreatePatientRequest("张三", "男", 30, "  ", null);
        var error = assertThrows(BusinessException.class, () -> service.create(request, doctor));
        assertEquals("PATIENT_PHONE_REQUIRED", error.code());
        verifyNoInteractions(mapper);
    }

    @Test
    void nullPhoneRejectedBeforePersistence() {
        var request = new CreatePatientRequest("张三", "男", 30, null, null);
        var error = assertThrows(BusinessException.class, () -> service.create(request, doctor));
        assertEquals("PATIENT_PHONE_REQUIRED", error.code());
        verifyNoInteractions(mapper);
    }
}

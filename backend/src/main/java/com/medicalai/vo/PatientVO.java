package com.medicalai.vo;

import com.medicalai.domain.Patient;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

public record PatientVO(UUID id, String patientNo, String name, String gender,
                            Integer age, String phoneMasked, String idNoMasked, String status,
                            UUID createdBy, String createdByName) {
    public static PatientVO from(Patient p, LocalDate today) {
        Integer age = p.birthDate() == null ? null : Period.between(p.birthDate(), today).getYears();
        return new PatientVO(p.id(), p.patientNo(), p.name(), p.gender(), age, p.phoneMasked(), p.idNoMasked(),
                p.status(), p.createdBy(), p.createdByName());
    }
}

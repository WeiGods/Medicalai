package com.medicalai.vo;

import com.medicalai.domain.Doctor;
import java.util.UUID;

public record DoctorVO(UUID id, String displayName, String departmentName, String role, String source) {
    public static DoctorVO from(Doctor d) {
        return new DoctorVO(d.id(), d.displayName(), d.departmentName(), d.role(), d.externalSystem());
    }
}

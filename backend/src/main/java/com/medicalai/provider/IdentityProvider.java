package com.medicalai.provider;

import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;

public interface IdentityProvider {
    Doctor authenticate(String credential, DoctorRole requestedRole);
}

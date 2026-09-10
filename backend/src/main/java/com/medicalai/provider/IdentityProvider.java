package com.medicalai.provider;

import com.medicalai.domain.Doctor;

public interface IdentityProvider {
    Doctor authenticate(String credential);
}

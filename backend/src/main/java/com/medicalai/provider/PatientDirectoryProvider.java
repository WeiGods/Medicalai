package com.medicalai.provider;

import java.util.List;

public interface PatientDirectoryProvider {
    List<PatientProfile> listPatients();
}

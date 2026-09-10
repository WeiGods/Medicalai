package com.medicalai.provider;

import java.time.LocalDate;

public record PatientProfile(String sourceSystem, String sourcePatientId, String patientNo,
                             String name, String gender, LocalDate birthDate, String phoneMasked,
                             String idNoMasked) {}

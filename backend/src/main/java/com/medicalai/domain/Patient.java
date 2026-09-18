package com.medicalai.domain;

import java.time.LocalDate;
import java.util.UUID;

public record Patient(UUID id, String patientNo, String name, String gender,
                      LocalDate birthDate, String phoneMasked, String idNoMasked,
                      String departmentName, String status, UUID createdBy, String createdByName) {}

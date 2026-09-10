package com.medicalai.vo;

public record MedicalRecordContentVO(String name, String gender, Integer age, String phone,
                                     String chief, String present, String past, String opinion,
                                     String medication, String followup, String doctor, String date) {}

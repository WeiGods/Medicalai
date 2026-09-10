package com.medicalai.vo;

public record LoginVO(String accessToken, String tokenType, long expiresIn, DoctorVO doctor) {}

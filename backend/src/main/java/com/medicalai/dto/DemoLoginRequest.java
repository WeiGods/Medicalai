package com.medicalai.dto;

import com.medicalai.domain.DoctorRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DemoLoginRequest(
        @NotBlank(message = "请输入医生名称") @Size(max = 128, message = "医生名称最多128个字符")
        String doctorName,
        @NotNull(message = "请选择登录身份") DoctorRole role) {}

package com.medicalai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Patient details entered by a doctor when no directory record is available. */
public record CreatePatientRequest(
        @NotBlank(message = "请输入患者姓名")
        @Size(max = 128, message = "患者姓名不能超过128个字符") String name,
        @NotBlank(message = "请选择患者性别")
        @Size(max = 16, message = "性别不能超过16个字符") String gender,
        @Min(value = 0, message = "年龄不能小于0岁")
        @Max(value = 150, message = "年龄不能超过150岁") Integer age,
        @Size(max = 64, message = "联系方式不能超过64个字符") String phone,
        @Size(max = 64, message = "证件号不能超过64个字符") String idNo) {}

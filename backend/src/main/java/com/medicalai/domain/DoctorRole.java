package com.medicalai.domain;

import java.util.Arrays;

/** 系统内的人员角色。医院单点登录接入后由身份提供方映射为这些稳定角色。 */
public enum DoctorRole {
    DOCTOR,
    DEPARTMENT_HEAD,
    ADMIN;

    public boolean canReadAuditLog() {
        return this == DEPARTMENT_HEAD || this == ADMIN;
    }

    /** 科室长和管理员仅可汇总查看全员患者及接诊状态。 */
    public boolean canReadAllPatientStatuses() {
        return this == DEPARTMENT_HEAD || this == ADMIN;
    }

    public boolean canUseDemoLogin() {
        return this == DOCTOR || this == DEPARTMENT_HEAD;
    }

    public static DoctorRole from(String value) {
        return Arrays.stream(values())
                .filter(role -> role.name().equals(value))
                .findFirst()
                .orElse(DOCTOR);
    }
}

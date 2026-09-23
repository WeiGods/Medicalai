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

    /** 只有科室长可以汇总查看全院患者及接诊状态。 */
    public boolean canReadAllPatientStatuses() {
        return this == DEPARTMENT_HEAD;
    }

    /** 科室长是跨医生只读角色，临床写操作只允许普通医生执行。 */
    public boolean canWriteClinicalData() {
        return this == DOCTOR;
    }

    public boolean canUseDemoLogin() {
        return this == DOCTOR || this == DEPARTMENT_HEAD;
    }

    /** Export layouts affect clinical documents across the hospital, so administrators are intentionally excluded. */
    public boolean canManageTemplates() {
        return this == DEPARTMENT_HEAD;
    }

    public static DoctorRole from(String value) {
        return Arrays.stream(values())
                .filter(role -> role.name().equals(value))
                .findFirst()
                .orElse(DOCTOR);
    }
}

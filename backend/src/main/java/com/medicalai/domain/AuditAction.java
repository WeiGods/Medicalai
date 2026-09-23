package com.medicalai.domain;

/** 本期向科室长开放查询的审计动作。 */
public enum AuditAction {
    LOGIN,
    VISIT_DETAIL_VIEWED,
    RECORDING_UPLOADED,
    RECORDING_DELETED,
    MEDICAL_RECORD_CONFIRMED,
    MEDICAL_RECORD_EXPORT
}

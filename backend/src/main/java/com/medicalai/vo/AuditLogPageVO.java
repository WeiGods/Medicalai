package com.medicalai.vo;

import java.util.List;

public record AuditLogPageVO(List<AuditLogVO> items, long total, int page, int pageSize) {
}

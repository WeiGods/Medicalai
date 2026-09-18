package com.medicalai.dto;

import com.medicalai.domain.AuditAction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record AuditLogQueryRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        UUID doctorId,
        AuditAction action,
        @Min(value = 1, message = "页码必须大于 0") @Max(value = 1_000_000, message = "页码超出范围") Integer page,
        @Min(value = 1, message = "每页数量必须大于 0") @Max(value = 100, message = "每页数量不能超过 100") Integer pageSize) {

    public int normalizedPage() {
        return page == null ? 1 : page;
    }

    public int normalizedPageSize() {
        return pageSize == null ? 20 : pageSize;
    }
}

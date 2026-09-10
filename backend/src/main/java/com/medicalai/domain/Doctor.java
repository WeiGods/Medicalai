package com.medicalai.domain;

import java.util.UUID;

public record Doctor(UUID id, String externalSystem, String externalUserId,
                     String displayName, String departmentId, String departmentName,
                     String role, String status) {}

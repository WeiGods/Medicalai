package com.medicalai.dto;

import java.util.UUID;

public record PatientDeletionResult(UUID deletionId, String cleanupStatus) {}

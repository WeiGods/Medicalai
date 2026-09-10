package com.medicalai.vo;

import java.time.Instant;
import java.util.UUID;

public record ConfirmationVO(UUID id, int versionNo, String doctorName, Instant confirmedAt) {}

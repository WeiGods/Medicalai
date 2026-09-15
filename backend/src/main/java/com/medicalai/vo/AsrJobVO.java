package com.medicalai.vo;

import java.util.UUID;

public record AsrJobVO(UUID jobId, String status, int totalRecordings, int completedRecordings,
                       String errorMessage, TranscriptVO transcript, String providerRoute) {}

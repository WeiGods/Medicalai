package com.medicalai.domain;

import java.util.UUID;

public record AuthenticatedDoctor(UUID sessionId, Doctor doctor) {}

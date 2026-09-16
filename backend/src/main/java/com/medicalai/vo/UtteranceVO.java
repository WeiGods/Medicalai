package com.medicalai.vo;

import com.medicalai.domain.Utterance;

public record UtteranceVO(String id, String role, String text, long startMs, long endMs, Integer speakerId,
                          String roleSource, Integer roleConfidence, boolean roleReviewRequired) {
    public static UtteranceVO from(Utterance u, int reviewThreshold) {
        boolean manual = "MANUAL".equals(u.roleSource());
        boolean reviewRequired = !manual && (!"DOCTOR".equals(u.role()) && !"PATIENT".equals(u.role())
                || u.roleConfidence() == null || u.roleConfidence() < reviewThreshold);
        return new UtteranceVO(u.id().toString(), u.role(), u.text(), u.startMs(), u.endMs(), u.speakerId(),
                u.roleSource(), u.roleConfidence(), reviewRequired);
    }
}

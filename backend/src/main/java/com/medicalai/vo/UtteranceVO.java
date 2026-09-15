package com.medicalai.vo;

import com.medicalai.domain.Utterance;

public record UtteranceVO(String role, String text, long startMs, long endMs, Integer speakerId) {
    public static UtteranceVO from(Utterance u) {
        return new UtteranceVO(u.role(), u.text(), u.startMs(), u.endMs(), u.speakerId());
    }
}

package com.medicalai.vo;

import com.medicalai.domain.Utterance;
import java.util.List;
import java.util.UUID;

/** 音频页只读的 ASR 原文；不暴露或采纳正式角色判断。 */
public record AsrPreviewRecordingVO(UUID recordingId, String recordingNo, String fileName,
                                    String status, String errorMessage, List<Segment> segments) {
    public record Segment(String utteranceId, String text, long startMs, long endMs, Integer speakerId) {
        public static Segment from(Utterance utterance) {
            return new Segment(utterance.id().toString(), utterance.text(), utterance.startMs(),
                    utterance.endMs(), utterance.speakerId());
        }
    }
}

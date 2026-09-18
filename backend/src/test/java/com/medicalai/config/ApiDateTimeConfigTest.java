package com.medicalai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.vo.ConfirmationVO;
import com.medicalai.vo.MedicalRecordVO;
import com.medicalai.vo.RecordExportVO;
import com.medicalai.vo.RecordingVO;
import com.medicalai.vo.VisitVO;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

@JsonTest
@Import(ApiDateTimeConfig.class)
class ApiDateTimeConfigTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-09-15T07:55:32.029490Z");

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesAllApiTimestampFieldsInBeijingTimeWithoutFractionsOrOffset() throws Exception {
        UUID id = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(new Object[] {
                new VisitVO(id, "V001", id, id, "ACTIVE", "内科", "患者", "M", 30,
                        "医生", null, 1, TIMESTAMP, TIMESTAMP),
                new RecordingVO(id, id, "R001", "UPLOAD", "recording.wav", "audio/wav", 1L,
                        1L, "DONE", null, null, TIMESTAMP),
                new MedicalRecordVO(id, id, 1, "CONFIRMED", "SUCCEEDED", null, true,
                        TIMESTAMP, "医生", false),
                new ConfirmationVO(id, 1, "医生", TIMESTAMP),
                new RecordExportVO(id, 1, 2, "PDF", "SUCCEEDED", "医生", TIMESTAMP)
        });

        assertThat(json).contains("\"created_at\":\"2026-09-15 15:55:32\"");
        assertThat(json).contains("\"last_activity_at\":\"2026-09-15 15:55:32\"");
        assertThat(json).contains("\"confirmed_at\":\"2026-09-15 15:55:32\"");
        assertThat(json).doesNotContain("T07:55:32.029490Z");
        assertThat(json).doesNotContain("+08:00");
    }
}

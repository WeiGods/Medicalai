package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;

import com.medicalai.mapper.RecordingMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the real mapper SQL and bindings; no external database is touched. */
class RecordingUtterancePersistenceTest {
    @Test
    void persistsSpeakerAndTimestampsIncludingUnknownSpeaker() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        try {
            jdbc.execute("""
                    CREATE TABLE asr_utterance (
                        id UUID PRIMARY KEY, visit_id UUID, recording_id UUID, session_id UUID,
                        utterance_id VARCHAR(64), revision INTEGER, result_type VARCHAR(32),
                        text VARCHAR(1000), role VARCHAR(32), speaker_id INTEGER,
                        role_source VARCHAR(32), start_ms BIGINT, end_ms BIGINT, is_current BOOLEAN
                    )
                    """);
            UUID visit = UUID.randomUUID(), recording = UUID.randomUUID(), session = UUID.randomUUID();
            var turns = List.of(
                    new RecordingMapper.Turn("DOCTOR", "哪里不舒服？", 120L, 2560L, 3),
                    new RecordingMapper.Turn("OTHER", "头痛。", 3010L, 4900L, null));
            var ids = new RecordingMapper(jdbc).insertUtterances(visit, recording, session, turns);
            assertEquals(2, ids.size());
            for (int i = 0; i < turns.size(); i++) {
                var turn = turns.get(i);
                var row = jdbc.queryForMap("SELECT * FROM asr_utterance WHERE id=?", ids.get(i));
                assertEquals(visit, row.get("visit_id"));
                assertEquals(recording, row.get("recording_id"));
                assertEquals(session, row.get("session_id"));
                assertEquals("u-" + (i + 1), row.get("utterance_id"));
                assertEquals(0, row.get("revision"));
                assertEquals("CANONICAL", row.get("result_type"));
                assertEquals(turn.text(), row.get("text"));
                assertEquals(turn.role(), row.get("role"));
                assertEquals(turn.speakerId(), row.get("speaker_id"));
                assertEquals("AUTO", row.get("role_source"));
                assertEquals(turn.startMs(), row.get("start_ms"));
                assertEquals(turn.endMs(), row.get("end_ms"));
                assertEquals(true, row.get("is_current"));
            }
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}

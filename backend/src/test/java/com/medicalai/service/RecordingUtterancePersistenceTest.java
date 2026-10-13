package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;

import com.medicalai.mapper.RecordingMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 执行真实 Mapper SQL 和参数绑定，不访问外部数据库。 */
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
                        role_source VARCHAR(32), role_confidence INTEGER, role_provider_route VARCHAR(32),
                        start_ms BIGINT, end_ms BIGINT, is_current BOOLEAN,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            UUID visit = UUID.randomUUID(), recording = UUID.randomUUID(), session = UUID.randomUUID();
            var turns = List.of(
                    new RecordingMapper.Turn("DOCTOR", "哪里不舒服？", 120L, 2560L, 3, "LLM", 92, "DASHSCOPE"),
                    new RecordingMapper.Turn("OTHER", "头痛。", 3010L, 4900L, null, "FALLBACK", null, "LOCAL"));
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
                assertEquals(turn.roleSource(), row.get("role_source"));
                assertEquals(turn.roleConfidence(), row.get("role_confidence"));
                assertEquals(turn.roleProviderRoute(), row.get("role_provider_route"));
                assertEquals(turn.startMs(), row.get("start_ms"));
                assertEquals(turn.endMs(), row.get("end_ms"));
                assertEquals(true, row.get("is_current"));
            }
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void persistsPreviewSeparatelyWithoutMakingItCurrentOrCanonical() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        try {
            jdbc.execute("""
                    CREATE TABLE asr_utterance (
                        id UUID PRIMARY KEY, visit_id UUID, recording_id UUID, session_id UUID,
                        utterance_id VARCHAR(64), revision INTEGER, result_type VARCHAR(32),
                        text VARCHAR(1000), role VARCHAR(32), speaker_id INTEGER,
                        role_source VARCHAR(32), role_confidence INTEGER, role_provider_route VARCHAR(32),
                        start_ms BIGINT, end_ms BIGINT, is_current BOOLEAN,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            jdbc.execute("CREATE TABLE recording_session(id UUID PRIMARY KEY, recording_id UUID)");
            jdbc.execute("CREATE TABLE dialogue_snapshot(id UUID PRIMARY KEY, session_id UUID)");
            UUID visit = UUID.randomUUID(), recording = UUID.randomUUID(), session = UUID.randomUUID();
            var mapper = new RecordingMapper(jdbc);
            var turn = new RecordingMapper.Turn("UNKNOWN", "我头疼", 10L, 90L, 2, "UNKNOWN", null, "UNKNOWN");
            var ids = mapper.insertPreviewUtterances(visit, recording, session, List.of(turn));

            var row = jdbc.queryForMap("SELECT * FROM asr_utterance WHERE id=?", ids.getFirst());
            assertEquals("PREVIEW", row.get("result_type"));
            assertEquals("UNKNOWN", row.get("role"));
            assertEquals("我头疼", row.get("text"));
            assertEquals(false, row.get("is_current"));
            assertTrue(mapper.listByRecording(recording).isEmpty());
            assertEquals(ids.getFirst(), mapper.listPreviewByRecording(recording).getFirst().id());
            assertEquals(session, mapper.previewSessionId(recording).orElseThrow());

            mapper.clearPreview(recording);
            assertTrue(mapper.listPreviewByRecording(recording).isEmpty());
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void keepsAsrPreviewOutOfCanonicalQueries() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        try {
            jdbc.execute("""
                    CREATE TABLE asr_utterance (
                        id UUID PRIMARY KEY, visit_id UUID, recording_id UUID, session_id UUID,
                        utterance_id VARCHAR(64), revision INTEGER, result_type VARCHAR(32),
                        text VARCHAR(1000), role VARCHAR(32), speaker_id INTEGER,
                        role_source VARCHAR(32), role_confidence INTEGER, role_provider_route VARCHAR(32),
                        start_ms BIGINT, end_ms BIGINT, is_current BOOLEAN,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            var mapper = new RecordingMapper(jdbc);
            UUID visit = UUID.randomUUID(), recording = UUID.randomUUID(), session = UUID.randomUUID();
            var previewTurns = List.of(new RecordingMapper.Turn("UNKNOWN", "ASR 原始文本", 0L, 100L,
                    7, "UNKNOWN", null, "UNKNOWN"));
            mapper.insertPreviewUtterances(visit, recording, session, previewTurns);
            mapper.insertUtterances(visit, recording, session, List.of(
                    new RecordingMapper.Turn("PATIENT", "正式结果", 0L, 100L, 7, "LLM", 90, "DASHSCOPE")));

            assertEquals(List.of("正式结果"), mapper.listByRecording(recording).stream().map(row -> row.text()).toList());
            assertEquals(List.of("ASR 原始文本"), mapper.listPreviewByRecording(recording).stream()
                    .map(row -> row.text()).toList());
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}

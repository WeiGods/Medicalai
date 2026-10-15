package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AsrRecordingFailureGuardTest {
    UUID visit=UUID.randomUUID(),recording=UUID.randomUUID(),job=UUID.randomUUID();
    JdbcTemplate jdbc;
    RecordingMapper mapper;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE ALIAS medicalai_local_now FOR 'com.medicalai.service.VisitProcessingRouteTest.localNow'");
        jdbc.execute("CREATE TABLE recording(id UUID,status VARCHAR,error_code VARCHAR,error_message VARCHAR,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE ai_job(id UUID,visit_id UUID,recording_id UUID,job_type VARCHAR,status VARCHAR,provider_route VARCHAR)");
        mapper=new RecordingMapper(jdbc);
    }
    @ParameterizedTest @CsvSource({"DONE,RUNNING,DONE", "PROCESSING,PENDING,PROCESSING", "PROCESSING,RUNNING,FAILED"})
    void onlyOwnRunningTaskCanFailProcessingRecording(String recordingStatus,String jobStatus,String expected) {
        jdbc.update("INSERT INTO recording(id,status) VALUES (?,?)",recording,recordingStatus);
        jdbc.update("INSERT INTO ai_job VALUES (?,?,?,'ASR_TRANSCRIBE',?,'LOCAL')",job,visit,recording,jobStatus);
        mapper.failOwnedRecording(job,"rejected");
        assertEquals(expected,jdbc.queryForObject("SELECT status FROM recording WHERE id=?",String.class,recording));
    }
    @Test void anotherRunningOwnerPreventsRecordingFailure() {
        jdbc.update("INSERT INTO recording(id,status) VALUES (?,'PROCESSING')",recording);
        jdbc.update("INSERT INTO ai_job VALUES (?,?,?,'ASR_TRANSCRIBE','RUNNING','LOCAL')",job,visit,recording);
        jdbc.update("INSERT INTO ai_job VALUES (?,?,?,'ASR_TRANSCRIBE','RUNNING','LOCAL')",UUID.randomUUID(),visit,recording);
        mapper.failOwnedRecording(job,"rejected");
        assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM recording WHERE id=?",String.class,recording));
    }
    @Test void activeRouteConflictNormalizesLegacyPublicValuesAndIgnoresFinishedTasks() {
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO ai_job VALUES (?,?,?,'ASR_TRANSCRIBE','PENDING',NULL)",other,visit,recording);
        assertFalse(mapper.hasConflictingAsrJob(visit,job,"DASHSCOPE"));
        assertTrue(mapper.hasConflictingAsrJob(visit,job,"LOCAL"));
        jdbc.update("UPDATE ai_job SET provider_route='DASHSCOPE_ROLE_V2' WHERE id=?",other);
        assertFalse(mapper.hasConflictingAsrJob(visit,job,"DASHSCOPE"));
        jdbc.update("UPDATE ai_job SET status='FAILED' WHERE id=?",other);
        assertFalse(mapper.hasConflictingAsrJob(visit,job,"LOCAL"));
    }
}

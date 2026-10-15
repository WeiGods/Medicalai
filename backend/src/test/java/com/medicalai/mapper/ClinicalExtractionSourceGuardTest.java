package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.*;
import com.medicalai.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ClinicalExtractionSourceGuardTest {
    UUID visit = UUID.randomUUID(), doctor = UUID.randomUUID(), snapshot = UUID.randomUUID(), recording = UUID.randomUUID(), utterance = UUID.randomUUID();
    JdbcTemplate jdbc;
    ClinicalExtractionMapper mapper;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE visit(id UUID,doctor_id UUID,status VARCHAR)");
        jdbc.execute("CREATE TABLE medical_record(visit_id UUID,status VARCHAR)");
        jdbc.execute("CREATE TABLE recording(id UUID,visit_id UUID,status VARCHAR,asr_route VARCHAR)");
        jdbc.execute("CREATE TABLE ai_job(visit_id UUID,job_type VARCHAR,status VARCHAR)");
        jdbc.execute("CREATE TABLE recording_session(visit_id UUID,status VARCHAR)");
        jdbc.execute("CREATE TABLE dialogue_snapshot(id UUID,visit_id UUID,snapshot_hash VARCHAR,authority_status VARCHAR)");
        jdbc.execute("CREATE TABLE visit_transcript(visit_id UUID,snapshot_id UUID)");
        jdbc.execute("CREATE TABLE dialogue_snapshot_source(snapshot_id UUID,utterance_id UUID)");
        jdbc.execute("CREATE TABLE asr_utterance(id UUID,recording_id UUID)");
        jdbc.execute("CREATE TABLE clinical_extraction(visit_id UUID,current_version INT,status VARCHAR)");
        jdbc.update("INSERT INTO visit VALUES (?,?,'ACTIVE')",visit,doctor);
        jdbc.update("INSERT INTO recording VALUES (?,?,'DONE','LOCAL')",recording,visit);
        jdbc.update("INSERT INTO dialogue_snapshot VALUES (?,?,'current-hash','ADOPTED')",snapshot,visit);
        jdbc.update("INSERT INTO visit_transcript VALUES (?,?)",visit,snapshot);
        jdbc.update("INSERT INTO dialogue_snapshot_source VALUES (?,?)",snapshot,utterance);
        jdbc.update("INSERT INTO asr_utterance VALUES (?,?)",utterance,recording);
        jdbc.update("INSERT INTO clinical_extraction VALUES (?,7,'GENERATED')",visit);
        mapper = new ClinicalExtractionMapper(jdbc);
    }

    @Test void validPreflightFollowsLocalSource() {
        assertDoesNotThrow(()->mapper.validateSource(visit,doctor,snapshot,"current-hash","LOCAL"));
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,
                ()->mapper.validateSource(visit,doctor,snapshot,"current-hash","DASHSCOPE")).code());
    }

    @ParameterizedTest @ValueSource(strings={"GENERATED","FAILED"})
    void oldSuccessAndFailureBothLeaveNewCurrentVersionUntouched(String status) {
        UUID oldSnapshot = UUID.randomUUID();
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,()->mapper.insert(
                visit,oldSnapshot,"old-hash",status,"{}","[]","LOCAL","model",doctor)).code());
        assertEquals(7,jdbc.queryForObject("SELECT current_version FROM clinical_extraction WHERE visit_id=?",Integer.class,visit));
        assertEquals("GENERATED",jdbc.queryForObject("SELECT status FROM clinical_extraction WHERE visit_id=?",String.class,visit));
    }

    @Test void activeAsrAndConfirmedRecordRejectOldWriteback() {
        jdbc.update("INSERT INTO ai_job VALUES (?,'ASR_TRANSCRIBE','PENDING')",visit);
        assertEquals("SOURCE_CHANGED",assertThrows(BusinessException.class,()->mapper.validateSource(visit,doctor,snapshot,"current-hash","LOCAL")).code());
        jdbc.update("DELETE FROM ai_job WHERE visit_id=?",visit);
        jdbc.update("INSERT INTO medical_record VALUES (?,'CONFIRMED')",visit);
        assertEquals("RECORD_CONFIRMED",assertThrows(BusinessException.class,()->mapper.validateSource(visit,doctor,snapshot,"current-hash","LOCAL")).code());
    }
}

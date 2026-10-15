package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.medicalai.exception.BusinessException;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 在独立 PostgreSQL 实例上验证真实队列 SQL、事务提交、去重和过期写入保护。 */
@EnabledIfSystemProperty(named="clinical.test.pg.url", matches=".+")
class ClinicalExtractionJobPostgresTest {
    SingleConnectionDataSource datasource;
    JdbcTemplate jdbc;
    ClinicalExtractionJobMapper jobs;
    ClinicalExtractionMapper facts;
    RecordingMapper recordings;
    TransactionTemplate transaction;
    String schema;
    UUID visit=UUID.randomUUID(), doctor=UUID.randomUUID(), snapshot=UUID.randomUUID(), recording=UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        var connection = DriverManager.getConnection(System.getProperty("clinical.test.pg.url"), "clinical_test", "");
        datasource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(datasource);
        schema = "extraction_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("SET search_path TO " + schema + ",public");
        jdbc.execute("CREATE FUNCTION medicalai_local_now() RETURNS timestamp LANGUAGE sql AS 'SELECT localtimestamp'");
        jdbc.execute("CREATE FUNCTION medicalai_local_clock() RETURNS timestamp LANGUAGE sql AS 'SELECT clock_timestamp()::timestamp'");
        jdbc.execute("CREATE TABLE visit(id UUID PRIMARY KEY,doctor_id UUID,status VARCHAR,ai_route VARCHAR)");
        jdbc.execute("CREATE TABLE medical_record(visit_id UUID,status VARCHAR)");
        jdbc.execute("CREATE TABLE recording(id UUID PRIMARY KEY,visit_id UUID,status VARCHAR,asr_route VARCHAR)");
        jdbc.execute("CREATE TABLE recording_session(visit_id UUID,status VARCHAR)");
        jdbc.execute("CREATE TABLE asr_utterance(id UUID PRIMARY KEY,recording_id UUID)");
        jdbc.execute("CREATE TABLE dialogue_snapshot(id UUID PRIMARY KEY,visit_id UUID,snapshot_hash VARCHAR,authority_status VARCHAR)");
        jdbc.execute("CREATE TABLE dialogue_snapshot_source(snapshot_id UUID,utterance_id UUID)");
        jdbc.execute("CREATE TABLE visit_transcript(visit_id UUID PRIMARY KEY,snapshot_id UUID,transcript_text VARCHAR,edited BOOLEAN,updated_at TIMESTAMP)");
        jdbc.execute("""
                CREATE TABLE ai_job(id UUID PRIMARY KEY,job_type VARCHAR,visit_id UUID,source_snapshot_hash VARCHAR,
                  idempotency_key VARCHAR UNIQUE,status VARCHAR,provider_route VARCHAR,attempt_count INT DEFAULT 0,
                  last_error VARCHAR,created_at TIMESTAMP DEFAULT medicalai_local_now(),started_at TIMESTAMP,
                  finished_at TIMESTAMP,locked_at TIMESTAMP,lease_token UUID)
                """);
        jdbc.execute("CREATE TABLE clinical_extraction(id UUID PRIMARY KEY,visit_id UUID UNIQUE,current_version INT,status VARCHAR,updated_at TIMESTAMP)");
        jdbc.execute("""
                CREATE TABLE clinical_extraction_version(id UUID PRIMARY KEY,extraction_id UUID,version_no INT,
                  source_snapshot_id UUID,source_snapshot_hash VARCHAR,status VARCHAR,content_json JSONB,quality_issues_json JSONB,
                  provider_route VARCHAR,generated_by VARCHAR,created_by UUID,created_at TIMESTAMP DEFAULT medicalai_local_now(),
                  confirmed_at TIMESTAMP,confirmed_by UUID)
                """);
        jdbc.update("INSERT INTO visit VALUES (?,?,'ACTIVE','LOCAL')",visit,doctor);
        jdbc.update("INSERT INTO recording VALUES (?,?,'DONE','LOCAL')",recording,visit);
        jdbc.update("INSERT INTO dialogue_snapshot VALUES (?,?,'hash-1','ADOPTED')",snapshot,visit);
        UUID utterance = UUID.randomUUID();
        jdbc.update("INSERT INTO asr_utterance VALUES (?,?)",utterance,recording);
        jdbc.update("INSERT INTO dialogue_snapshot_source VALUES (?,?)",snapshot,utterance);
        jdbc.update("INSERT INTO visit_transcript(visit_id,snapshot_id) VALUES (?,?)",visit,snapshot);
        jobs = new ClinicalExtractionJobMapper(jdbc);
        facts = new ClinicalExtractionMapper(jdbc);
        recordings = new RecordingMapper(jdbc, jobs);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
    }

    @AfterEach void cleanup() {
        if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        if (datasource != null) datasource.destroy();
    }

    @Test void savingTranscriptRegistersOneJobAndRollbackLeavesNoTask() {
        transaction.executeWithoutResult(status -> {
            recordings.saveTranscript(visit,snapshot,"患者：头痛",false);
            assertEquals("PENDING",jobs.current(visit,"hash-1").orElseThrow().status());
            status.setRollbackOnly();
        });
        assertTrue(jobs.current(visit,"hash-1").isEmpty());
        transaction.executeWithoutResult(status -> {
            recordings.saveTranscript(visit,snapshot,"患者：头痛",false);
            recordings.saveTranscript(visit,snapshot,"患者：头痛",false);
        });
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai_job",Integer.class));
        assertEquals("LOCAL",jobs.current(visit,"hash-1").orElseThrow().route());
    }

    @Test void onlyClaimsCompleteVisitInMatchingEnvironmentAndCannotClaimTwice() {
        jobs.enqueueSnapshot(visit,snapshot);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO recording VALUES (?,?,'UPLOADED',NULL)",other,visit);
        assertTrue(jobs.claim("LOCAL",UUID.randomUUID()).isEmpty());
        jdbc.update("DELETE FROM recording WHERE id=?",other);
        assertTrue(jobs.claim("DASHSCOPE",UUID.randomUUID()).isEmpty());
        UUID token = UUID.randomUUID();
        var job = jobs.claim("LOCAL",token).orElseThrow();
        assertTrue(jobs.claim("LOCAL",UUID.randomUUID()).isEmpty());
        assertTrue(jobs.renew(job.id(),token));
        assertFalse(jobs.renew(job.id(),UUID.randomUUID()));
        jobs.finish(job.id(),token,"FAILED","test failure");
        jobs.enqueue(visit,snapshot,"LOCAL");
        assertEquals("PENDING",jobs.current(visit,"hash-1").orElseThrow().status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ai_job",Integer.class));
    }

    @Test void obsoleteSuccessAndFailureCannotOverwriteCurrentSnapshot() {
        UUID old = UUID.randomUUID();
        for (String status : new String[]{"GENERATED","FAILED"}) {
            var error = assertThrows(BusinessException.class,()->transaction.executeWithoutResult(tx ->
                    facts.insert(visit,old,"old-hash",status,"{}","[]","LOCAL","model",doctor)));
            assertEquals("SOURCE_CHANGED",error.code());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM clinical_extraction",Integer.class));
    }

    @Test void expiredWorkerCannotWriteAfterRetryAndCurrentWorkerCanSaveEvidence() {
        jobs.enqueueSnapshot(visit,snapshot);
        UUID token = UUID.randomUUID();
        var job = jobs.claim("LOCAL",token).orElseThrow();
        jdbc.update("UPDATE ai_job SET locked_at=medicalai_local_clock()-interval '100 seconds' WHERE id=?",job.id());
        jobs.claim("LOCAL",UUID.randomUUID());
        assertEquals("FAILED",jobs.current(visit,"hash-1").orElseThrow().status());
        assertThrows(BusinessException.class,()->transaction.executeWithoutResult(tx -> facts.insertForJob(visit,snapshot,
                "hash-1","GENERATED","{}","[]","LOCAL","model",doctor,job.id(),token)));
        jobs.enqueue(visit,snapshot,"LOCAL");
        UUID retryToken = UUID.randomUUID();
        jobs.claim("LOCAL",retryToken).orElseThrow();
        assertThrows(BusinessException.class,()->transaction.executeWithoutResult(tx -> facts.insertForJob(visit,snapshot,
                "hash-1","FAILED","{}","[]","LOCAL","model",doctor,job.id(),token)));
        var saved = transaction.execute(tx -> facts.insertForJob(visit,snapshot,"hash-1","GENERATED","{}","[]",
                "LOCAL","model",doctor,job.id(),retryToken));
        assertEquals("GENERATED",saved.status());
        assertEquals(snapshot,saved.sourceSnapshotId());
    }

    @Test void pendingTasksAreCancelledWhenVisitClosesOrSnapshotChanges() {
        jobs.enqueueSnapshot(visit,snapshot);
        jdbc.update("UPDATE visit SET status='COMPLETED' WHERE id=?",visit);
        assertTrue(jobs.claim("LOCAL",UUID.randomUUID()).isEmpty());
        assertEquals("CANCELLED",jobs.current(visit,"hash-1").orElseThrow().status());
    }

    @Test void aSavedRoleCorrectionQueuesNewSnapshotAndRejectsInFlightOldResult() {
        jobs.enqueueSnapshot(visit,snapshot);
        UUID oldToken = UUID.randomUUID();
        var oldJob = jobs.claim("LOCAL",oldToken).orElseThrow();
        UUID next = UUID.randomUUID();
        transaction.executeWithoutResult(tx -> {
            jdbc.update("UPDATE dialogue_snapshot SET authority_status='SUPERSEDED' WHERE id=?",snapshot);
            jdbc.update("INSERT INTO dialogue_snapshot VALUES (?,?,'hash-2','ADOPTED')",next,visit);
            jdbc.update("INSERT INTO dialogue_snapshot_source SELECT ?,utterance_id FROM dialogue_snapshot_source WHERE snapshot_id=?",next,snapshot);
            recordings.saveTranscript(visit,next,"患者：更正后的头痛描述",true);
        });
        assertEquals("PENDING",jobs.current(visit,"hash-2").orElseThrow().status());
        assertThrows(BusinessException.class,()->transaction.executeWithoutResult(tx -> facts.insertForJob(visit,snapshot,
                "hash-1","GENERATED","{}","[]","LOCAL","model",doctor,oldJob.id(),oldToken)));
        jobs.finish(oldJob.id(),oldToken,"CANCELLED",null);
        assertEquals("hash-2",jobs.claim("LOCAL",UUID.randomUUID()).orElseThrow().snapshotHash());
    }

    @Test void unknownOrMixedHistoricalEnvironmentDoesNotFallBackToPublic() {
        jdbc.update("UPDATE visit SET ai_route=NULL WHERE id=?",visit);
        jdbc.update("UPDATE recording SET asr_route='UNKNOWN' WHERE id=?",recording);
        jobs.enqueueSnapshot(visit,snapshot);
        assertTrue(jobs.current(visit,"hash-1").isEmpty());
        jdbc.update("UPDATE recording SET asr_route='LOCAL' WHERE id=?",recording);
        jobs.enqueueSnapshot(visit,snapshot);
        assertEquals("LOCAL",jobs.current(visit,"hash-1").orElseThrow().route());
    }
}

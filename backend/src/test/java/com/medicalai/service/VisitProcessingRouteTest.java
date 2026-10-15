package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.medicalai.domain.Recording;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 使用真实 JDBC 路由更新及 Spring 事务，验证成功锁定和异常回滚。 */
public class VisitProcessingRouteTest {
    private final UUID visitId = UUID.randomUUID(), recordingId = UUID.randomUUID(), token = UUID.randomUUID();
    private final RecordingMapper recordings = mock(RecordingMapper.class);
    private JdbcTemplate jdbc;
    private VisitMapper visits;
    private AsrJobStore store;

    public static LocalDateTime localNow() { return LocalDateTime.now(); }

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:route_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE ALIAS medicalai_local_now FOR 'com.medicalai.service.VisitProcessingRouteTest.localNow'");
        jdbc.execute("""
                CREATE TABLE visit(id UUID PRIMARY KEY, visit_no VARCHAR, patient_id UUID, doctor_id UUID,
                  status VARCHAR, department_name VARCHAR, patient_name_snapshot VARCHAR, patient_gender_snapshot VARCHAR,
                  patient_age_snapshot INT, doctor_name_snapshot VARCHAR, chief_complaint VARCHAR, version INT,
                  started_at TIMESTAMP, completed_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP,
                  processing_route VARCHAR CHECK(processing_route IN ('DASHSCOPE','LOCAL')), recovery_route VARCHAR)
                """);
        jdbc.execute("CREATE TABLE recording(id UUID PRIMARY KEY, visit_id UUID, status VARCHAR, asr_route VARCHAR)");
        jdbc.execute("CREATE TABLE asr_utterance(recording_id UUID, result_type VARCHAR)");
        jdbc.update("INSERT INTO visit(id,visit_no,status,version,created_at) VALUES (?,'V1','ACTIVE',1,CURRENT_TIMESTAMP)", visitId);
        visits = new VisitMapper(jdbc);
        var factory = new ProxyFactory(new AsrJobStore(recordings, mock(MedicalRecordMapper.class), visits));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        store = (AsrJobStore) factory.getProxy();
        when(recordings.list(visitId)).thenReturn(List.of(new Recording(UUID.randomUUID(),visitId,"R2","UPLOAD",
                "key","next.wav","audio/wav",1L,100L,"UPLOADED",null,Instant.now())));
    }

    private RecordingMapper.AsrJob job(String provider) {
        var job = new RecordingMapper.AsrJob(UUID.randomUUID(),visitId,recordingId,null,"RUNNING",1,null,Instant.now(),provider);
        when(recordings.lockAsrLease(job.id(),token)).thenReturn(true);
        when(recordings.asrJob(job.id(),visitId)).thenReturn(Optional.of(job));
        return job;
    }
    private List<RecordingMapper.Turn> turns(String provider) {
        return List.of(new RecordingMapper.Turn("OTHER","原始对话",0,100,1,"FALLBACK",null,provider));
    }

    @ParameterizedTest @ValueSource(strings={"LOCAL","DASHSCOPE_ROLE_V2"})
    void firstSuccessfulRecordingLocksRouteBeforeCanonicalWrite(String provider) {
        assertNull(visits.find(visitId,false).orElseThrow().processingRoute());
        String route = LlmRoute.fromAsrRoute(provider).name();
        when(recordings.insertUtterances(eq(visitId),eq(recordingId),any(),anyList())).thenAnswer(invocation -> {
            assertEquals(route,visits.find(visitId,false).orElseThrow().processingRoute());
            return List.of();
        });
        store.complete(job(provider),token,recordingId,turns(route));
        assertEquals(route,visits.find(visitId,false).orElseThrow().processingRoute());
        verify(recordings).updateStatus(recordingId,"DONE",null);
        verify(recordings).prepareNextAsrRecording(any());
    }

    @Test void persistenceFailureRollsBackFirstRouteLock() {
        when(recordings.insertUtterances(any(),any(),any(),anyList())).thenThrow(new IllegalStateException("write failed"));
        assertThrows(IllegalStateException.class,() -> store.complete(job("LOCAL"),token,recordingId,turns("LOCAL")));
        assertNull(visits.find(visitId,false).orElseThrow().processingRoute());
    }

    @Test void failedAsrDoesNotLockRoute() {
        var job = job("LOCAL");
        store.fail(job,token,"ASR failed");
        assertNull(visits.find(visitId,false).orElseThrow().processingRoute());
        verify(recordings).markAsrFailed(job.id(),"ASR failed");
    }

    @Test void recoveryFailureKeepsTargetAndSuccessfulWriteLocksAndClearsItAtomically() {
        visits.setRecoveryRoute(visitId,"LOCAL");
        store.fail(job("LOCAL"),token,"offline");
        assertNull(visits.find(visitId,false).orElseThrow().processingRoute());
        assertEquals("LOCAL",visits.find(visitId,false).orElseThrow().recoveryRoute());
        store.complete(job("LOCAL"),token,recordingId,turns("LOCAL"));
        assertEquals("LOCAL",visits.find(visitId,false).orElseThrow().processingRoute());
        assertNull(visits.find(visitId,false).orElseThrow().recoveryRoute());
    }

    @Test void rolledBackRecoverySuccessRestoresRecoveryTarget() {
        visits.setRecoveryRoute(visitId,"LOCAL");
        when(recordings.insertUtterances(any(),any(),any(),anyList())).thenThrow(new IllegalStateException("write failed"));
        assertThrows(IllegalStateException.class,()->store.complete(job("LOCAL"),token,recordingId,turns("LOCAL")));
        assertNull(visits.find(visitId,false).orElseThrow().processingRoute());
        assertEquals("LOCAL",visits.find(visitId,false).orElseThrow().recoveryRoute());
    }

    @Test void wrongRouteCompletionCannotWriteCanonicalTurns() {
        assertTrue(visits.lockProcessingRoute(visitId,"LOCAL"));
        assertFalse(visits.lockProcessingRoute(visitId,"DASHSCOPE"));
        assertEquals("ASR_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> store.complete(job("DASHSCOPE_ROLE_V2"),token,recordingId,turns("DASHSCOPE"))).code());
        assertEquals("LOCAL",visits.find(visitId,false).orElseThrow().processingRoute());
        verify(recordings,never()).insertUtterances(any(),any(),any(),anyList());
    }

    @Test void wrongRoleProviderCannotLockOrWrite() {
        assertEquals("LLM_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> store.complete(job("LOCAL"),token,recordingId,turns("DASHSCOPE"))).code());
        assertNull(visits.find(visitId,false).orElseThrow().processingRoute());
    }

    @Test void manualOrUnknownRolesCannotBecomeAsrResults() {
        var job = job("LOCAL");
        for (String source : List.of("MANUAL","AUTO","UNKNOWN")) {
            assertThrows(IllegalStateException.class,() -> store.complete(job,token,recordingId,
                    List.of(new RecordingMapper.Turn("OTHER","原文",0,100,1,source,null,"LOCAL"))));
        }
        assertNull(visits.find(visitId,false).orElseThrow().processingRoute());
    }

    @Test void historicalInferenceAllowsOnlySafeSingleSource() {
        assertNull(visits.historicalProcessingRoute(visitId));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recording VALUES (?,?,'DONE',NULL)",id,visitId);
        assertEquals("DASHSCOPE",visits.historicalProcessingRoute(visitId));
        jdbc.update("UPDATE recording SET asr_route='DASHSCOPE_ROLE_V2'");
        assertEquals("DASHSCOPE",visits.historicalProcessingRoute(visitId));
        jdbc.update("UPDATE recording SET asr_route='LOCAL'");
        assertEquals("LOCAL",visits.historicalProcessingRoute(visitId));
        jdbc.update("INSERT INTO recording VALUES (?,?,'DONE','DASHSCOPE')",UUID.randomUUID(),visitId);
        assertEquals("UNKNOWN",visits.historicalProcessingRoute(visitId));
    }
}

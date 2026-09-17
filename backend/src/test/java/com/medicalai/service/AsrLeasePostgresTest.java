package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.medicalai.mapper.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅使用独立且可销毁的本机 PostgreSQL，绝不使用应用凭据。 */
@EnabledIfEnvironmentVariable(named="RUN_ASR_POSTGRES_TEST",matches="true")
class AsrLeasePostgresTest {
    String schema;
    JdbcTemplate jdbc, admin;
    RecordingMapper mapper;
    MedicalRecordMapper audit;
    AsrJobStore store;
    DataSourceTransactionManager transactions;
    UUID visit,recording,job;
    @BeforeEach void setup() {
        String url="jdbc:postgresql://127.0.0.1:55439/postgres";
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres","asr-test-only"));
        schema="asr_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres","asr-test-only");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);mapper=new RecordingMapper(jdbc);transactions=new DataSourceTransactionManager(ds);
        audit=mock(MedicalRecordMapper.class);
        var factory=new ProxyFactory(new AsrJobStore(mapper,audit,new VisitMapper(jdbc)));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        store=(AsrJobStore)factory.getProxy();
        visit=createVisit();recording=createRecording(visit);job=mapper.createAsrJob(visit,"LOCAL");
    }
    @AfterEach void cleanup(){if(admin!=null && schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    UUID createVisit(){
        UUID doctor=UUID.randomUUID(),patient=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("INSERT INTO doctor(id,external_system,external_user_id,display_name) VALUES (?,'TEST',?,'test')",doctor,doctor.toString());
        jdbc.update("INSERT INTO patient(id,source_system,source_patient_id,name) VALUES (?,'TEST',?,'test')",patient,patient.toString());
        jdbc.update("INSERT INTO visit(id,visit_no,patient_id,doctor_id,status) VALUES (?,?,?,?,'ACTIVE')",id,id.toString(),patient,doctor);
        return id;
    }
    UUID createRecording(UUID visit){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO recording(id,visit_id,recording_no,source_type,object_key,file_name,mime_type) VALUES (?,?,?,'UPLOAD','key','test.wav','audio/wav')",id,visit,id.toString());
        return id;
    }
    List<RecordingMapper.Turn> turns(){return List.of(new RecordingMapper.Turn("OTHER","离线测试",20,300,7));}
    int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}

    @Test void routesClaimIndependentlyAndHeartbeatFencesStaleOwner(){
        UUID publicVisit=createVisit();createRecording(publicVisit);UUID publicJob=mapper.createAsrJob(publicVisit,"DASHSCOPE");
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var local=store.claim("LOCAL",first).orElseThrow();
        assertEquals(job,local.id());
        assertEquals(publicJob,store.claim("DASHSCOPE",UUID.randomUUID()).orElseThrow().id());
        assertTrue(store.claim("LOCAL",second).isEmpty());
        store.begin(local,first);
        jdbc.update("UPDATE ai_job SET locked_at=medicalai_local_clock()-interval '1 minute' WHERE id=?",job);
        assertTrue(store.renew(job,first));
        assertFalse(store.renew(job,second));
        jdbc.update("UPDATE ai_job SET locked_at=medicalai_local_clock()-interval '3 minutes' WHERE id=?",job);
        assertFalse(store.renew(job,first),"expired lease cannot be resurrected");
        var reclaimed=store.claim("LOCAL",second).orElseThrow();
        assertEquals("RUNNING",reclaimed.status());
        assertThrows(AsrJobStore.LeaseLostException.class,()->store.complete(local,first,recording,turns()));
        assertThrows(AsrJobStore.LeaseLostException.class,()->store.fail(local,first,"stale"));
        assertThrows(AsrJobStore.LeaseLostException.class,()->store.submitted(local,first,recording,"stale-task"));
        assertEquals(0,count("asr_utterance"));
        store.fail(reclaimed,second,"本地 ASR 任务已中断");
        assertEquals("FAILED",mapper.asrJob(job,visit).orElseThrow().status());
    }

    @Test void rollbackAndRetryAreAtomicAndSpeakerSurvivesSnapshot(){
        UUID token=UUID.randomUUID();var current=store.claim("LOCAL",token).orElseThrow();
        store.begin(current,token);
        doThrow(new IllegalStateException("audit failure")).when(audit).audit(any(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->store.complete(current,token,recording,turns()));
        assertEquals(0,count("asr_utterance"));assertEquals(0,count("recording_session"));
        assertEquals(0,count("dialogue_snapshot"));assertEquals(0,count("visit_transcript"));
        assertEquals("RUNNING",mapper.asrJob(job,visit).orElseThrow().status());
        store.fail(current,token,"save failed");
        mapper.requeueRetryableRecordings(visit);
        UUID retryId=mapper.createAsrJob(visit,"LOCAL"),retryToken=UUID.randomUUID();
        var retry=store.claim("LOCAL",retryToken).orElseThrow();
        assertEquals(retryId,retry.id());store.begin(retry,retryToken);
        doNothing().when(audit).audit(any(),any(),any(),any());
        store.complete(retry,retryToken,recording,turns());
        assertEquals(1,count("asr_utterance"));assertEquals(1,count("dialogue_snapshot"));
        assertEquals(7,mapper.listByRecording(recording).getFirst().speakerId());
        assertEquals("说话人 7：离线测试",mapper.transcript(visit).orElseThrow().transcript());
        assertEquals("7",jdbc.queryForObject("SELECT turns_json->0->>'speaker_id' FROM dialogue_snapshot",String.class));
        assertThrows(AsrJobStore.LeaseLostException.class,()->store.complete(retry,retryToken,recording,turns()));
        assertEquals(1,count("asr_utterance"));
    }

    @Test void retryOfEarlierRecordingKeepsCompletedLaterRecordingAndValidSessionScope() {
        UUID later=createRecording(visit);
        jdbc.update("UPDATE recording SET created_at=medicalai_local_clock()+interval '1 second',status='DONE' WHERE id=?",later);
        UUID laterSession=mapper.createSession(visit,later);
        mapper.insertUtterances(visit,later,laterSession,List.of(new RecordingMapper.Turn("DOCTOR","已完成",0,100,0)));
        UUID token=UUID.randomUUID();var current=store.claim("LOCAL",token).orElseThrow();
        assertEquals(recording,store.begin(current,token).id());
        store.complete(current,token,recording,turns());
        assertEquals(2,count("asr_utterance"));
        assertEquals(1,count("dialogue_snapshot"));
        assertEquals(recording,jdbc.queryForObject("SELECT recording_id FROM dialogue_snapshot",UUID.class));
        assertEquals("SUCCEEDED",mapper.asrJob(job,visit).orElseThrow().status());
    }

    @Test void savedPublicTaskIdSurvivesLeaseRelease(){
        jdbc.update("UPDATE ai_job SET provider_route='DASHSCOPE' WHERE id=?",job);
        UUID token=UUID.randomUUID();var current=store.claim("DASHSCOPE",token).orElseThrow();
        store.begin(current,token);store.submitted(current,token,recording,"provider-123");
        var next=store.claim("DASHSCOPE",UUID.randomUUID()).orElseThrow();
        assertEquals("provider-123",next.providerTaskId());assertEquals("RUNNING",next.status());
    }

    @Test void concurrentClaimsCannotOwnSameTask() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Boolean> claim=()->{start.await();return store.claim("LOCAL",UUID.randomUUID()).isPresent();};
            var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();
            assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }

    @Test void visitLockSerializesTaskCreation() throws Exception {
        // 实际工作流中的 owned(..., true) 必须加锁；此前曾忽略该标志位。
        UUID doctor=jdbc.queryForObject("SELECT doctor_id FROM visit WHERE id=?",UUID.class,visit);
        var pool=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx->{
                new VisitMapper(jdbc).find(visit,doctor,true).orElseThrow();
                var waiting=pool.submit(()->new TransactionTemplate(transactions).execute(t->{
                    started.countDown();return new VisitMapper(jdbc).find(visit,doctor,true);
                }));
                try {assertTrue(started.await(2,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->waiting.get(150,TimeUnit.MILLISECONDS));}
                catch(InterruptedException e){throw new RuntimeException(e);}
            });
        } finally {pool.shutdown();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));}
    }
}

package com.medicalai.service;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.medicalai.domain.Recording;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.config.AsrSchedulingConfig;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;

class AsrJobWorkerTest {
    AsrJobStore store=mock(AsrJobStore.class);
    AudioStorageService storage=mock(AudioStorageService.class);
    DashScopeAsrClient cloud=mock(DashScopeAsrClient.class);
    DashScopeRoleClient roles=mock(DashScopeRoleClient.class);
    AiServiceClient internal=mock(AiServiceClient.class);
    LocalLlmRoleClient localRoles=mock(LocalLlmRoleClient.class);
    LocalAsrClient local=mock(LocalAsrClient.class);
    ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();
    // 全局默认故意为公网，LOCAL 任务必须仍然使用本地角色客户端。
    LlmRoleRouter roleRouter=new LlmRoleRouter(roles,localRoles,"DASHSCOPE");
    AsrJobWorker worker=new AsrJobWorker(store,storage,cloud,local,roleRouter,heartbeat,600000);
    UUID id=UUID.randomUUID(),visit=UUID.randomUUID(),recordingId=UUID.randomUUID();
    Recording recording=new Recording(recordingId,visit,"R1","UPLOAD","key","a.wav","audio/wav",1L,100L,"UPLOADED",null,Instant.now());
    @BeforeEach void configureRoleLlms(){
        lenient().when(roles.isConfigured()).thenReturn(true);
        lenient().when(localRoles.isConfigured()).thenReturn(true);
    }
    @AfterEach void stop(){heartbeat.shutdownNow();}
    RecordingMapper.AsrJob pending(String route){
        var job=new RecordingMapper.AsrJob(id,visit,null,null,"PENDING",0,null,null,route);
        when(store.claim(eq(route),any())).thenReturn(Optional.of(job));
        when(store.begin(eq(job),any())).thenReturn(recording);
        return job;
    }
    @Test void localCompletionAssignsRolesByLocalLlm(){
        var job=pending("LOCAL");
        var audio=new ByteArrayResource(new byte[]{1});
        when(storage.load("key")).thenReturn(audio);
        when(local.transcribeDetailed(audio,"a.wav","audio/wav")).thenReturn(new AsrResult(
                List.of(new AsrSegment("头疼",0,100,2),new AsrSegment("嗯",110,200,null)), null));
        when(localRoles.assignRoles(any())).thenReturn(Map.of(
                0,new DashScopeRoleClient.RoleAssignment("PATIENT",88,"LLM"),
                1,new DashScopeRoleClient.RoleAssignment("OTHER",null,"FALLBACK")));
        worker.processLocal();
        verifyNoInteractions(cloud,roles);
        verifyNoInteractions(internal);
        verify(storage,never()).presignedUrl(any());
        org.mockito.InOrder sequence = inOrder(store, localRoles);
        sequence.verify(store).savePreview(eq(job), any(), eq(recordingId), eq(List.of(
                new AsrSegment("头疼",0,100,2),new AsrSegment("嗯",110,200,null))));
        sequence.verify(localRoles).assignRoles(any());
        verify(store).complete(eq(job),any(),eq(recordingId),eq(List.of(
                new RecordingMapper.Turn("PATIENT","头疼",0,100,2,"LLM",88,"LOCAL"),
                new RecordingMapper.Turn("OTHER","嗯",110,200,null,"FALLBACK",null,"LOCAL"))), isNull());
    }
    @Test void localJobCompletesWithFallbackWhenLocalRoleLlmIsMissing(){
        var job=pending("LOCAL");
        var audio=new ByteArrayResource(new byte[]{1});
        var segment=new AsrSegment("可预览的原始识别文字",0,100,2);
        when(storage.load("key")).thenReturn(audio);
        when(local.transcribeDetailed(audio,"a.wav","audio/wav"))
                .thenReturn(new AsrResult(List.of(segment), null));
        when(localRoles.isConfigured()).thenReturn(false);
        when(localRoles.assignRoles(any())).thenReturn(Map.of(0, DashScopeRoleClient.RoleAssignment.fallback()));

        worker.processLocal();

        org.mockito.InOrder sequence=inOrder(local,store,localRoles);
        sequence.verify(local).transcribeDetailed(audio,"a.wav","audio/wav");
        sequence.verify(store).savePreview(eq(job),any(),eq(recordingId),eq(List.of(segment)));
        sequence.verify(localRoles).assignRoles(any());
        sequence.verify(store).complete(eq(job),any(),eq(recordingId),eq(List.of(
                new RecordingMapper.Turn("OTHER",segment.text(),0,100,2,"FALLBACK",null,"LOCAL"))),isNull());
        verifyNoInteractions(cloud,internal,roles);
        verify(store,never()).fail(any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"DASHSCOPE","DASHSCOPE_ROLE_V2"})
    void publicCompletionNeverCallsLocalService(String route){
        var job=new RecordingMapper.AsrJob(id,visit,recordingId,"task","RUNNING",1,null,Instant.now(),route);
        when(store.claim(eq(AsrJobWorker.PUBLIC_ROLE_ROUTE),any())).thenReturn(Optional.of(job));
        var task=new DashScopeAsrClient.Task("task","SUCCEEDED",null);
        when(cloud.query("task")).thenReturn(task);
        when(cloud.resultDetailed(task)).thenReturn(new AsrResult(List.of(new AsrSegment("哪里疼",0,100,null)), null));
        when(roles.assignRoles(any())).thenReturn(Map.of(0,new DashScopeRoleClient.RoleAssignment("DOCTOR",91,"LLM")));
        worker.processPublic();
        verifyNoInteractions(local,storage,internal,localRoles);
        org.mockito.InOrder sequence = inOrder(store, roles);
        sequence.verify(store).savePreview(eq(job), any(), eq(recordingId), eq(List.of(new AsrSegment("哪里疼",0,100,null))));
        sequence.verify(roles).assignRoles(any());
        verify(store).complete(eq(job),any(),eq(recordingId),eq(List.of(
                new RecordingMapper.Turn("DOCTOR","哪里疼",0,100,null,"LLM",91,"DASHSCOPE"))), isNull());
    }
    @Test void roleAssignmentFailureLeavesPreviouslySavedPreviewAndDoesNotCompleteTranscript() {
        var job = pending("LOCAL");
        var audio = new ByteArrayResource(new byte[]{1});
        var segment = new AsrSegment("原始识别文字", 0, 100, 1);
        when(storage.load("key")).thenReturn(audio);
        when(local.transcribeDetailed(audio, "a.wav", "audio/wav"))
                .thenReturn(new AsrResult(List.of(segment), null));
        when(localRoles.assignRoles(any())).thenThrow(new IllegalStateException("role model unavailable"));

        worker.processLocal();

        org.mockito.InOrder sequence = inOrder(store, localRoles);
        sequence.verify(store).savePreview(eq(job), any(), eq(recordingId), eq(List.of(segment)));
        sequence.verify(localRoles).assignRoles(any());
        sequence.verify(store).fail(eq(job), any(), contains("role model unavailable"));
        verify(store, never()).complete(any(), any(), any(), any());
        verifyNoInteractions(roles,cloud);
    }
    @Test void publicRoleFailureCompletesWithPublicFallbackAndNeverCallsLocal() {
        var job=new RecordingMapper.AsrJob(id,visit,recordingId,"task","RUNNING",1,null,Instant.now(),AsrJobWorker.PUBLIC_ROLE_ROUTE);
        when(store.claim(eq(AsrJobWorker.PUBLIC_ROLE_ROUTE),any())).thenReturn(Optional.of(job));
        var task=new DashScopeAsrClient.Task("task","SUCCEEDED",null);
        when(cloud.query("task")).thenReturn(task);
        when(cloud.resultDetailed(task)).thenReturn(new AsrResult(List.of(new AsrSegment("原文",0,100,null)),null));
        when(roles.assignRoles(anyList())).thenReturn(Map.of(0,DashScopeRoleClient.RoleAssignment.fallback()));
        worker.processPublic();
        verify(store).complete(eq(job),any(),eq(recordingId),eq(List.of(
                new RecordingMapper.Turn("OTHER","原文",0,100,null,"FALLBACK",null,"DASHSCOPE"))),isNull());
        verifyNoInteractions(local,localRoles,internal);
    }
    @Test void interruptedLocalJobFailsWithoutReplayingModel(){
        var job=new RecordingMapper.AsrJob(id,visit,recordingId,null,"RUNNING",1,null,Instant.now(),"LOCAL");
        when(store.claim(eq("LOCAL"),any())).thenReturn(Optional.of(job));
        worker.processLocal();
        verifyNoInteractions(local,cloud,roles);
        verify(store).fail(eq(job),any(),contains("本地 ASR 任务已中断"));
    }
    @Test void lostLeaseCannotWriteFailure(){
        var job=pending("LOCAL");
        when(store.begin(eq(job),any())).thenThrow(new AsrJobStore.LeaseLostException());
        worker.processLocal();
        verify(store,never()).fail(any(),any(),any());
        verifyNoInteractions(local,cloud,roles);
    }
    @Test void heartbeatLossDiscardsInFlightResult() {
        var job=pending("LOCAL");
        var timer=mock(ScheduledExecutorService.class);
        var future=mock(ScheduledFuture.class);
        var renewal=org.mockito.ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(timer).scheduleWithFixedDelay(renewal.capture(),eq(20L),eq(20L),eq(TimeUnit.SECONDS));
        when(store.renew(eq(job.id()),any())).thenReturn(false);
        when(local.transcribeDetailed(any(),any(),any())).thenAnswer(inv->{renewal.getValue().run();return new AsrResult(List.of(new AsrSegment("过期结果",0,1,0)), null);});
        new AsrJobWorker(store,storage,cloud,local,roleRouter,timer,600000).processLocal();
        verify(store).renew(eq(job.id()),any());
        verify(store,never()).complete(any(),any(),any(),any());
        verify(store,never()).fail(any(),any(),any());
        verify(future).cancel(false);
    }
    @Test void blockedLocalInferenceDoesNotBlockPublicScheduler() throws Exception {
        var localJob=pending("LOCAL");
        var publicJob=new RecordingMapper.AsrJob(UUID.randomUUID(),UUID.randomUUID(),recordingId,"task","RUNNING",1,null,Instant.now(),AsrJobWorker.PUBLIC_ROLE_ROUTE);
        when(store.claim(eq(AsrJobWorker.PUBLIC_ROLE_ROUTE),any())).thenReturn(Optional.of(publicJob));
        var task=new DashScopeAsrClient.Task("task","SUCCEEDED",null);
        when(cloud.query("task")).thenReturn(task);
        when(cloud.resultDetailed(task)).thenReturn(new AsrResult(List.of(new AsrSegment("医生您好",0,100,1)), null));
        when(roles.assignRoles(any())).thenReturn(Map.of(1,new DashScopeRoleClient.RoleAssignment("PATIENT",90,"LLM")));
        CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1),completed=new CountDownLatch(1);
        when(local.transcribeDetailed(any(),any(),any())).thenAnswer(inv->{entered.countDown();assertTrue(unblock.await(5,TimeUnit.SECONDS));return new AsrResult(List.of(new AsrSegment("本地",0,100,0)), null);});
        doAnswer(inv->{completed.countDown();return null;}).when(store).complete(eq(publicJob),any(),any(),any(),any());
        var config=new AsrSchedulingConfig();var localScheduler=config.localAsrScheduler();var publicScheduler=config.publicAsrScheduler();
        localScheduler.initialize();publicScheduler.initialize();
        try {
            localScheduler.execute(worker::processLocal);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            publicScheduler.execute(worker::processPublic);
            assertTrue(completed.await(3,TimeUnit.SECONDS),"public completion must not wait for local inference");
        } finally {unblock.countDown();localScheduler.shutdown();publicScheduler.shutdown();}
    }
}

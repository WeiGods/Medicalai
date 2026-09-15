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
import org.springframework.core.io.ByteArrayResource;

class AsrJobWorkerTest {
    AsrJobStore store=mock(AsrJobStore.class);
    AudioStorageService storage=mock(AudioStorageService.class);
    DashScopeAsrClient cloud=mock(DashScopeAsrClient.class);
    DashScopeRoleClient roles=mock(DashScopeRoleClient.class);
    LocalAsrClient local=mock(LocalAsrClient.class);
    ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();
    AsrJobWorker worker=new AsrJobWorker(store,storage,cloud,local,roles,heartbeat,600000);
    UUID id=UUID.randomUUID(),visit=UUID.randomUUID(),recordingId=UUID.randomUUID();
    Recording recording=new Recording(recordingId,visit,"R1","UPLOAD","key","a.wav","audio/wav",1L,100L,"UPLOADED",null,Instant.now());
    @AfterEach void stop(){heartbeat.shutdownNow();}
    RecordingMapper.AsrJob pending(String route){
        var job=new RecordingMapper.AsrJob(id,visit,null,null,"PENDING",0,null,null,route);
        when(store.claim(eq(route),any())).thenReturn(Optional.of(job));
        when(store.begin(eq(job),any())).thenReturn(recording);
        return job;
    }
    @Test void localRequiresNoPublicOrLlmCall(){
        var job=pending("LOCAL");
        var audio=new ByteArrayResource(new byte[]{1});
        when(storage.load("key")).thenReturn(audio);
        when(local.transcribe(audio,"a.wav","audio/wav")).thenReturn(List.of(new AsrSegment("头疼",0,100,2),new AsrSegment("嗯",110,200,null)));
        worker.processLocal();
        verifyNoInteractions(cloud,roles);
        verify(storage,never()).presignedUrl(any());
        verify(store).complete(eq(job),any(),eq(recordingId),eq(List.of(
                new RecordingMapper.Turn("OTHER","头疼",0,100,2),new RecordingMapper.Turn("OTHER","嗯",110,200,null))));
    }
    @Test void publicCompletionNeverCallsLocalService(){
        var job=new RecordingMapper.AsrJob(id,visit,recordingId,"task","RUNNING",1,null,Instant.now(),"DASHSCOPE");
        when(store.claim(eq("DASHSCOPE"),any())).thenReturn(Optional.of(job));
        var task=new DashScopeAsrClient.Task("task","SUCCEEDED",null);
        when(cloud.query("task")).thenReturn(task);
        when(cloud.result(task)).thenReturn(List.of(new AsrSegment("哪里疼",0,100,null)));
        when(roles.assignRoles(List.of(Map.<String,Object>of("speaker_id",-1,"text","哪里疼")))).thenReturn(Map.of(-1,"DOCTOR"));
        worker.processPublic();
        verifyNoInteractions(local,storage);
        verify(store).complete(eq(job),any(),eq(recordingId),eq(List.of(new RecordingMapper.Turn("DOCTOR","哪里疼",0,100,null))));
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
        when(local.transcribe(any(),any(),any())).thenAnswer(inv->{renewal.getValue().run();return List.of(new AsrSegment("过期结果",0,1,0));});
        new AsrJobWorker(store,storage,cloud,local,roles,timer,600000).processLocal();
        verify(store).renew(eq(job.id()),any());
        verify(store,never()).complete(any(),any(),any(),any());
        verify(store,never()).fail(any(),any(),any());
        verify(future).cancel(false);
    }
    @Test void blockedLocalInferenceDoesNotBlockPublicScheduler() throws Exception {
        var localJob=pending("LOCAL");
        var publicJob=new RecordingMapper.AsrJob(UUID.randomUUID(),UUID.randomUUID(),recordingId,"task","RUNNING",1,null,Instant.now(),"DASHSCOPE");
        when(store.claim(eq("DASHSCOPE"),any())).thenReturn(Optional.of(publicJob));
        var task=new DashScopeAsrClient.Task("task","SUCCEEDED",null);
        when(cloud.query("task")).thenReturn(task);
        when(cloud.result(task)).thenReturn(List.of(new AsrSegment("医生您好",0,100,1)));
        when(roles.assignRoles(any())).thenReturn(Map.of(1,"PATIENT"));
        CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1),completed=new CountDownLatch(1);
        when(local.transcribe(any(),any(),any())).thenAnswer(inv->{entered.countDown();assertTrue(unblock.await(5,TimeUnit.SECONDS));return List.of(new AsrSegment("本地",0,100,0));});
        doAnswer(inv->{completed.countDown();return null;}).when(store).complete(eq(publicJob),any(),any(),any());
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

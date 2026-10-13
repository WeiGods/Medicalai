package com.medicalai.service;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.controller.RecordingController;
import com.medicalai.domain.*;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class AsrRoutingTest {
    VisitMapper visits = mock(VisitMapper.class);
    RecordingMapper recordings = mock(RecordingMapper.class);
    AudioStorageService storage = mock(AudioStorageService.class);
    ClinicalWorkflowService workflow = new ClinicalWorkflowService(visits,mock(PatientMapper.class),mock(DoctorMapper.class),
        recordings,mock(MedicalRecordMapper.class),mock(AiServiceClient.class),storage,new ObjectMapper(),Clock.systemUTC(),
        mock(DashScopeRoleClient.class),mock(TranscriptRoleReclassificationStore.class));
    UUID visitId=UUID.randomUUID(), doctorId=UUID.randomUUID(), jobId=UUID.randomUUID();
    AsrRoutingTest() {
        org.springframework.test.util.ReflectionTestUtils.setField(workflow,"dashscopeApiKey","test-key");
        var visit = new Visit(visitId,"V1",UUID.randomUUID(),doctorId,"ACTIVE",null,null,null,null,null,null,1,Instant.now());
        when(visits.find(eq(visitId),eq(doctorId),anyBoolean())).thenReturn(Optional.of(visit));
    }
    RecordingMapper.AsrJob job(String status,String provider) {
        return new RecordingMapper.AsrJob(jobId,visitId,null,null,status,0,null,null,provider);
    }
    @Test void existingJobRejectsCrossRouteRequest() {
        when(recordings.activeAsrJob(visitId)).thenReturn(Optional.of(job("RUNNING",AsrJobWorker.PUBLIC_ROLE_ROUTE)));
        assertEquals("ASR_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> workflow.transcribe(visitId,doctorId,"LOCAL")).code());
        verify(recordings,never()).createAsrJob(any(),any());
        verify(recordings,never()).requeueRetryableRecordings(any());
    }
    @Test void existingJobKeepsOriginalRouteForMatchingRequest() {
        when(recordings.activeAsrJob(visitId)).thenReturn(Optional.of(job("RUNNING","DASHSCOPE")));
        when(recordings.asrJob(jobId,visitId)).thenReturn(Optional.of(job("RUNNING","DASHSCOPE")));
        var response = workflow.transcribe(visitId,doctorId,"DASHSCOPE");
        assertEquals(jobId,response.jobId());
        assertEquals("DASHSCOPE",response.providerRoute());
        verify(recordings,never()).createAsrJob(any(),any());
        verify(recordings,never()).requeueRetryableRecordings(any());
    }
    @ParameterizedTest @ValueSource(strings={"DASHSCOPE","LOCAL"})
    void lockedVisitRejectsOtherRouteAndAllowsImplicitRetry(String route) {
        var visit = new Visit(visitId,"V1",UUID.randomUUID(),doctorId,"ACTIVE",null,null,null,null,null,null,1,
                null,null,Instant.now(),route);
        when(visits.find(eq(visitId),eq(doctorId),anyBoolean())).thenReturn(Optional.of(visit));
        assertEquals("ASR_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> workflow.transcribe(visitId,doctorId,"LOCAL".equals(route) ? "DASHSCOPE" : "LOCAL")).code());
        when(recordings.latestAsrJob(visitId)).thenReturn(Optional.of(job("FAILED",route)));
        when(recordings.list(visitId)).thenReturn(List.of(new Recording(UUID.randomUUID(),visitId,"R1","UPLOAD","key","a.wav","audio/wav",1L,1L,"UPLOADED",null,Instant.now())));
        when(recordings.createAsrJob(visitId,"LOCAL".equals(route) ? route : AsrJobWorker.PUBLIC_ROLE_ROUTE)).thenReturn(jobId);
        when(recordings.asrJob(jobId,visitId)).thenReturn(Optional.of(job("PENDING",route)));
        assertEquals(route,workflow.transcribe(visitId,doctorId,null).providerRoute());
    }
    @Test void implicitProviderFollowsActiveLocalJob() {
        when(recordings.activeAsrJob(visitId)).thenReturn(Optional.of(job("RUNNING","LOCAL")));
        when(recordings.asrJob(jobId,visitId)).thenReturn(Optional.of(job("RUNNING","LOCAL")));
        assertEquals("LOCAL",workflow.transcribe(visitId,doctorId,null).providerRoute());
    }
    @Test void historicalPublicVisitRejectsLocalRoute() {
        when(visits.historicalProcessingRoute(visitId)).thenReturn("DASHSCOPE");
        assertEquals("ASR_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> workflow.transcribe(visitId,doctorId,"LOCAL")).code());
    }
    @Test void newerFailedJobCannotHideOlderActiveRoute() {
        when(recordings.latestAsrJob(visitId)).thenReturn(Optional.of(job("FAILED","LOCAL")));
        when(recordings.activeAsrJob(visitId)).thenReturn(Optional.of(job("RUNNING","DASHSCOPE")));
        assertEquals("ASR_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> workflow.transcribe(visitId,doctorId,"LOCAL")).code());
        verify(recordings,never()).createAsrJob(any(),any());
    }
    @Test void unsafeHistoricalVisitRejectsProcessing() {
        when(visits.historicalProcessingRoute(visitId)).thenReturn("UNKNOWN");
        assertEquals("ASR_ROUTE_UNKNOWN",assertThrows(BusinessException.class,
                () -> workflow.transcribe(visitId,doctorId,"LOCAL")).code());
        verify(recordings,never()).createAsrJob(any(),any());
    }

    @Test void recoveryTargetPinsImplicitAndExplicitRetriesBeforeFirstSuccess() {
        var visit = new Visit(visitId,"V1",UUID.randomUUID(),doctorId,"ACTIVE",null,null,null,null,null,null,1,
                null,null,Instant.now(),null,"LOCAL");
        when(visits.find(eq(visitId),eq(doctorId),anyBoolean())).thenReturn(Optional.of(visit));
        assertEquals("ASR_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> workflow.transcribe(visitId,doctorId,"DASHSCOPE")).code());
        // requeueRetryableRecordings changes FAILED to UPLOADED before the pending list is read.
        when(recordings.list(visitId)).thenReturn(List.of(new Recording(UUID.randomUUID(),visitId,"R1","UPLOAD","key","a.wav","audio/wav",1L,1L,"UPLOADED",null,Instant.now())));
        when(recordings.createAsrJob(visitId,"LOCAL")).thenReturn(jobId);
        when(recordings.asrJob(jobId,visitId)).thenReturn(Optional.of(job("PENDING","LOCAL")));
        assertEquals("LOCAL",workflow.transcribe(visitId,doctorId,null).providerRoute());
        verify(recordings).requeueRetryableRecordings(visitId);
    }
    @ParameterizedTest @ValueSource(strings={"DASHSCOPE","LOCAL"})
    void lockedVisitRejectsRetranscriptionWithOtherRoute(String route) {
        var visit = new Visit(visitId,"V1",UUID.randomUUID(),doctorId,"ACTIVE",null,null,null,null,null,null,1,
                null,null,Instant.now(),route);
        when(visits.find(eq(visitId),eq(doctorId),anyBoolean())).thenReturn(Optional.of(visit));
        assertEquals("ASR_ROUTE_MISMATCH",assertThrows(BusinessException.class,
                () -> workflow.retranscribeRecording(visitId,UUID.randomUUID(),doctorId,
                        "LOCAL".equals(route) ? "DASHSCOPE" : "LOCAL")).code());
        verify(recordings,never()).requeueTranscribedRecording(any());
        verify(recordings,never()).createAsrJob(any(),any(),any());
    }
    @Test void activeJobQueryRestoresQueuedTaskState() {
        var pending = job("PENDING", AsrJobWorker.PUBLIC_ROLE_ROUTE);
        when(recordings.activeAsrJob(visitId)).thenReturn(Optional.of(pending));
        when(recordings.list(visitId)).thenReturn(List.of());

        var response = workflow.activeAsrJob(visitId, doctorId, DoctorRole.DOCTOR);

        assertNotNull(response);
        assertEquals(jobId, response.jobId());
        assertEquals("PENDING", response.status());
        assertEquals("DASHSCOPE", response.providerRoute());
        assertEquals(0, response.completedRecordings());
    }
    @ParameterizedTest @ValueSource(strings={"DASHSCOPE","LOCAL"})
    void retryCreatesTaskWithSelectedProvider(String provider) {
        String queueProvider = "DASHSCOPE".equals(provider) ? AsrJobWorker.PUBLIC_ROLE_ROUTE : provider;
        if ("LOCAL".equals(provider)) org.springframework.test.util.ReflectionTestUtils.setField(workflow,"dashscopeApiKey","");
        when(recordings.latestAsrJob(visitId)).thenReturn(Optional.of(job("FAILED",provider.equals("LOCAL") ? "DASHSCOPE" : "LOCAL")));
        when(recordings.list(visitId)).thenReturn(List.of(new Recording(UUID.randomUUID(),visitId,"R1","UPLOAD","key","a.wav","audio/wav",1L,1L,"UPLOADED",null,Instant.now())));
        when(recordings.createAsrJob(visitId,queueProvider)).thenReturn(jobId);
        when(recordings.asrJob(jobId,visitId)).thenReturn(Optional.of(job("PENDING",queueProvider)));
        assertEquals(provider,workflow.transcribe(visitId,doctorId,provider).providerRoute());
        verify(recordings).requeueRetryableRecordings(visitId);
        verify(recordings).createAsrJob(visitId,queueProvider);
    }
    @Test void missingPublicCredentialsRejectedBeforeCreatingTask() {
        org.springframework.test.util.ReflectionTestUtils.setField(workflow,"dashscopeApiKey","");
        assertEquals("DASHSCOPE_NOT_CONFIGURED", assertThrows(BusinessException.class,
                () -> workflow.transcribe(visitId,doctorId,"DASHSCOPE")).code());
        verify(recordings,never()).createAsrJob(any(),any());
    }
    @Test void invalidProviderRejectedBeforeWorkflow() {
        var service = mock(ClinicalWorkflowService.class);
        var controller = new RecordingController(service,recordings,storage);
        var error = assertThrows(BusinessException.class, () -> controller.transcribe(visitId,new RecordingController.TranscribeRequest("invalid"),null));
        assertEquals(HttpStatus.BAD_REQUEST,error.status());
        verifyNoInteractions(service);
    }
}

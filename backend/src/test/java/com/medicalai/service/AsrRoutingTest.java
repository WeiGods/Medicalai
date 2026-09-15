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
        recordings,mock(MedicalRecordMapper.class),mock(AiServiceClient.class),storage,new ObjectMapper(),Clock.systemUTC());
    UUID visitId=UUID.randomUUID(), doctorId=UUID.randomUUID(), jobId=UUID.randomUUID();
    AsrRoutingTest() {
        org.springframework.test.util.ReflectionTestUtils.setField(workflow,"dashscopeApiKey","test-key");
        var visit = new Visit(visitId,"V1",UUID.randomUUID(),doctorId,"ACTIVE",null,null,null,null,null,null,1,Instant.now());
        when(visits.find(eq(visitId),eq(doctorId),anyBoolean())).thenReturn(Optional.of(visit));
    }
    RecordingMapper.AsrJob job(String status,String provider) {
        return new RecordingMapper.AsrJob(jobId,visitId,null,null,status,0,null,null,provider);
    }
    @Test void existingJobKeepsOriginalRoute() {
        when(recordings.latestAsrJob(visitId)).thenReturn(Optional.of(job("RUNNING","DASHSCOPE")));
        when(recordings.asrJob(jobId,visitId)).thenReturn(Optional.of(job("RUNNING","DASHSCOPE")));
        var response = workflow.transcribe(visitId,doctorId,"LOCAL");
        assertEquals(jobId,response.jobId());
        assertEquals("DASHSCOPE",response.providerRoute());
        verify(recordings,never()).createAsrJob(any(),any());
        verify(recordings,never()).requeueRetryableRecordings(any());
    }
    @ParameterizedTest @ValueSource(strings={"DASHSCOPE","LOCAL"})
    void retryCreatesTaskWithSelectedProvider(String provider) {
        if ("LOCAL".equals(provider)) org.springframework.test.util.ReflectionTestUtils.setField(workflow,"dashscopeApiKey","");
        when(recordings.latestAsrJob(visitId)).thenReturn(Optional.of(job("FAILED",provider.equals("LOCAL") ? "DASHSCOPE" : "LOCAL")));
        when(recordings.list(visitId)).thenReturn(List.of(new Recording(UUID.randomUUID(),visitId,"R1","UPLOAD","key","a.wav","audio/wav",1L,1L,"UPLOADED",null,Instant.now())));
        when(recordings.createAsrJob(visitId,provider)).thenReturn(jobId);
        when(recordings.asrJob(jobId,visitId)).thenReturn(Optional.of(job("PENDING",provider)));
        assertEquals(provider,workflow.transcribe(visitId,doctorId,provider).providerRoute());
        verify(recordings).requeueRetryableRecordings(visitId);
        verify(recordings).createAsrJob(visitId,provider);
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

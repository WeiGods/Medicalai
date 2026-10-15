package com.medicalai.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.service.PatientService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 回归：即使运行产物未保留 Java 形参名，查询参数也必须可被 Spring 显式绑定。 */
class PatientControllerBindingTest {
    @Test
    void bindsKeywordWithoutJavaParameterMetadata() throws Exception {
        PatientService service = mock(PatientService.class);
        Doctor doctor = new Doctor(UUID.randomUUID(), "LOCAL_DEMO", "doctor-1", "测试医生", null,
                "神经内科", "DOCTOR", "ACTIVE");
        when(service.list("头痛", doctor)).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PatientController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();

        mvc.perform(get("/api/v1/patients").queryParam("keyword", "头痛")
                        .requestAttr("currentDoctor", new AuthenticatedDoctor(UUID.randomUUID(), doctor)))
                .andExpect(status().isOk());

        verify(service).list("头痛", doctor);
    }
}

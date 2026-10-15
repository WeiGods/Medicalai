package com.medicalai.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.dto.AuditLogQueryRequest;
import com.medicalai.exception.GlobalExceptionHandler;
import com.medicalai.mapper.AuditLogMapper;
import com.medicalai.service.AuditLogService;
import com.medicalai.vo.AuditLogVO;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuditLogControllerTest {
    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditLogService service = new AuditLogService(mapper);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuditLogController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void doctorReceivesForbiddenWhenReadingAuditLogs() throws Exception {
        mvc.perform(get("/api/v1/audit-logs").requestAttr("currentDoctor", current("DOCTOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("AUDIT_ACCESS_DENIED")));
    }

    @Test
    void departmentHeadCanFilterAndPageGlobalAuditLogs() throws Exception {
        AuditLogVO item = new AuditLogVO(UUID.randomUUID(), "LOGIN", "SUCCESS", "张医生", null, null,
                "登录系统", "127.0.0.1", Instant.parse("2026-09-17T00:00:00Z"));
        when(mapper.list(any())).thenReturn(List.of(item));
        when(mapper.count(any())).thenReturn(1L);

        mvc.perform(get("/api/v1/audit-logs")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-17")
                        .param("doctorId", UUID.randomUUID().toString())
                        .param("action", "LOGIN")
                        .param("page", "2")
                        .param("pageSize", "20")
                        .requestAttr("currentDoctor", current("DEPARTMENT_HEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.page", is(2)))
                .andExpect(jsonPath("$.items[0].action", is("LOGIN")));

        ArgumentCaptor<AuditLogQueryRequest> requestCaptor = ArgumentCaptor.forClass(AuditLogQueryRequest.class);
        verify(mapper).list(requestCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("LOGIN", requestCaptor.getValue().action().name());
        org.junit.jupiter.api.Assertions.assertEquals(2, requestCaptor.getValue().normalizedPage());
    }

    @Test
    void administratorCanReadAuditLogs() throws Exception {
        when(mapper.list(any())).thenReturn(List.of());
        when(mapper.count(any())).thenReturn(0L);

        mvc.perform(get("/api/v1/audit-logs").requestAttr("currentDoctor", current("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(0)));
    }

    private AuthenticatedDoctor current(String role) {
        Doctor doctor = new Doctor(UUID.randomUUID(), "LOCAL_DEMO", "test", "测试人员", null,
                "神经内科", role, "ACTIVE");
        return new AuthenticatedDoctor(UUID.randomUUID(), doctor);
    }
}

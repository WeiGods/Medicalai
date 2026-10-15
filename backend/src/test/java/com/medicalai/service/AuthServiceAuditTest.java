package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.mapper.LoginSessionMapper;
import com.medicalai.provider.IdentityProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthServiceAuditTest {
    @Test
    void demoLoginUsesSelectedRoleAndWritesLoginAudit() {
        IdentityProvider identity = mock(IdentityProvider.class);
        DoctorMapper doctors = mock(DoctorMapper.class);
        LoginSessionMapper sessions = mock(LoginSessionMapper.class);
        TokenService tokens = mock(TokenService.class);
        AuditLogService auditLogs = mock(AuditLogService.class);
        Doctor departmentHead = new Doctor(UUID.randomUUID(), "LOCAL_DEMO", "head-1", "王主任", null,
                "神经内科", "DEPARTMENT_HEAD", "ACTIVE");
        when(identity.authenticate("王主任", DoctorRole.DEPARTMENT_HEAD)).thenReturn(departmentHead);
        when(tokens.issue()).thenReturn("test-token");
        AuthService service = new AuthService(identity, doctors, sessions, tokens, auditLogs,
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC), 3600);

        var login = service.demoLogin("王主任", DoctorRole.DEPARTMENT_HEAD, "127.0.0.1");

        assertEquals("DEPARTMENT_HEAD", login.doctor().role());
        verify(identity).authenticate("王主任", DoctorRole.DEPARTMENT_HEAD);
        verify(doctors).markLogin(departmentHead.id());
        verify(sessions).create(eq(departmentHead.id()), any(String.class), any(Instant.class));
        verify(auditLogs).recordLogin(departmentHead, "127.0.0.1");
    }

    @Test
    void demoLoginRejectsAdminRoleSubmittedByClient() {
        IdentityProvider identity = mock(IdentityProvider.class);
        AuthService service = new AuthService(identity, mock(DoctorMapper.class), mock(LoginSessionMapper.class),
                mock(TokenService.class), mock(AuditLogService.class), Clock.systemUTC(), 3600);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.demoLogin("管理员", DoctorRole.ADMIN, "127.0.0.1"));

        assertEquals("DEMO_ROLE_NOT_ALLOWED", exception.code());
        verifyNoInteractions(identity);
    }
}

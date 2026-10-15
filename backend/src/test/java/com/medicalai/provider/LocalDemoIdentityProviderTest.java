package com.medicalai.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.DoctorMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalDemoIdentityProviderTest {
    @Test
    void sameNameAndRoleReusesExistingDemoDoctor() {
        DoctorMapper mapper = mock(DoctorMapper.class);
        Doctor existing = doctor("小明", "DOCTOR");
        when(mapper.findLocalDemoByDisplayName("小明")).thenReturn(List.of(existing));
        LocalDemoIdentityProvider provider = new LocalDemoIdentityProvider(mapper, "DEMO");

        Doctor result = provider.authenticate("小明", DoctorRole.DOCTOR);

        assertSame(existing, result);
        verify(mapper).findLocalDemoByDisplayName("小明");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void sameNameWithDifferentRoleIsRejected() {
        DoctorMapper mapper = mock(DoctorMapper.class);
        when(mapper.findLocalDemoByDisplayName("小明")).thenReturn(List.of(doctor("小明", "DOCTOR")));
        LocalDemoIdentityProvider provider = new LocalDemoIdentityProvider(mapper, "DEMO");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.authenticate("小明", DoctorRole.DEPARTMENT_HEAD));

        assertEquals("DEMO_DOCTOR_NAME_TAKEN", exception.code());
        verify(mapper).findLocalDemoByDisplayName("小明");
    }

    @Test
    void duplicateHistoricalDemoDoctorsAreRejected() {
        DoctorMapper mapper = mock(DoctorMapper.class);
        when(mapper.findLocalDemoByDisplayName("小明"))
                .thenReturn(List.of(doctor("小明", "DOCTOR"), doctor("小明", "DEPARTMENT_HEAD")));
        LocalDemoIdentityProvider provider = new LocalDemoIdentityProvider(mapper, "DEMO");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> provider.authenticate("小明", DoctorRole.DOCTOR));

        assertEquals("DEMO_DOCTOR_NAME_DUPLICATE", exception.code());
        verify(mapper).findLocalDemoByDisplayName("小明");
    }

    @Test
    void newDemoDoctorUsesOneStableIdentityRegardlessOfRole() {
        DoctorMapper mapper = mock(DoctorMapper.class);
        Doctor created = doctor("小明", "DOCTOR");
        when(mapper.findLocalDemoByDisplayName("小明")).thenReturn(List.of());
        when(mapper.upsertDemo(org.mockito.ArgumentMatchers.startsWith("demo:"),
                org.mockito.ArgumentMatchers.eq("小明"), org.mockito.ArgumentMatchers.eq(DoctorRole.DOCTOR)))
                .thenReturn(created);
        LocalDemoIdentityProvider provider = new LocalDemoIdentityProvider(mapper, "DEMO");

        Doctor result = provider.authenticate(" 小明 ", DoctorRole.DOCTOR);

        assertSame(created, result);
        verify(mapper).upsertDemo(org.mockito.ArgumentMatchers.startsWith("demo:"),
                org.mockito.ArgumentMatchers.eq("小明"), org.mockito.ArgumentMatchers.eq(DoctorRole.DOCTOR));
    }

    private static Doctor doctor(String name, String role) {
        return new Doctor(UUID.randomUUID(), "LOCAL_DEMO", "demo-id", name,
                null, "神经内科", role, "ACTIVE");
    }
}

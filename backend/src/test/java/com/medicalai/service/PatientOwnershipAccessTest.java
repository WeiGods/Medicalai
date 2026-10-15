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
import com.medicalai.domain.Patient;
import com.medicalai.domain.Visit;
import com.medicalai.dto.CreatePatientRequest;
import com.medicalai.dto.CreateVisitRequest;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.mapper.PatientMapper;
import com.medicalai.mapper.VisitMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 患者可见范围和接诊归属必须由服务层统一约束，不能依赖前端隐藏操作入口。 */
class PatientOwnershipAccessTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void doctorOnlyQueriesPatientsCreatedBySelf() {
        PatientMapper mapper = mock(PatientMapper.class);
        PatientService service = new PatientService(mapper, CLOCK);
        Doctor doctor = doctor("DOCTOR");
        Patient patient = patient(doctor);
        when(mapper.find("", doctor.id(), false)).thenReturn(List.of(patient));

        var result = service.list("", doctor);

        assertEquals(1, result.size());
        assertEquals(doctor.id(), result.getFirst().createdBy());
        verify(mapper).find("", doctor.id(), false);
    }

    @Test
    void departmentHeadQueriesAllPatientsAndReceivesCreatorMetadata() {
        PatientMapper mapper = mock(PatientMapper.class);
        PatientService service = new PatientService(mapper, CLOCK);
        Doctor creator = doctor("DOCTOR");
        Doctor head = doctor("DEPARTMENT_HEAD");
        when(mapper.find("", head.id(), true)).thenReturn(List.of(patient(creator)));

        var result = service.list("", head);

        assertEquals(creator.id(), result.getFirst().createdBy());
        assertEquals(creator.displayName(), result.getFirst().createdByName());
        verify(mapper).find("", head.id(), true);
    }

    @Test
    void manualPatientPersistsCurrentDoctorAsCreator() {
        PatientMapper mapper = mock(PatientMapper.class);
        PatientService service = new PatientService(mapper, CLOCK);
        Doctor doctor = doctor("DOCTOR");
        when(mapper.insertManual(any(UUID.class), eq("患者甲"), eq("男"), any(), eq("138****5678"),
                eq("110****002X"), eq("神经内科"), eq(doctor.id()))).thenReturn(patient(doctor));

        service.create(new CreatePatientRequest("患者甲", "男", 30, "13812345678", "11010519491231002X"), doctor);

        verify(mapper).insertManual(any(UUID.class), eq("患者甲"), eq("男"), any(), eq("138****5678"),
                eq("110****002X"), eq("神经内科"), eq(doctor.id()));
    }

    @Test
    void departmentHeadCanListAllVisitsButCannotCreateVisitForAnotherDoctorsPatient() {
        VisitMapper visits = mock(VisitMapper.class);
        PatientMapper patients = mock(PatientMapper.class);
        DoctorMapper doctors = mock(DoctorMapper.class);
        VisitService service = new VisitService(visits, patients, doctors,
                mock(AudioStorageService.class), CLOCK);
        Doctor owner = doctor("DOCTOR");
        Doctor head = doctor("DEPARTMENT_HEAD");
        Visit foreignVisit = new Visit(UUID.randomUUID(), "VIS-001", UUID.randomUUID(), owner.id(), "ACTIVE",
                "神经内科", "患者乙", "女", 40, owner.displayName(), null, 0, Instant.now());
        when(visits.findAll()).thenReturn(List.of(foreignVisit));
        UUID foreignPatientId = UUID.randomUUID();
        when(patients.findOwnedByIdForUpdate(foreignPatientId, head.id())).thenReturn(Optional.empty());

        var visibleVisits = service.list(head);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(new CreateVisitRequest(foreignPatientId, null), head));

        assertEquals(1, visibleVisits.size());
        assertEquals(owner.displayName(), visibleVisits.getFirst().doctorName());
        assertEquals("NOT_FOUND", exception.code());
        verify(visits).findAll();
        verify(patients).findOwnedByIdForUpdate(foreignPatientId, head.id());
        verifyNoInteractions(doctors);
    }

    @Test
    void doctorOnlyQueriesOwnVisits() {
        VisitMapper visits = mock(VisitMapper.class);
        PatientMapper patients = mock(PatientMapper.class);
        DoctorMapper doctors = mock(DoctorMapper.class);
        VisitService service = new VisitService(visits, patients, doctors,
                mock(AudioStorageService.class), CLOCK);
        Doctor doctor = doctor("DOCTOR");
        Visit ownVisit = new Visit(UUID.randomUUID(), "VIS-002", UUID.randomUUID(), doctor.id(), "ACTIVE",
                "神经内科", "患者丙", "女", 35, doctor.displayName(), null, 0, Instant.now());
        when(visits.findAll(doctor.id())).thenReturn(List.of(ownVisit));

        var result = service.list(doctor);

        assertEquals(1, result.size());
        assertEquals(doctor.id(), result.getFirst().doctorId());
        verify(visits).findAll(doctor.id());
        verifyNoInteractions(patients, doctors);
    }

    @Test
    void doctorCannotCreateVisitForAnotherDoctorsPatient() {
        VisitMapper visits = mock(VisitMapper.class);
        PatientMapper patients = mock(PatientMapper.class);
        VisitService service = new VisitService(visits, patients, mock(DoctorMapper.class),
                mock(AudioStorageService.class), CLOCK);
        Doctor doctor = doctor("DOCTOR");
        UUID foreignPatientId = UUID.randomUUID();
        when(patients.findOwnedByIdForUpdate(foreignPatientId, doctor.id())).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(new CreateVisitRequest(foreignPatientId, null), doctor));

        assertEquals("NOT_FOUND", exception.code());
        verify(patients).findOwnedByIdForUpdate(foreignPatientId, doctor.id());
        verifyNoInteractions(visits);
    }

    @Test
    void doctorCannotReadAnotherDoctorsPatientById() {
        PatientMapper mapper = mock(PatientMapper.class);
        PatientService service = new PatientService(mapper, CLOCK);
        Doctor doctor = doctor("DOCTOR");
        UUID foreignPatientId = UUID.randomUUID();
        when(mapper.findById(foreignPatientId, doctor.id(), false)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.get(foreignPatientId, doctor));

        assertEquals("NOT_FOUND", exception.code());
        verify(mapper).findById(foreignPatientId, doctor.id(), false);
    }

    private static Doctor doctor(String role) {
        UUID id = UUID.randomUUID();
        return new Doctor(id, "LOCAL_DEMO", id.toString(), role + "医生", null, "神经内科", role, "ACTIVE");
    }

    private static Patient patient(Doctor creator) {
        return new Patient(UUID.randomUUID(), "M-001", "患者甲", "男", java.time.LocalDate.of(1996, 1, 1),
                "138****5678", "110****5678", "神经内科", "ACTIVE", creator.id(), creator.displayName());
    }
}

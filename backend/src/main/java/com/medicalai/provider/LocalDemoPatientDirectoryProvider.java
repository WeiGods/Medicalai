package com.medicalai.provider;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalDemoPatientDirectoryProvider implements PatientDirectoryProvider {
    @Override
    public List<PatientProfile> listPatients() {
        return List.of(
                new PatientProfile("LOCAL_DEMO", "001", "001", "张三", "男", LocalDate.of(1981,1,1), "138****0001", "340XXX000001"),
                new PatientProfile("LOCAL_DEMO", "002", "002", "李四", "女", LocalDate.of(1987,1,1), "138****0002", "340XXX000002"),
                new PatientProfile("LOCAL_DEMO", "003", "003", "王五", "男", LocalDate.of(1973,1,1), "138****0003", "340XXX000003"));
    }
}

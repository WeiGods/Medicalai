package com.medicalai.config;

import com.medicalai.service.PatientService;
import java.time.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
public class ApplicationConfig {
    @Bean
    public Clock clock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }

    @Bean
    @ConditionalOnProperty(name="medicalai.patient-directory.mode", havingValue="LOCAL_DEMO", matchIfMissing=true)
    public ApplicationRunner initializeDemoPatients(PatientService service) {
        return args -> service.syncDemoPatients();
    }
}

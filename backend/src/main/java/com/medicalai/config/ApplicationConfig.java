package com.medicalai.config;

import java.time.*;
import org.springframework.context.annotation.*;

@Configuration
public class ApplicationConfig {
    @Bean
    public Clock clock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }
}

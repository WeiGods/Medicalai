package com.medicalai.provider;

import com.medicalai.domain.Doctor;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.exception.BusinessException;
import com.medicalai.service.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalDemoIdentityProvider implements IdentityProvider {
    private final DoctorMapper mapper;
    private final String mode;

    public LocalDemoIdentityProvider(DoctorMapper mapper, @Value("${medicalai.auth.mode:DEMO}") String mode) {
        this.mapper = mapper;
        this.mode = mode;
    }

    @Override
    public Doctor authenticate(String credential) {
        if (!"DEMO".equals(mode)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_NOT_CONFIGURED",
                    "OA 身份服务尚未配置，演示登录已关闭");
        }
        String name = credential.strip();
        // Demo-only alias. Future OA accounts map by provider subject, never by display name.
        return mapper.upsertDemo("demo:" + TokenService.hash(name), name);
    }
}

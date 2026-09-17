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
        // 仅供演示的别名。后续 OA 账号必须按身份提供方主体标识映射，不能按展示名称映射。
        return mapper.upsertDemo("demo:" + TokenService.hash(name), name);
    }
}

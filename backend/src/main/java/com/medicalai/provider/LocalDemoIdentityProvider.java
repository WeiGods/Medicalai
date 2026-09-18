package com.medicalai.provider;

import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.service.TokenService;
import java.util.List;
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
    public Doctor authenticate(String credential, DoctorRole requestedRole) {
        if (!"DEMO".equals(mode)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_NOT_CONFIGURED",
                    "OA 身份服务尚未配置，演示登录已关闭");
        }
        if (!requestedRole.canUseDemoLogin()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DEMO_ROLE_NOT_ALLOWED", "演示登录仅支持医生或科室长");
        }
        String name = credential.strip();
        List<Doctor> matches = mapper.findLocalDemoByDisplayName(name);
        if (matches.size() > 1) {
            throw BusinessException.conflict("DEMO_DOCTOR_NAME_DUPLICATE", "该姓名已存在多个演示医生账号，请先清理重复数据");
        }
        if (matches.size() == 1) {
            Doctor existing = matches.get(0);
            if (!requestedRole.name().equals(existing.role())) {
                throw BusinessException.conflict("DEMO_DOCTOR_NAME_TAKEN", "该姓名已绑定为" + roleLabel(existing.role()) + "，不能再次使用其他角色登录");
            }
            // 同一个演示账号重复登录只复用原身份，不会创建第二条医生记录。
            return existing;
        }
        // 姓名是演示账号的稳定身份键，不能把角色拼进身份键，否则同名切换角色会产生第二个医生。
        return mapper.upsertDemo("demo:" + TokenService.hash(name), name, requestedRole);
    }

    private static String roleLabel(String role) {
        return switch (DoctorRole.from(role)) {
            case DEPARTMENT_HEAD -> "科室长";
            case ADMIN -> "管理员";
            default -> "医生";
        };
    }
}

package com.medicalai.service;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Doctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.DoctorMapper;
import com.medicalai.mapper.LoginSessionMapper;
import com.medicalai.provider.IdentityProvider;
import com.medicalai.vo.DoctorVO;
import com.medicalai.vo.LoginVO;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final IdentityProvider identity;
    private final DoctorMapper doctors;
    private final LoginSessionMapper sessions;
    private final TokenService tokens;
    private final AuditLogService auditLogs;
    private final Clock clock;
    private final long sessionSeconds;

    public AuthService(IdentityProvider identity, DoctorMapper doctors, LoginSessionMapper sessions,
                       TokenService tokens, AuditLogService auditLogs, Clock clock,
                       @Value("${medicalai.auth.session-seconds:28800}") long sessionSeconds) {
        this.identity=identity; this.doctors=doctors; this.sessions=sessions;
        this.tokens=tokens; this.auditLogs=auditLogs; this.clock=clock; this.sessionSeconds=sessionSeconds;
    }

    @Transactional
    public LoginVO demoLogin(String name, DoctorRole role, String clientIp) {
        if (role == null || !role.canUseDemoLogin()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DEMO_ROLE_NOT_ALLOWED", "演示登录仅支持医生或科室长");
        }
        Doctor doctor = identity.authenticate(name, role);
        if (!"ACTIVE".equals(doctor.status())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "DOCTOR_DISABLED", "该医生账号已停用");
        }
        String token = tokens.issue();
        sessions.create(doctor.id(), TokenService.hash(token), clock.instant().plusSeconds(sessionSeconds));
        doctors.markLogin(doctor.id());
        auditLogs.recordLogin(doctor, clientIp);
        return new LoginVO(token, "Bearer", sessionSeconds, DoctorVO.from(doctor));
    }

    public AuthenticatedDoctor authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 256) {
            throw unauthorized();
        }
        AuthenticatedDoctor result = sessions.authenticate(TokenService.hash(authorization.substring(7)))
                .orElseThrow(AuthService::unauthorized);
        sessions.touch(result.sessionId());
        return result;
    }

    public void logout(AuthenticatedDoctor current) { sessions.revoke(current.sessionId()); }

    private static BusinessException unauthorized() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录已失效，请重新登录");
    }
}

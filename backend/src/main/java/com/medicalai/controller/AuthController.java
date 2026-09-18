package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.DemoLoginRequest;
import com.medicalai.service.AuthService;
import com.medicalai.vo.DoctorVO;
import com.medicalai.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }

    @PostMapping("/demo-login")
    public LoginVO login(@Valid @RequestBody DemoLoginRequest request, HttpServletRequest httpRequest) {
        // 只记录服务端实际接收到的地址，避免直接信任可被客户端伪造的请求头。
        return service.demoLogin(request.doctorName(), request.role(), httpRequest.getRemoteAddr());
    }

    @GetMapping("/me")
    public DoctorVO me(@RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return DoctorVO.from(current.doctor());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        service.logout(current);
    }
}

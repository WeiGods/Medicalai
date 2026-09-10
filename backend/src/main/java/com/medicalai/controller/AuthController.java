package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.dto.DemoLoginRequest;
import com.medicalai.service.AuthService;
import com.medicalai.vo.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }

    @PostMapping("/demo-login")
    public LoginVO login(@Valid @RequestBody DemoLoginRequest request) {
        return service.demoLogin(request.doctorName());
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

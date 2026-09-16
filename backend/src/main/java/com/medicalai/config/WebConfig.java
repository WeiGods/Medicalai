package com.medicalai.config;

import com.medicalai.service.AuthService;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthService auth;
    public WebConfig(AuthService auth) { this.auth=auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("OPTIONS".equals(request.getMethod())) return true;
                request.setAttribute("currentDoctor", auth.authenticate(request.getHeader("Authorization")));
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/v1/auth/demo-login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").allowedHeaders("*");
    }
}

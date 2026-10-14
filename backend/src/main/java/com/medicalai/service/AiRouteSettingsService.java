package com.medicalai.service;

import com.medicalai.domain.*;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.AiRouteSettingsMapper;
import com.medicalai.vo.AiRouteSettingsVO;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiRouteSettingsService {
    private final AiRouteSettingsMapper mapper;
    private final DashScopeAsrClient dashscopeAsr;
    private final LocalAsrClient localAsr;
    private final LlmRoleRouter roles;
    private final ClinicalExtractionRouter extraction;
    private final AuditLogService audit;

    public AiRouteSettingsService(AiRouteSettingsMapper mapper, DashScopeAsrClient dashscopeAsr,
                                  LocalAsrClient localAsr, LlmRoleRouter roles,
                                  ClinicalExtractionRouter extraction, AuditLogService audit) {
        this.mapper = mapper; this.dashscopeAsr = dashscopeAsr; this.localAsr = localAsr;
        this.roles = roles; this.extraction = extraction; this.audit = audit;
    }

    public AiRouteSettingsVO get() { return AiRouteSettingsVO.from(snapshot()); }

    @Transactional
    public AiRouteSettingsVO update(Doctor doctor, String route) {
        if (doctor == null || DoctorRole.from(doctor.role()) != DoctorRole.DEPARTMENT_HEAD) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "AI_ROUTE_SETTINGS_FORBIDDEN", "仅科室长可以修改 AI 处理环境");
        }
        String normalized = route == null ? "" : route.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals("DASHSCOPE") && !normalized.equals("LOCAL")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AI_ROUTE", "AI 处理环境只支持 DASHSCOPE 或 LOCAL");
        }
        String previous = mapper.find().map(AiRouteSettingsMapper.Stored::route).orElse("DASHSCOPE");
        mapper.save(normalized, doctor.id());
        AiRouteSettingsVO result = get();
        audit.recordAiRouteChanged(doctor.id(), previous, normalized, result.unavailableCapabilities());
        return result;
    }

    public String currentRoute() { return snapshot().route(); }

    /** 首次转写时使用；能力检查留给实际客户端，避免把设置页健康检查变成外部网络副作用。 */
    public AiRouteSettings snapshot() {
        String route = mapper.find().map(AiRouteSettingsMapper.Stored::route).orElse("DASHSCOPE");
        boolean publicAsr = dashscopeAsr.isConfigured();
        boolean localAsrConfigured = localAsr.isConfigured();
        boolean publicRole = roles.isAvailable(LlmRoute.DASHSCOPE);
        boolean localRole = roles.isAvailable(LlmRoute.LOCAL);
        boolean publicExtraction = extraction.isAvailable(LlmRoute.DASHSCOPE);
        boolean localExtraction = extraction.isAvailable(LlmRoute.LOCAL);
        boolean asr = route.equals("LOCAL") ? localAsrConfigured : publicAsr;
        boolean role = route.equals("LOCAL") ? localRole : publicRole;
        boolean ext = route.equals("LOCAL") ? localExtraction : publicExtraction;
        String asrMessage = asr ? "可用" : (route.equals("LOCAL") ? "本地 ASR 地址未配置" : "未配置 DASHSCOPE_API_KEY");
        String roleMessage = role ? "可用" : (route.equals("LOCAL") ? "本地角色识别未启用" : "未配置公网角色识别凭据");
        String extMessage = ext ? "可用" : (route.equals("LOCAL") ? "本地信息提取未启用" : "未配置公网信息提取凭据");
        List<String> unavailable = new ArrayList<>();
        if (!asr) unavailable.add("音频转写");
        if (!role) unavailable.add("角色识别");
        if (!ext) unavailable.add("临床信息提取");
        AiRouteSettingsMapper.Stored stored = mapper.find().orElse(null);
        return new AiRouteSettings(route, asr, role, ext, asrMessage, roleMessage, extMessage,
                stored == null ? null : stored.updatedByName(), stored == null ? null : stored.updatedAt(), unavailable,
                publicAsr, publicRole, publicExtraction,
                capabilityMessage(publicAsr, "公网 ASR 未配置 DASHSCOPE_API_KEY"),
                capabilityMessage(publicRole, "公网角色识别未配置凭据"),
                capabilityMessage(publicExtraction, "公网信息提取未配置凭据"),
                localAsrConfigured, localRole, localExtraction,
                capabilityMessage(localAsrConfigured, "本地 ASR 地址未配置"),
                capabilityMessage(localRole, "本地角色识别未启用"),
                capabilityMessage(localExtraction, "本地信息提取未启用"));
    }

    private static String capabilityMessage(boolean available, String unavailable) {
        return available ? "可用" : unavailable;
    }
}

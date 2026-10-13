package com.medicalai.service;

import java.util.List;
import java.util.Map;

/**
 * 角色识别 LLM 通信适配器契约。
 *
 * <p>无论底层走公网 DashScope 还是内网本地 LLM（llama.cpp），对调用方暴露的语义完全一致：
 * 按 ASR 句段逐句判断 DOCTOR / PATIENT / OTHER，分批调用、失败重试、最终降级为 FALLBACK，
 * 绝不因模型不可用而丢弃转写结果。
 *
 * <p>两条共用同一套系统提示词与角色判断指令，保证跨路由的结果口径一致。
 */
public interface RoleLlmClient {

    /** 逐句角色分类的系统提示词（跨路由共用）。 */
    String SYSTEM_PROMPT = RolePromptResource.SYSTEM;

    /** 角色判断指令（含约束与示例，跨路由共用）。 */
    String ROLE_ASSIGNMENT_INSTRUCTION = RolePromptResource.INSTRUCTION;

    /** 该路由是否已配置可用。 */
    boolean isConfigured();

    /**
     * 对每个规范化 ASR 句段独立判断角色，speaker_id 仅作为上下文。
     *
     * @param turns 含有唯一、非负整数 index 的 ASR 句段
     * @return 与输入 index 对应的角色结果；调用异常时仅对受影响批次返回降级结果
     */
    Map<Integer, DashScopeRoleClient.RoleAssignment> assignRoles(List<Map<String, Object>> turns);
}

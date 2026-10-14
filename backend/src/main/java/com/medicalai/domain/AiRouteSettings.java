package com.medicalai.domain;

import java.time.Instant;
import java.util.List;

/** 全系统统一的 AI 处理环境及当前运行能力状态。 */
public record AiRouteSettings(String route, boolean asrAvailable, boolean roleAvailable,
                              boolean extractionAvailable, String asrMessage, String roleMessage,
                              String extractionMessage, String updatedBy, Instant updatedAt,
                              List<String> unavailableCapabilities,
                              boolean publicAsrAvailable, boolean publicRoleAvailable, boolean publicExtractionAvailable,
                              String publicAsrMessage, String publicRoleMessage, String publicExtractionMessage,
                              boolean localAsrAvailable, boolean localRoleAvailable, boolean localExtractionAvailable,
                              String localAsrMessage, String localRoleMessage, String localExtractionMessage) {
}

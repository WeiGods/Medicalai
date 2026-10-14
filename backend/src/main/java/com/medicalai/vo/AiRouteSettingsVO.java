package com.medicalai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.medicalai.domain.AiRouteSettings;
import java.time.Instant;
import java.util.List;

public record AiRouteSettingsVO(
        String route,
        @JsonProperty("asr_available") boolean asrAvailable,
        @JsonProperty("role_available") boolean roleAvailable,
        @JsonProperty("extraction_available") boolean extractionAvailable,
        @JsonProperty("asr_message") String asrMessage,
        @JsonProperty("role_message") String roleMessage,
        @JsonProperty("extraction_message") String extractionMessage,
        @JsonProperty("updated_by") String updatedBy,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("unavailable_capabilities") List<String> unavailableCapabilities,
        @JsonProperty("public_asr_available") boolean publicAsrAvailable,
        @JsonProperty("public_role_available") boolean publicRoleAvailable,
        @JsonProperty("public_extraction_available") boolean publicExtractionAvailable,
        @JsonProperty("public_asr_message") String publicAsrMessage,
        @JsonProperty("public_role_message") String publicRoleMessage,
        @JsonProperty("public_extraction_message") String publicExtractionMessage,
        @JsonProperty("local_asr_available") boolean localAsrAvailable,
        @JsonProperty("local_role_available") boolean localRoleAvailable,
        @JsonProperty("local_extraction_available") boolean localExtractionAvailable,
        @JsonProperty("local_asr_message") String localAsrMessage,
        @JsonProperty("local_role_message") String localRoleMessage,
        @JsonProperty("local_extraction_message") String localExtractionMessage) {
    public static AiRouteSettingsVO from(AiRouteSettings settings) {
        return new AiRouteSettingsVO(settings.route(), settings.asrAvailable(), settings.roleAvailable(),
                settings.extractionAvailable(), settings.asrMessage(), settings.roleMessage(),
                settings.extractionMessage(), settings.updatedBy(), settings.updatedAt(),
                settings.unavailableCapabilities(), settings.publicAsrAvailable(), settings.publicRoleAvailable(),
                settings.publicExtractionAvailable(), settings.publicAsrMessage(), settings.publicRoleMessage(),
                settings.publicExtractionMessage(), settings.localAsrAvailable(), settings.localRoleAvailable(),
                settings.localExtractionAvailable(), settings.localAsrMessage(), settings.localRoleMessage(),
                settings.localExtractionMessage());
    }
}

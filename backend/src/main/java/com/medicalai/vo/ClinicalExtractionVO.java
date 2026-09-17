package com.medicalai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 当前快照对应的结构化信息提取结果。 */
public record ClinicalExtractionVO(
        @JsonProperty("extraction_id") String extractionId,
        @JsonProperty("version_no") int versionNo,
        String status,
        @JsonProperty("snapshot_id") String snapshotId,
        @JsonProperty("snapshot_hash") String snapshotHash,
        Map<String, ClinicalFactVO> fields,
        @JsonProperty("quality_issues") List<String> qualityIssues,
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("confirmed_at") Instant confirmedAt,
        @JsonProperty("provider_route") String providerRoute,
        @JsonProperty("source_route") String sourceRoute,
        @JsonProperty("available_routes") List<String> availableRoutes,
        @JsonProperty("route_selection_required") boolean routeSelectionRequired) {
    public ClinicalExtractionVO(String extractionId, int versionNo, String status, String snapshotId,
                                String snapshotHash, Map<String, ClinicalFactVO> fields,
                                List<String> qualityIssues, Instant generatedAt, Instant confirmedAt) {
        this(extractionId, versionNo, status, snapshotId, snapshotHash, fields, qualityIssues, generatedAt,
                confirmedAt, null, "UNKNOWN", List.of(), false);
    }
}

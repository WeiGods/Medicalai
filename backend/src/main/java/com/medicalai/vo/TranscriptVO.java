package com.medicalai.vo;

import java.util.List;

public record TranscriptVO(String snapshotId, int snapshotVersion, String snapshotHash,
                           String authorityStatus, String transcript, boolean edited,
                           boolean sourceDirty, List<UtteranceVO> turns,
                           String sourceRoute, List<String> availableRoutes,
                           boolean routeSelectionRequired) {
    public TranscriptVO(String snapshotId, int snapshotVersion, String snapshotHash,
                        String authorityStatus, String transcript, boolean edited,
                        boolean sourceDirty, List<UtteranceVO> turns) {
        this(snapshotId, snapshotVersion, snapshotHash, authorityStatus, transcript, edited, sourceDirty, turns,
                "UNKNOWN", List.of(), false);
    }
}

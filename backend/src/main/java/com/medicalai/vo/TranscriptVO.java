package com.medicalai.vo;

import java.util.List;

public record TranscriptVO(String snapshotId, int snapshotVersion, String snapshotHash,
                           String authorityStatus, String transcript, boolean edited,
                           boolean sourceDirty, List<UtteranceVO> turns) {}

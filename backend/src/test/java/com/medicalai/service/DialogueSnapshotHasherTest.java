package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.medicalai.mapper.RecordingMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DialogueSnapshotHasherTest {
    @Test
    void includesRoleMetadataAndTimeRangeInSnapshotHash() {
        UUID visitId = UUID.randomUUID();
        RecordingMapper.Turn original = new RecordingMapper.Turn("PATIENT", "头痛三天", 0, 1000,
                1, "LLM", 95);
        RecordingMapper.Turn manualRole = new RecordingMapper.Turn("PATIENT", "头痛三天", 0, 1000,
                1, "MANUAL", null);
        RecordingMapper.Turn differentTime = new RecordingMapper.Turn("PATIENT", "头痛三天", 0, 1200,
                1, "LLM", 95);

        String originalHash = DialogueSnapshotHasher.hash(visitId, List.of(original));

        assertNotEquals(originalHash, DialogueSnapshotHasher.hash(visitId, List.of(manualRole)));
        assertNotEquals(originalHash, DialogueSnapshotHasher.hash(visitId, List.of(differentTime)));
    }
}

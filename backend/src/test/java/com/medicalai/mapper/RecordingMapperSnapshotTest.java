package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medicalai.domain.DialogueSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RecordingMapperSnapshotTest {

    @Test
    void reusesExistingSnapshotWhenTheFrozenTurnContentIsIdentical() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        RecordingMapper mapper = new RecordingMapper(jdbc);
        UUID visitId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        String snapshotHash = "same-dialogue-content";
        DialogueSnapshot base = new DialogueSnapshot(snapshotId, visitId, UUID.randomUUID(), UUID.randomUUID(),
                1, snapshotHash, "ADOPTED", Instant.now());

        when(jdbc.query(startsWith("SELECT id FROM dialogue_snapshot"), any(RowMapper.class),
                eq(visitId), eq(snapshotHash))).thenReturn(List.of(snapshotId));

        UUID result = mapper.createEditedSnapshot(visitId, base,
                List.of(new RecordingMapper.Turn("OTHER", "请在外面等候", 0, 900, 0, "MANUAL", null)),
                snapshotHash, UUID.randomUUID());

        assertEquals(snapshotId, result);
        verify(jdbc).update(contains("SET authority_status=CASE"), eq(snapshotId), eq(visitId), eq(snapshotId));
        verify(jdbc, never()).update(startsWith("INSERT INTO dialogue_snapshot"), any(), any(), any(), any(), any(),
                any(), any(), any());
    }
}

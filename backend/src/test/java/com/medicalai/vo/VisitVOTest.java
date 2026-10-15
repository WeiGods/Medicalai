package com.medicalai.vo;

import static org.assertj.core.api.Assertions.assertThat;

import com.medicalai.domain.Visit;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisitVOTest {
    private static final Instant CREATED = Instant.parse("2026-09-17T01:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-09-17T02:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-09-17T03:00:00Z");

    @Test
    void exposesTheMostRecentAvailableVisitActivityTime() {
        assertThat(VisitVO.from(visit(STARTED, COMPLETED)).lastActivityAt()).isEqualTo(COMPLETED);
        assertThat(VisitVO.from(visit(STARTED, null)).lastActivityAt()).isEqualTo(STARTED);
        assertThat(VisitVO.from(visit(null, null)).lastActivityAt()).isEqualTo(CREATED);
    }

    private Visit visit(Instant startedAt, Instant completedAt) {
        UUID id = UUID.randomUUID();
        return new Visit(id, "V-001", id, id, "COMPLETED", null, null, null, null,
                null, null, 1, startedAt, completedAt, CREATED);
    }
}

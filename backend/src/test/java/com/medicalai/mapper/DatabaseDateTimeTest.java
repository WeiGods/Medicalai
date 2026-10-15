package com.medicalai.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DatabaseDateTimeTest {
    @Test
    void convertsTimezoneFreeBeijingValuesWithoutDependingOnTheMachineTimezone() {
        Instant instant = Instant.parse("2026-09-15T07:55:32.987654Z");

        assertThat(DatabaseDateTime.toTimestamp(instant).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 15, 15, 55, 32));
        assertThat(DatabaseDateTime.toInstant(LocalDateTime.of(2026, 9, 15, 15, 55, 32)))
                .isEqualTo(Instant.parse("2026-09-15T07:55:32Z"));
    }
}

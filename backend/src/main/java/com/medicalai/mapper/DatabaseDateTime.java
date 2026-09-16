package com.medicalai.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Converts the database's timezone-free Beijing timestamps at the JDBC boundary. */
public final class DatabaseDateTime {
    private static final ZoneId STORAGE_ZONE = ZoneId.of("Asia/Shanghai");

    private DatabaseDateTime() {}

    public static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        return toInstant(resultSet.getObject(column, LocalDateTime.class));
    }

    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.SECONDS).atZone(STORAGE_ZONE).toInstant();
    }

    public static Timestamp toTimestamp(Instant value) {
        if (value == null) return null;
        LocalDateTime local = LocalDateTime.ofInstant(value, STORAGE_ZONE).truncatedTo(ChronoUnit.SECONDS);
        return Timestamp.valueOf(local);
    }
}

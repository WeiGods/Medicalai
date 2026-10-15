package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class MedicalRecordMapperTest {
    @Test
    void absentMedicalRecordIsDraftRatherThanDatabaseFailure() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:medical-record-mapper-test.sql").build();
        try {
            var mapper = new MedicalRecordMapper(new JdbcTemplate(database));
            UUID visitId = UUID.randomUUID();

            assertEquals("DRAFT", mapper.status(visitId));
        } finally {
            database.shutdown();
        }
    }
}

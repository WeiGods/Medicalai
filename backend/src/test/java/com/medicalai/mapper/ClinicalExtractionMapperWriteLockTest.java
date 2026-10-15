package com.medicalai.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medicalai.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ClinicalExtractionMapperWriteLockTest {
    @Test
    void doesNotSaveExtractionAfterTheVisitWasDeletedDuringModelCall() {
        String databaseName = "extraction-lock-" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE visit (id UUID PRIMARY KEY, doctor_id UUID, status VARCHAR)");
        ClinicalExtractionMapper mapper = new ClinicalExtractionMapper(jdbc);

        BusinessException error = assertThrows(BusinessException.class, () -> mapper.insert(
                UUID.randomUUID(), UUID.randomUUID(), "snapshot-hash", "GENERATED", "{}", "[]",
                "DASHSCOPE", "model", UUID.randomUUID()));

        assertEquals("NOT_FOUND", error.code());
    }
}

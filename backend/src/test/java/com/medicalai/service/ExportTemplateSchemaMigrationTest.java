package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExportTemplateSchemaMigrationTest {
    @Test
    void upgradesOnlySystemV1PointersToTheStyledV2Revisions() throws Exception {
        String schema;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("db/migration/schema.sql")) {
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(schema.contains("000000000206"));
        assertTrue(schema.contains("000000000207"));
        assertTrue(schema.contains("000000000208"));
        assertTrue(schema.contains("current_revision_id='00000000-0000-0000-0000-000000000106'"));
        assertTrue(schema.contains("current_revision_id='00000000-0000-0000-0000-000000000107'"));
        assertTrue(schema.contains("current_revision_id='00000000-0000-0000-0000-000000000108'"));
        assertTrue(schema.contains("\"theme\":\"MEDICAL_GREEN\""));
        assertTrue(schema.contains("'DELETED'"));
        assertTrue(schema.contains("ON CONFLICT DO NOTHING"));
    }
}

package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AsrRawResponseTest {
    @Test void retainsPayloadForAuditWithoutCredentialsOrSignedQuery() throws Exception {
        var mapper = new ObjectMapper();
        var source = mapper.readTree("""
                {"token":"secret","result_url":"https://example.test/result?signature=secret",
                 "utterances":[{"text":"头痛","start_ms":0,"end_ms":100}]}
                """);

        String saved = AsrRawResponse.sanitizedJson(source);

        assertFalse(saved.contains("secret"));
        assertTrue(saved.contains("https://example.test/result"));
        assertTrue(saved.contains("头痛"));
        assertEquals(64, AsrRawResponse.sha256(saved).length());
    }
}

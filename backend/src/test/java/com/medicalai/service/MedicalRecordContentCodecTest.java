package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.MedicalRecordContent;
import org.junit.jupiter.api.Test;

class MedicalRecordContentCodecTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mergesPartialEditsWithoutDiscardingSourceFields() throws Exception {
        MedicalRecordContent content = MedicalRecordContentCodec.parse(objectMapper,
                "{\"name\":\"张三\",\"age\":52,\"chief\":\"原始主诉\",\"doctor\":\"王医生\"}",
                "{\"chief\":\"修改后主诉\"}");

        assertEquals("张三", content.name());
        assertEquals(52, content.age());
        assertEquals("修改后主诉", content.chief());
        assertEquals("王医生", content.doctor());
    }

    @Test
    void preservesCurrentNullAndEmptyValueSemantics() throws Exception {
        MedicalRecordContent content = MedicalRecordContentCodec.parse(objectMapper,
                "{\"name\":\"张三\",\"age\":\"not-a-number\"}", "{\"name\":null,\"phone\":\"\"}");

        assertEquals("", content.name());
        assertEquals("", content.phone());
        assertNull(content.age());
    }

    @Test
    void acceptsMissingOrEmptyContent() throws Exception {
        MedicalRecordContent content = MedicalRecordContentCodec.parse(objectMapper, null, "");

        assertEquals("", content.name());
        assertNull(content.age());
    }
}

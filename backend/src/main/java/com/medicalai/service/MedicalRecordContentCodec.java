package com.medicalai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.MedicalRecordContent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts persisted record JSON into the same effective content used by the clinical workbench. */
public final class MedicalRecordContentCodec {
    private MedicalRecordContentCodec() {}

    public static MedicalRecordContent parse(ObjectMapper objectMapper, String contentJson,
                                             String editedContentJson) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        merge(values, objectMapper, contentJson);
        merge(values, objectMapper, editedContentJson);
        return new MedicalRecordContent(
                string(values, "name"), string(values, "gender"), integer(values.get("age")),
                string(values, "phone"), string(values, "chief"), string(values, "present"),
                string(values, "past"), string(values, "opinion"), string(values, "medication"),
                string(values, "followup"), string(values, "doctor"), string(values, "date"));
    }

    private static void merge(Map<String, Object> target, ObjectMapper objectMapper, String json) throws IOException {
        if (json == null || json.isBlank()) return;
        target.putAll(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

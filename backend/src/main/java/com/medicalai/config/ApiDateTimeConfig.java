package com.medicalai.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiDateTimeConfig {
    private static final ZoneId API_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter API_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(API_TIME_ZONE);

    /** Keep API timestamps human-readable while persistence continues to use instants. */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer apiDateTimeCustomizer() {
        return builder -> builder.serializerByType(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(API_DATE_TIME_FORMAT.format(value));
            }
        });
    }
}

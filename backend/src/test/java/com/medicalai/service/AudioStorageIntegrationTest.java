package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

/** Opt-in live verification; creates and deletes only a unique test recording. */
@EnabledIfEnvironmentVariable(named = "RUN_MINIO_INTEGRATION_TEST", matches = "true")
class AudioStorageIntegrationTest {
    @TempDir java.nio.file.Path localRoot;

    @Test
    void uploadReadAndDownloadUsingOnlyApiEndpoint() throws Exception {
        var env = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        String endpoint = env.getRequiredProperty("medicalai.minio.api-endpoint");
        var storage = new AudioStorageService(endpoint,
                env.getRequiredProperty("medicalai.minio.access-key"),
                env.getRequiredProperty("medicalai.minio.secret-key"),
                env.getRequiredProperty("medicalai.minio.bucket-name"),
                env.getRequiredProperty("medicalai.minio.folder-prefix"),
                env.getProperty("medicalai.minio.presign-expiry-seconds", Integer.class, 900),
                localRoot.toString());
        UUID visit = UUID.randomUUID();
        // One second of synthetic PCM silence; no patient data is used.
        byte[] audio = ByteBuffer.allocate(32044).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(32036)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(32000).array();
        String key = null;
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
            storage.assertAsrSubmissionReady();
            key = storage.save(new MockMultipartFile("file", "minio-verification.wav", "audio/wav", audio),
                    visit.toString(), UUID.randomUUID().toString());
            assertArrayEquals(audio, storage.load(key).getContentAsByteArray());
            URI url = URI.create(storage.presignedUrl(key));
            assertEquals(URI.create(endpoint).getAuthority(), url.getAuthority());
            assertEquals(URI.create(endpoint).getScheme(), url.getScheme());
            assertTrue(url.getRawQuery().contains("X-Amz-Signature="));
            var response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(audio, response.body());
            System.out.println("MinIO live verification: upload, SDK read, signed HTTP download and content match PASS");
        } finally {
            storage.deleteVisitAssets(visit);
        }
        final String deletedKey = key;
        assertThrows(com.medicalai.exception.BusinessException.class, () -> storage.load(deletedKey));
        System.out.println("MinIO live verification: test recording cleanup PASS");
    }
}

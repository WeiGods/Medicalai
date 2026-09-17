package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import io.minio.errors.ErrorResponseException;
import java.io.IOException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AudioStorageService {
    private final MinioClient client;
    private final String apiEndpoint;
    private final String bucket;
    private final String folderPrefix;
    private final int presignExpirySeconds;
    private final Path localRoot;
    private final boolean credentialsConfigured;
    private final AtomicBoolean bucketInitialized = new AtomicBoolean(false);

    public AudioStorageService(
            @Value("${medicalai.minio.api-endpoint:http://106.55.225.222:9000}") String apiEndpoint,
            @Value("${medicalai.minio.access-key:}") String accessKey,
            @Value("${medicalai.minio.secret-key:}") String secretKey,
            @Value("${medicalai.minio.bucket-name:medicalai}") String bucket,
            @Value("${medicalai.minio.folder-prefix:medicalai}") String folderPrefix,
            @Value("${medicalai.minio.presign-expiry-seconds:900}") int presignExpirySeconds,
            @Value("${medicalai.storage.root:./data/recordings}") String localRoot) {
        this.apiEndpoint = apiEndpoint;
        this.client = buildClient(apiEndpoint, accessKey, secretKey);
        this.bucket = bucket;
        this.folderPrefix = trimSlashes(folderPrefix);
        this.presignExpirySeconds = Math.max(60, Math.min(7 * 24 * 3600, presignExpirySeconds));
        this.localRoot = Path.of(localRoot).toAbsolutePath().normalize();
        this.credentialsConfigured = hasCredentials(accessKey, secretKey);
    }

    public String save(MultipartFile file, String visitId, String recordingId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_AUDIO", "录音文件不能为空");
        }
        String original = file.getOriginalFilename() == null ? "recording" : file.getOriginalFilename();
        String extension = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).replaceAll("[^a-zA-Z0-9]", "")
                : "bin";
        String objectKey = key(visitId + "/" + recordingId + "." + extension);
        try {
            requireCredentials();
            ensureBucket();
            try (var input = file.getInputStream()) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(input, file.getSize(), -1)
                        .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
                        .build());
            }
            return objectKey;
        } catch (Exception e) {
            throw storageFailure("录音文件保存失败", e);
        }
    }

    public Resource load(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", "录音文件不存在");
        }
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
             var output = new java.io.ByteArrayOutputStream()) {
            input.transferTo(output);
            byte[] bytes = output.toByteArray();
            return new ByteArrayResource(bytes) {
                @Override public String getFilename() { return objectKey; }
            };
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", "录音文件不存在", e);
        }
    }

    public String presignedUrl(String objectKey) {
        assertAsrSubmissionReady();
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(objectKey)
                    .expiry(presignExpirySeconds).build());
        } catch (Exception e) {
            throw storageFailure("无法生成录音公网访问地址", e);
        }
    }

    /** 在创建 ASR 任务前校验所需存储配置。 */
    public void assertAsrSubmissionReady() {
        if (!credentialsConfigured) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_CREDENTIALS_MISSING",
                    "未配置 MinIO 访问凭据，无法生成录音访问地址");
        }
    }

    /** 删除指定接诊关联的 MinIO 录音和本地生成的导出文件。 */
    public void deleteVisitAssets(UUID visitId) {
        String prefix = key(visitId + "/");
        try {
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build())) {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(result.get().objectName()).build());
            }
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_DELETE_FAILED", "接诊录音清理失败", e);
        }
        deleteLocalDirectory(localRoot.resolve("exports").resolve(visitId.toString()));
    }

    private void ensureBucket() throws Exception {
        if (bucketInitialized.get()) return;
        synchronized (bucketInitialized) {
            if (bucketInitialized.get()) return;
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            bucketInitialized.set(true);
        }
    }

    private void requireCredentials() {
        if (!credentialsConfigured) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_CREDENTIALS_MISSING",
                    "未配置 MinIO 访问凭据，请设置 MINIO_ACCESS_KEY 和 MINIO_SECRET_KEY");
        }
    }

    private boolean hasCredentials(String accessKey, String secretKey) {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    private BusinessException storageFailure(String message, Exception cause) {
        if (cause instanceof BusinessException business) return business;
        if (cause instanceof ErrorResponseException error) {
            String code = error.errorResponse() == null ? "" : error.errorResponse().code();
            if ("AccessDenied".equalsIgnoreCase(code)
                    || "InvalidAccessKeyId".equalsIgnoreCase(code)
                    || "SignatureDoesNotMatch".equalsIgnoreCase(code)) {
                return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_AUTH_FAILED",
                        "MinIO 认证失败，请检查 MINIO_ACCESS_KEY 和 MINIO_SECRET_KEY", cause);
            }
        }
        if (transportFailure(cause)) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
                    "无法连接 MinIO S3 API（" + apiEndpoint + "）。请确认该地址是 S3 API 端口（通常为 9000），"
                            + "并检查服务器端口映射、安全组和防火墙配置", cause);
        }
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_FAILED", message, cause);
    }

    private boolean transportFailure(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < 8; i++, current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof SocketTimeoutException
                    || current instanceof EOFException) return true;
        }
        return false;
    }

    /**
     * 使应用启动不依赖可选的存储凭据。
     *
     * <p>客户端仍可用于匿名读取；未注入凭据时，写入和预签名操作会返回既有的明确存储错误。
     */
    private MinioClient buildClient(String endpoint, String accessKey, String secretKey) {
        var builder = MinioClient.builder().endpoint(endpoint);
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentials(accessKey, secretKey);
        }
        return builder.build();
    }

    private String key(String value) {
        String clean = value.replace('\\', '/').replaceAll("^/+|/+$", "");
        return folderPrefix.isBlank() ? clean : folderPrefix + "/" + clean;
    }

    private String trimSlashes(String value) {
        return value == null ? "" : value.replaceAll("^/+|/+$", "");
    }

    private void deleteLocalDirectory(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(localRoot) || target.equals(localRoot) || !Files.exists(target)) return;
        try (var paths = Files.walk(target)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { throw new StorageDeleteException(e); }
            });
        } catch (IOException | StorageDeleteException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_DELETE_FAILED", "接诊文件清理失败", e);
        }
    }

    private static final class StorageDeleteException extends RuntimeException {
        private StorageDeleteException(IOException cause) { super(cause); }
    }
}

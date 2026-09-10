package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AudioStorageService {
    private final Path root;

    public AudioStorageService(@Value("${medicalai.storage.root:./data/recordings}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public String save(MultipartFile file, String visitId, String recordingId) {
        try {
            Path dir = root.resolve(visitId).normalize();
            if (!dir.startsWith(root)) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "录音路径无效");
            Files.createDirectories(dir);
            String original = file.getOriginalFilename() == null ? "recording" : file.getOriginalFilename();
            String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1) : "bin";
            Path target = dir.resolve(recordingId + "." + ext.replaceAll("[^a-zA-Z0-9]", ""));
            file.transferTo(target);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_FAILED", "录音文件保存失败");
        }
    }

    public Resource load(String objectKey) {
        try {
            if (objectKey == null || objectKey.isBlank()) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", "录音文件不存在");
            }
            Path path = root.resolve(objectKey).normalize();
            if (!path.startsWith(root) || !Files.exists(path)) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", "录音文件不存在");
            }
            return new FileSystemResource(path);
        } catch (InvalidPathException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "录音路径无效");
        }
    }
}

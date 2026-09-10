package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Recording;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.service.AudioStorageService;
import com.medicalai.service.ClinicalWorkflowService;
import com.medicalai.vo.RecordingVO;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1")
public class RecordingController {
    private static final Logger LOG = LoggerFactory.getLogger(RecordingController.class);
    private final ClinicalWorkflowService workflow;
    private final RecordingMapper recordings;
    private final AudioStorageService storage;

    public RecordingController(ClinicalWorkflowService workflow, RecordingMapper recordings, AudioStorageService storage) {
        this.workflow = workflow;
        this.recordings = recordings;
        this.storage = storage;
    }

    @GetMapping("/visits/{visitId}/recordings")
    public List<RecordingVO> list(@PathVariable UUID visitId,
                                  @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.recordings(visitId, current.doctor().id());
    }

    @PostMapping(value = "/visits/{visitId}/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RecordingVO upload(@PathVariable UUID visitId,
                              @RequestPart("file") MultipartFile file,
                              @RequestParam(value = "duration_ms", required = false) Long durationMs,
                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.upload(visitId, current.doctor().id(), file, durationMs);
    }

    @PostMapping("/visits/{visitId}/recordings/sample")
    public RecordingVO sample(@PathVariable UUID visitId,
                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.createSample(visitId, current.doctor().id());
    }

    @PostMapping("/visits/{visitId}/recordings/transcribe")
    public Object transcribe(@PathVariable UUID visitId,
                             @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.transcribe(visitId, current.doctor().id());
    }

    @GetMapping("/recordings/{recordingId}/audio")
    public ResponseEntity<ByteArrayResource> audio(@PathVariable UUID recordingId,
                                                   @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        Recording recording = recordings.find(recordingId, current.doctor().id()).orElseThrow(BusinessException::notFound);
        if ("SAMPLE".equals(recording.sourceType()) && recording.objectKey() == null) {
            byte[] bytes = sampleWav();
            LOG.info("Audio download served: recordingId={}, sourceType=SAMPLE, mimeType={}, sizeBytes={}",
                    recordingId, "audio/wav", bytes.length);
            return audioResponse(bytes, MediaType.parseMediaType("audio/wav"));
        }
        if (recording.objectKey() == null || recording.objectKey().isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "AUDIO_NOT_FOUND", "录音文件不存在");
        }
        var resource = storage.load(recording.objectKey());
        byte[] bytes = readAll(resource);
        MediaType type = resolveMediaType(recording);
        LOG.info("Audio download served: recordingId={}, sourceType={}, fileName={}, mimeType={}, sizeBytes={}",
                recordingId, recording.sourceType(), recording.fileName(), type, bytes.length);
        return audioResponse(bytes, type);
    }

    private ResponseEntity<ByteArrayResource> audioResponse(byte[] bytes, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new ByteArrayResource(bytes));
    }

    private MediaType resolveMediaType(Recording recording) {
        String mime = recording.mimeType();
        String fileName = recording.fileName() == null ? "" : recording.fileName().toLowerCase(java.util.Locale.ROOT);
        if (mime != null && !mime.isBlank()) {
            try {
                MediaType parsed = MediaType.parseMediaType(mime);
                // Some browsers label MediaRecorder WebM as video/webm; it is
                // still an audio stream and audio/webm is the most compatible
                // response type for HTMLAudioElement.
                if (fileName.endsWith(".webm") && "video".equalsIgnoreCase(parsed.getType())) {
                    return MediaType.parseMediaType("audio/webm");
                }
                // Do not let a generic upload header (for example
                // application/octet-stream) hide the extension based audio
                // type.  HTMLAudioElement needs an audio-compatible response.
                if ("audio".equalsIgnoreCase(parsed.getType())) return parsed;
                if (!MediaType.APPLICATION_OCTET_STREAM.equals(parsed)) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to the extension based fallback below.
            }
        }
        if (fileName.endsWith(".webm")) return MediaType.parseMediaType("audio/webm");
        if (fileName.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (fileName.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (fileName.endsWith(".m4a")) return MediaType.parseMediaType("audio/mp4");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private byte[] readAll(org.springframework.core.io.Resource resource) {
        try (var in = resource.getInputStream(); var out = new java.io.ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new com.medicalai.exception.BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "AUDIO_NOT_FOUND", "录音文件不存在", e);
        }
    }

    private byte[] sampleWav() {
        int rate = 8000;
        int seconds = 2;
        int samples = rate * seconds;
        byte[] data = new byte[44 + samples * 2];
        writeInt(data, 0, 0x46464952);
        writeInt(data, 4, 36 + samples * 2);
        writeInt(data, 8, 0x45564157);
        writeInt(data, 12, 0x20746d66);
        writeInt(data, 16, 16);
        data[20] = 1; data[22] = 1;
        writeInt(data, 24, rate);
        writeInt(data, 28, rate * 2);
        data[32] = 2; data[34] = 16;
        writeInt(data, 40, samples * 2);
        for (int i = 0; i < samples; i++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 9000);
            data[44 + i * 2] = (byte) value;
            data[45 + i * 2] = (byte) (value >>> 8);
        }
        return data;
    }

    private void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}

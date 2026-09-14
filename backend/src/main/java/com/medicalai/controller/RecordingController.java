package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.Recording;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.service.AudioStorageService;
import com.medicalai.service.ClinicalWorkflowService;
import com.medicalai.vo.AsrJobVO;
import com.medicalai.vo.RecordingVO;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class RecordingController {
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

    @PostMapping("/visits/{visitId}/recordings/transcribe")
    public AsrJobVO transcribe(@PathVariable UUID visitId,
                             @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.transcribe(visitId, current.doctor().id());
    }

    @GetMapping("/visits/{visitId}/recordings/transcribe/{jobId}")
    public AsrJobVO transcriptionStatus(@PathVariable UUID visitId, @PathVariable UUID jobId,
                                        @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.asrJob(visitId, current.doctor().id(), jobId);
    }

    @GetMapping("/recordings/{recordingId}/audio")
    public ResponseEntity<ByteArrayResource> audio(@PathVariable UUID recordingId,
                                                   @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        Recording recording = recordings.find(recordingId, current.doctor().id()).orElseThrow(BusinessException::notFound);
        if (recording.objectKey() == null || recording.objectKey().isBlank()) {
            throw new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "AUDIO_NOT_FOUND", "录音文件不存在");
        }
        var resource = storage.load(recording.objectKey());
        byte[] bytes = readAll(resource);
        MediaType type = resolveMediaType(recording);
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

}

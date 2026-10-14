package com.medicalai.controller;

import com.medicalai.domain.AuthenticatedDoctor;
import com.medicalai.domain.DoctorRole;
import com.medicalai.domain.Recording;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.service.AudioStorageService;
import com.medicalai.service.ClinicalWorkflowService;
import com.medicalai.vo.AsrJobVO;
import com.medicalai.vo.AsrPreviewRecordingVO;
import com.medicalai.vo.RecordingVO;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;

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
    public List<RecordingVO> list(@PathVariable("visitId") UUID visitId,
                                  @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.recordings(visitId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
    }

    @GetMapping("/visits/{visitId}/recordings/asr-preview")
    public List<AsrPreviewRecordingVO> asrPreviews(@PathVariable("visitId") UUID visitId,
                                                 @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.asrPreviews(visitId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
    }

    @PostMapping(value = "/visits/{visitId}/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RecordingVO upload(@PathVariable("visitId") UUID visitId,
                              @RequestPart("file") MultipartFile file,
                              @RequestParam(value = "duration_ms", required = false) Long durationMs,
                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        return workflow.upload(visitId, current.doctor().id(), file, durationMs);
    }

    @DeleteMapping("/visits/{visitId}/recordings/{recordingId}")
    public void delete(@PathVariable("visitId") UUID visitId,
                       @PathVariable("recordingId") UUID recordingId,
                       @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        requireWrite(current);
        workflow.deleteRecording(visitId, recordingId, current.doctor().id());
    }

    @PostMapping("/visits/{visitId}/recordings/transcribe")
    public AsrJobVO transcribe(@PathVariable("visitId") UUID visitId,
                             @RequestBody(required = false) TranscribeRequest request,
                             @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        TranscribeRequest body = request == null ? new TranscribeRequest(null, null) : request;
        String provider = asrProvider(body.provider());
        String roleProvider = roleProvider(body.roleProvider());
        requireWrite(current);
        return workflow.transcribe(visitId, current.doctor().id(), provider, roleProvider);
    }

    @PostMapping("/visits/{visitId}/recordings/{recordingId}/retranscribe")
    public AsrJobVO retranscribe(@PathVariable("visitId") UUID visitId,
                                 @PathVariable("recordingId") UUID recordingId,
                                 @RequestBody(required = false) TranscribeRequest request,
                                 @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        TranscribeRequest body = request == null ? new TranscribeRequest(null, null) : request;
        String provider = asrProvider(body.provider());
        String roleProvider = roleProvider(body.roleProvider());
        requireWrite(current);
        return workflow.retranscribeRecording(visitId, recordingId, current.doctor().id(), provider, roleProvider);
    }

    /**
     * 转写请求：{@code provider} 选 ASR 路由，{@code role_provider} 选转写完成后自动角色判断
     * 使用的 LLM 路由；{@code role_provider} 为空表示跟随后端 {@code LLM_ROLE_ROUTE} 的默认值。
     */
    public record TranscribeRequest(String provider, String roleProvider) {
        /** 兼容旧调用方：只指定 ASR 路由。 */
        public TranscribeRequest(String provider) {
            this(provider, null);
        }
    }

    private static final java.util.Set<String> PROVIDERS = java.util.Set.of("DASHSCOPE", "LOCAL");

    private static String asrProvider(String provider) {
        String value = provider == null ? "DASHSCOPE" : provider;
        if (!PROVIDERS.contains(value)) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ASR_PROVIDER", "请选择公网或本地 ASR");
        }
        return value;
    }

    /** 路由可用性由服务层按"能力驱动"校验；这里只挡明显非法的取值。 */
    private static String roleProvider(String roleProvider) {
        if (roleProvider == null || roleProvider.isBlank()) {
            return null;
        }
        String value = roleProvider.strip().toUpperCase(java.util.Locale.ROOT);
        if (!PROVIDERS.contains(value)) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ROLE_PROVIDER",
                    "请选择公网或内网角色识别");
        }
        return value;
    }

    @GetMapping("/visits/{visitId}/recordings/transcribe/{jobId}")
    public AsrJobVO transcriptionStatus(@PathVariable("visitId") UUID visitId, @PathVariable("jobId") UUID jobId,
                                        @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.asrJob(visitId, current.doctor().id(), jobId, DoctorRole.from(current.doctor().role()));
    }

    @GetMapping("/visits/{visitId}/recordings/transcribe/active")
    public AsrJobVO activeTranscriptionStatus(@PathVariable("visitId") UUID visitId,
                                              @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        return workflow.activeAsrJob(visitId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
    }

    @GetMapping("/recordings/{recordingId}/audio")
    public ResponseEntity<ByteArrayResource> audio(@PathVariable("recordingId") UUID recordingId,
                                                   @RequestAttribute("currentDoctor") AuthenticatedDoctor current) {
        Recording recording = workflow.readableRecording(recordingId, current.doctor().id(), DoctorRole.from(current.doctor().role()));
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

    private void requireWrite(AuthenticatedDoctor current) {
        if (current == null || !DoctorRole.from(current.doctor().role()).canWriteClinicalData()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CLINICAL_READ_ONLY", "科室长只能查看接诊数据，不能执行修改操作");
        }
    }

    private MediaType resolveMediaType(Recording recording) {
        String mime = recording.mimeType();
        String fileName = recording.fileName() == null ? "" : recording.fileName().toLowerCase(java.util.Locale.ROOT);
        if (mime != null && !mime.isBlank()) {
            try {
                MediaType parsed = MediaType.parseMediaType(mime);
                // 部分浏览器将 MediaRecorder 的 WebM 标记为 video/webm，但它仍是音频流；
                // audio/webm 是与 HTMLAudioElement 兼容性最好的响应类型。
                if (fileName.endsWith(".webm") && "video".equalsIgnoreCase(parsed.getType())) {
                    return MediaType.parseMediaType("audio/webm");
                }
                // 不能让通用上传请求头（如 application/octet-stream）覆盖基于扩展名判断的音频类型，
                // HTMLAudioElement 需要音频兼容的响应类型。
                if ("audio".equalsIgnoreCase(parsed.getType())) return parsed;
                if (!MediaType.APPLICATION_OCTET_STREAM.equals(parsed)) {
                    return parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // 继续使用下方基于扩展名的兜底判断。
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

package com.medicalai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.*;
import com.medicalai.dto.*;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.*;
import com.medicalai.vo.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ClinicalWorkflowService {
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalWorkflowService.class);
    private static final Set<String> AUDIO_TYPES = Set.of("audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/webm");
    private final VisitMapper visits;
    private final PatientMapper patients;
    private final DoctorMapper doctors;
    private final RecordingMapper recordings;
    private final MedicalRecordMapper records;
    private final AiServiceClient ai;
    private final AudioStorageService storage;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ClinicalWorkflowService(VisitMapper visits, PatientMapper patients, DoctorMapper doctors, RecordingMapper recordings,
                                   MedicalRecordMapper records, AiServiceClient ai, AudioStorageService storage,
                                   ObjectMapper objectMapper, Clock clock) {
        this.visits = visits;
        this.patients = patients;
        this.doctors = doctors;
        this.recordings = recordings;
        this.records = records;
        this.ai = ai;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public List<RecordingVO> recordings(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, false);
        return recordings.list(visit.id()).stream().map(RecordingVO::from).toList();
    }

    @Transactional
    public RecordingVO upload(UUID visitId, UUID doctorId, MultipartFile file, Long durationMs) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (name.isBlank() || !name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|wav|m4a|webm)$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", "仅支持 MP3、WAV、M4A 或 WEBM 录音");
        }
        if (file.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_AUDIO", "录音文件不能为空");
        if (file.getSize() > 200L * 1024 * 1024) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AUDIO_TOO_LARGE", "录音文件不能超过 200MB");
        }
        UUID id = UUID.randomUUID();
        String objectKey = storage.save(file, visit.id().toString(), id.toString());
        Recording recording = recordings.insert(new Recording(id, visit.id(), nextRecordingNo(visit.id()),
                "UPLOAD", objectKey, name, file.getContentType(), file.getSize(),
                durationMs == null || durationMs < 0 ? null : durationMs, "UPLOADED", null, Instant.now(clock)));
        records.audit(doctorId, visit.id(), "RECORDING_UPLOADED", id);
        return RecordingVO.from(recording);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AsrJobVO transcribe(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        Optional<RecordingMapper.AsrJob> active = recordings.latestAsrJob(visit.id())
                .filter(j -> Set.of("PENDING", "RUNNING").contains(j.status()));
        if (active.isPresent()) return asrJob(visitId, doctorId, active.get().id());
        recordings.requeueRetryableRecordings(visit.id());
        // Recover stale PROCESSING rows before validating storage credentials
        // so failed submissions leave recordings in a retryable state.
        storage.assertAsrSubmissionReady();
        List<Recording> pending = recordings.list(visit.id()).stream()
                .filter(r -> "UPLOADED".equals(r.status())).toList();
        if (pending.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "NO_PENDING_RECORDING", "请先上传录音");
        UUID jobId = recordings.createAsrJob(visit.id());
        return asrJob(visitId, doctorId, jobId);
    }

    @Transactional(readOnly = true)
    public AsrJobVO asrJob(UUID visitId, UUID doctorId, UUID jobId) {
        Visit visit = owned(visitId, doctorId, false);
        RecordingMapper.AsrJob job = recordings.asrJob(jobId, visit.id())
                .orElseThrow(BusinessException::notFound);
        List<Recording> all = recordings.list(visit.id());
        int completed = (int) all.stream().filter(r -> "DONE".equals(r.status())).count();
        TranscriptVO result = "SUCCEEDED".equals(job.status()) ? transcript(visitId, doctorId) : null;
        return new AsrJobVO(job.id(), job.status(), all.size(), completed, job.lastError(), result);
    }

    @Transactional(readOnly = true)
    public TranscriptVO transcript(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, false);
        Optional<DialogueSnapshot> snapshot = recordings.latestSnapshot(visit.id());
        if (snapshot.isEmpty()) {
            return new TranscriptVO(null, 0, null, null, "", false, false, List.of());
        }
        List<UtteranceVO> turns = recordings.list(visit.id()).stream()
                .filter(r -> "DONE".equals(r.status()))
                .flatMap(r -> recordings.listByRecording(r.id()).stream())
                .map(UtteranceVO::from).toList();
        Optional<RecordingMapper.TurnState> state = recordings.transcript(visit.id());
        String text = state.map(RecordingMapper.TurnState::transcript).orElse("");
        boolean edited = state.map(RecordingMapper.TurnState::edited).orElse(false);
        boolean dirty = state.isPresent() && !state.get().snapshotId().equals(snapshot.get().id());
        return new TranscriptVO(snapshot.get().id().toString(), snapshot.get().snapshotVersion(),
                snapshot.get().snapshotHash(), snapshot.get().authorityStatus(), text, edited, dirty, turns);
    }

    @Transactional
    public TranscriptVO saveTranscript(UUID visitId, UUID doctorId, SaveTranscriptRequest request) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成转写"));
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        String editedText = request.transcript().strip();
        RecordingMapper.TurnState current = recordings.transcript(visit.id()).orElse(null);
        if (current != null && editedText.equals(current.transcript())) {
            return transcript(visitId, doctorId);
        }
        List<RecordingMapper.Turn> editedTurns = editedTurns(editedText);
        if (editedTurns.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TRANSCRIPT_EMPTY", "转写内容不能为空");
        }
        UUID editedSnapshotId = recordings.createEditedSnapshot(visit.id(), snapshot, editedTurns,
                snapshotHash(visit.id(), editedTurns), doctorId);
        recordings.saveTranscript(visit.id(), editedSnapshotId, editedText, true);
        records.audit(doctorId, visit.id(), "TRANSCRIPT_EDITED", editedSnapshotId);
        return transcript(visitId, doctorId);
    }

    @Transactional
    public MedicalRecordVO generate(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        requireRecordEditable(visit.id());
        DialogueSnapshot snapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成录音转写"));
        if (recordings.list(visit.id()).isEmpty() || recordings.list(visit.id()).stream()
                .anyMatch(recording -> !"DONE".equals(recording.status()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_NOT_TRANSCRIBED", "请先完成全部录音转写");
        }
        RecordingMapper.TurnState state = recordings.transcript(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "TRANSCRIPT_REQUIRED", "请先完成录音转写"));
        if (!snapshot.id().equals(state.snapshotId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SOURCE_CHANGED", "转写已更新，请重新确认当前转写后再生成病历");
        }
        Patient patient = patient(visit);
        AiServiceClient.GenerateResponse response = ai.generate(snapshot.snapshotHash(),
                dialogueForAi(state.transcript()), patientInfo(patient, visit));
        MedicalRecordContent content = contentFromAi(response.record(), patient, visit, doctorName(doctorId));
        validateContent(content);
        UUID recordId = records.findRecordId(visit.id()).orElseGet(() -> records.createRecord(UUID.randomUUID(), visit.id()));
        int versionNo = records.latestVersion(recordId) + 1;
        records.insertVersion(recordId, versionNo, snapshot.id(), snapshot.snapshotHash(),
                json(content), doctorName(doctorId), doctorId);
        records.audit(doctorId, visit.id(), "MEDICAL_RECORD_GENERATED", recordId);
        return medicalRecord(visitId, doctorId);
    }

    @Transactional(readOnly = true)
    public MedicalRecordVO medicalRecord(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, false);
        Optional<MedicalRecordVersion> version = records.currentVersion(visit.id());
        if (version.isEmpty()) {
            return new MedicalRecordVO(null, visit.id(), 0, "DRAFT", "PENDING", null, false, null, null, false);
        }
        MedicalRecordContent content = effectiveContent(version.get());
        Optional<MedicalRecordMapper.ConfirmationRow> confirmation = records.confirmations(visit.id()).stream()
                .filter(c -> c.versionNo() == version.get().versionNo()).findFirst();
        RecordingMapper.TurnState state = recordings.transcript(visit.id()).orElse(null);
        boolean dirty = state != null && (!state.snapshotId().equals(version.get().sourceSnapshotId())
                || (state.edited() && state.updatedAt().isAfter(version.get().createdAt())));
        boolean confirmed = "CONFIRMED".equals(recordStatus(visit.id()))
                && confirmation.isPresent();
        return new MedicalRecordVO(version.get().recordId(), visit.id(), version.get().versionNo(),
                recordStatus(visit.id()), version.get().generationStatus(), toVO(content), confirmed,
                confirmation.map(MedicalRecordMapper.ConfirmationRow::confirmedAt).orElse(null),
                confirmation.map(MedicalRecordMapper.ConfirmationRow::doctorName).orElse(null), dirty);
    }

    @Transactional
    public MedicalRecordVO saveDraft(UUID visitId, UUID doctorId, SaveMedicalRecordRequest request) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
        MedicalRecordContent current = effectiveContent(version);
        MedicalRecordContent merged = merge(current, request);
        validateContent(merged);
        records.saveDraft(version.recordId(), version.versionNo(), json(merged));
        records.audit(doctorId, visit.id(), "MEDICAL_RECORD_DRAFT_SAVED", version.recordId());
        return medicalRecord(visitId, doctorId);
    }

    @Transactional
    public MedicalRecordVO editConfirmed(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        if (!isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_NOT_CONFIRMED", "当前病历尚未确认");
        }
        MedicalRecordContent content = effectiveContent(version);
        int nextVersion = records.latestVersion(version.recordId()) + 1;
        records.insertVersion(version.recordId(), nextVersion, version.sourceSnapshotId(),
                version.sourceSnapshotHash(), json(content), doctorName(doctorId), doctorId);
        records.audit(doctorId, visit.id(), "MEDICAL_RECORD_EDIT_STARTED", version.recordId());
        return medicalRecord(visitId, doctorId);
    }

    @Transactional
    public MedicalRecordVO confirm(UUID visitId, UUID doctorId, ConfirmMedicalRecordRequest request,
                                   String clientIp) {
        Visit visit = owned(visitId, doctorId, true);
        requireActive(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        if (isConfirmed(visit.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "当前病历已确认");
        }
        if (records.confirmationExists(version.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "当前病历版本已确认");
        }
        RecordingMapper.TurnState state = recordings.transcript(visit.id()).orElse(null);
        if (recordings.list(visit.id()).isEmpty() || recordings.list(visit.id()).stream()
                .anyMatch(recording -> !"DONE".equals(recording.status()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORDING_NOT_TRANSCRIBED", "请先完成全部录音转写");
        }
        DialogueSnapshot latestSnapshot = recordings.latestSnapshot(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请先完成录音转写"));
        if (state == null || !latestSnapshot.id().equals(state.snapshotId())
                || !latestSnapshot.id().equals(version.sourceSnapshotId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "SOURCE_CHANGED", "录音或转写已更新，请重新生成病历");
        }
        MedicalRecordContent content = effectiveContent(version);
        List<String> missing = missingFields(content);
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECORD_FIELDS_REQUIRED", "请完善：" + String.join("、", missing));
        }
        UUID confirmationId = records.insertConfirmation(version.recordId(), version.id(), doctorId, clientIp);
        records.confirm(version.recordId(), version.versionNo(), doctorId, confirmationId);
        records.audit(doctorId, visit.id(), "MEDICAL_RECORD_CONFIRMED", version.recordId());
        return medicalRecord(visitId, doctorId);
    }

    @Transactional(readOnly = true)
    public List<ConfirmationVO> confirmations(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, false);
        return records.confirmations(visit.id()).stream()
                .map(c -> new ConfirmationVO(c.id(), c.versionNo(), c.doctorName(), c.confirmedAt())).toList();
    }

    @Transactional
    public List<RecordExportVO> recordExport(UUID visitId, UUID doctorId, ExportMedicalRecordRequest request) {
        Visit visit = owned(visitId, doctorId, true);
        requireExportable(visit);
        MedicalRecordVersion version = records.currentVersion(visit.id())
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "RECORD_REQUIRED", "请先生成病历草稿"));
        MedicalRecordMapper.ConfirmedVersion confirmedVersion = records.currentConfirmedVersion(visit.id()).orElse(null);
        if (confirmedVersion == null || !confirmedVersion.versionId().equals(version.id())) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_NOT_CONFIRMED", "请先确认当前病历版本");
        }
        RecordExport reusable = records.reusableExport(version.recordId(), version.id(),
                confirmedVersion.confirmationId(), request.format()).orElse(null);
        if (reusable != null) {
            return exports(visitId, doctorId);
        }
        RecordExport export = records.insertExport(version.recordId(), version.versionNo(), confirmedVersion.confirmationId(),
                version.id(), request.format(), doctorId);
        records.createExportJob(visit.id(), export.id(), request.format());
        records.audit(doctorId, visit.id(), "MEDICAL_RECORD_EXPORTED", export.id());
        return exports(visitId, doctorId);
    }

    @Transactional(readOnly = true)
    public List<RecordExportVO> exports(UUID visitId, UUID doctorId) {
        Visit visit = owned(visitId, doctorId, false);
        return records.exportsByVisit(visit.id()).stream()
                .map(e -> new RecordExportVO(e.id(), e.versionNo(), e.format(), e.status(), e.doctorName(), e.createdAt()))
                .toList();
    }

    private List<Map<String, Object>> dialogueForAi(String transcript) {
        List<Map<String, Object>> dialogue = new ArrayList<>();
        for (String line : transcript.split("\\R+")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            String role = value.startsWith("医生：") || value.startsWith("医生:") ? "DOCTOR"
                    : value.startsWith("患者：") || value.startsWith("患者:") ? "PATIENT" : "OTHER";
            String text = value.replaceFirst("^(医生|患者|其他人)[：:]\\s*", "");
            dialogue.add(Map.of("role", role, "text", text));
        }
        return dialogue;
    }

    private List<RecordingMapper.Turn> editedTurns(String transcript) {
        List<RecordingMapper.Turn> turns = new ArrayList<>();
        long cursor = 0;
        for (String line : transcript.split("\\R+")) {
            String value = line.strip();
            if (value.isEmpty()) continue;
            String role = value.startsWith("医生：") || value.startsWith("医生:") ? "DOCTOR" : value.startsWith("患者：") || value.startsWith("患者:") ? "PATIENT" : "OTHER";
            String text = value.replaceFirst("^(医生|患者|其他人)[：:]\\s*", "").strip();
            if (text.isEmpty()) continue;
            long end = cursor + Math.max(500, text.length() * 120L);
            turns.add(new RecordingMapper.Turn(role, text, cursor, end));
            cursor = end + 500;
        }
        return turns;
    }

    private Map<String, Object> patientInfo(Patient patient, Visit visit) {
        return Map.of("name", nullSafe(patient.name()), "gender", nullSafe(patient.gender()),
                "age", patient.birthDate() == null ? "" : Period.between(patient.birthDate(), LocalDate.now(clock)).getYears(),
                "phone", nullSafe(patient.phoneMasked()), "visitNo", nullSafe(visit.visitNo()));
    }

    private MedicalRecordContent contentFromAi(Map<String, Object> generated, Patient patient,
                                               Visit visit, String doctorName) {
        String today = LocalDate.now(clock).toString();
        return new MedicalRecordContent(
                patient.name(), patient.gender(),
                patient.birthDate() == null ? null : Period.between(patient.birthDate(), LocalDate.now(clock)).getYears(),
                patient.phoneMasked(), string(generated, "chief"), string(generated, "present"),
                string(generated, "past"), string(generated, "opinion"), string(generated, "medication"),
                string(generated, "followup"), doctorName, today);
    }

    private MedicalRecordContent effectiveContent(MedicalRecordVersion version) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            if (version.contentJson() != null && !version.contentJson().isBlank()) {
                map.putAll(objectMapper.readValue(version.contentJson(), new TypeReference<Map<String, Object>>() {}));
            }
            if (version.editedContentJson() != null && !version.editedContentJson().isBlank()) {
                map.putAll(objectMapper.readValue(version.editedContentJson(), new TypeReference<Map<String, Object>>() {}));
            }
            return new MedicalRecordContent(
                    string(map.get("name")), string(map.get("gender")), integer(map.get("age")),
                    string(map.get("phone")), string(map.get("chief")), string(map.get("present")),
                    string(map.get("past")), string(map.get("opinion")), string(map.get("medication")),
                    string(map.get("followup")), string(map.get("doctor")), string(map.get("date")));
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORD_PARSE_FAILED", "病历内容读取失败");
        }
    }

    private MedicalRecordContent merge(MedicalRecordContent current, SaveMedicalRecordRequest request) {
        return new MedicalRecordContent(
                request.name() == null ? current.name() : request.name(),
                request.gender() == null ? current.gender() : request.gender(),
                request.age() == null ? current.age() : request.age(),
                request.phone() == null ? current.phone() : request.phone(),
                request.chief() == null ? current.chief() : request.chief(),
                request.present() == null ? current.present() : request.present(),
                request.past() == null ? current.past() : request.past(),
                request.opinion() == null ? current.opinion() : request.opinion(),
                request.medication() == null ? current.medication() : request.medication(),
                request.followup() == null ? current.followup() : request.followup(),
                request.doctor() == null ? current.doctor() : request.doctor(),
                request.date() == null ? current.date() : request.date());
    }

    private void validateContent(MedicalRecordContent content) {
        List<String> missing = missingFields(content);
        if (!missing.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECORD_FIELDS_REQUIRED", "请完善：" + String.join("、", missing));
        }
    }

    private List<String> missingFields(MedicalRecordContent content) {
        List<String> missing = new ArrayList<>();
        if (blank(content.name())) missing.add("患者姓名");
        if (blank(content.gender())) missing.add("性别");
        if (content.age() == null || content.age() < 0 || content.age() > 150) missing.add("有效年龄");
        if (blank(content.phone())) missing.add("联系方式");
        if (blank(content.chief())) missing.add("患者主诉");
        if (blank(content.doctor())) missing.add("接诊医生");
        if (blank(content.date())) missing.add("接诊日期");
        return missing;
    }

    private MedicalRecordContentVO toVO(MedicalRecordContent c) {
        return new MedicalRecordContentVO(c.name(), c.gender(), c.age(), c.phone(), c.chief(), c.present(),
                c.past(), c.opinion(), c.medication(), c.followup(), c.doctor(), c.date());
    }

    private String recordStatus(UUID visitId) {
        return records.status(visitId);
    }

    private boolean isConfirmed(UUID visitId) {
        return "CONFIRMED".equals(recordStatus(visitId));
    }

    private Visit owned(UUID visitId, UUID doctorId, boolean lock) {
        return visits.find(visitId, doctorId, lock).orElseThrow(BusinessException::notFound);
    }

    private void requireActive(Visit visit) {
        if (!"ACTIVE".equals(visit.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VISIT_NOT_ACTIVE", "请先开始本次接诊");
        }
    }

    private void requireExportable(Visit visit) {
        if (!Set.of("ACTIVE", "COMPLETED").contains(visit.status())) {
            throw new BusinessException(HttpStatus.CONFLICT, "VISIT_NOT_EXPORTABLE", "当前接诊状态不允许导出病历");
        }
    }

    private void requireRecordEditable(UUID visitId) {
        if (records.findRecordId(visitId).isPresent() && isConfirmed(visitId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "RECORD_CONFIRMED", "病历已确认，请先进入修改状态");
        }
    }

    private Patient patient(Visit visit) {
        return patients.findById(visit.patientId()).orElseThrow(BusinessException::notFound);
    }

    private String doctorName(UUID doctorId) {
        return doctors.findById(doctorId).map(Doctor::displayName).orElse(doctorId.toString());
    }

    private String nextRecordingNo(UUID visitId) {
        int no = recordings.count(visitId) + 1;
        return String.format("REC-%03d", no);
    }

    private String snapshotHash(UUID visitId, List<RecordingMapper.Turn> turns) {
        try {
            String source = visitId + "|" + turns.stream().map(t -> t.role() + ":" + t.text()).reduce((a, b) -> a + "\n" + b).orElse("");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "HASH_FAILED", "快照生成失败");
        }
    }

    private Integer integer(Object value) {
        try { return value == null || String.valueOf(value).isBlank() ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private String string(Map<String, Object> map, String key) {
        return map == null || map.get(key) == null ? "" : String.valueOf(map.get(key));
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "RECORD_SERIALIZE_FAILED", "病历内容保存失败"); }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String nullSafe(String value) { return value == null ? "" : value; }
}

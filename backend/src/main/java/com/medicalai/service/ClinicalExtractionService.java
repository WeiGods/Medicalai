package com.medicalai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalai.domain.DialogueSnapshot;
import com.medicalai.exception.BusinessException;
import com.medicalai.mapper.ClinicalExtractionMapper;
import com.medicalai.mapper.MedicalRecordMapper;
import com.medicalai.mapper.RecordingMapper;
import com.medicalai.vo.ClinicalEvidenceVO;
import com.medicalai.vo.ClinicalExtractionVO;
import com.medicalai.vo.ClinicalFactVO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从已采用的对话快照提取可追溯的问诊事实。
 *
 * <p>本服务不诊断、不补全。模型输出只是待确认草稿，只有证据校验和医生整体确认均完成后，
 * 才能作为病历生成来源。
 */
@Service
public class ClinicalExtractionService {
    private static final Logger LOG = LoggerFactory.getLogger(ClinicalExtractionService.class);
    private static final List<String> FIELD_ORDER = List.of(
            "chief_complaint", "onset_course", "symptom_characteristics", "associated_symptoms",
            "past_medical_history", "medication_history", "allergy_history", "family_history",
            "social_history", "doctor_diagnosis", "doctor_medication", "doctor_followup");
    private static final Set<String> PATIENT_FIELDS = Set.of(
            "chief_complaint", "onset_course", "symptom_characteristics", "associated_symptoms",
            "past_medical_history", "medication_history", "allergy_history", "family_history", "social_history");
    private static final Set<String> DOCTOR_FIELDS = Set.of(
            "doctor_diagnosis", "doctor_medication", "doctor_followup");
    private static final int MIN_FACT_CONFIDENCE = 80;
    private static final long MAX_TURN_DURATION_MS = 45_000;
    private static final int MAX_TURN_CHARACTERS = 400;

    private final ClinicalExtractionMapper extractions;
    private final RecordingMapper recordings;
    private final MedicalRecordMapper records;
    private final ClinicalExtractionRouter clients;
    private final LlmRouteResolver routes;
    private final ObjectMapper objectMapper;
    private final int roleReviewThreshold;

    public ClinicalExtractionService(ClinicalExtractionMapper extractions, RecordingMapper recordings,
                                     MedicalRecordMapper records, ClinicalExtractionRouter clients,
                                     LlmRouteResolver routes, ObjectMapper objectMapper,
                                     @Value("${medicalai.dashscope.role-review-threshold:70}") int roleReviewThreshold) {
        this.extractions = extractions;
        this.recordings = recordings;
        this.records = records;
        this.clients = clients;
        this.routes = routes;
        this.objectMapper = objectMapper;
        this.roleReviewThreshold = roleReviewThreshold;
    }

    @Transactional(readOnly = true)
    public ClinicalExtractionVO current(UUID visitId, DialogueSnapshot snapshot) {
        LlmRouting routing = routes.routing(snapshot);
        return extractions.current(visitId)
                .map(version -> toVO(version, snapshot, routing))
                .orElseGet(() -> empty(snapshot, routing));
    }

    @Transactional
    public ClinicalExtractionVO generate(UUID visitId, UUID doctorId, DialogueSnapshot snapshot) {
        return generate(visitId, doctorId, snapshot, routes.resolve(snapshot, null));
    }

    @Transactional
    public ClinicalExtractionVO generate(UUID visitId, UUID doctorId, DialogueSnapshot snapshot, LlmRoute route) {
        long startedAt = System.nanoTime();
        LlmRouting routing = routes.routing(snapshot);
        LOG.info("信息提取开始: visitId={}, snapshotHash={}, route={}", visitId, shortHash(snapshot.snapshotHash()), route);
        List<SnapshotTurn> turns;
        try {
            turns = snapshotTurns(snapshot);
        } catch (BusinessException error) {
            LOG.warn("信息提取因快照无效失败: visitId={}, snapshotHash={}, route={}, code={}, elapsedMs={}",
                    visitId, shortHash(snapshot.snapshotHash()), route, error.code(), elapsedMs(startedAt));
            return saveFailure(visitId, doctorId, snapshot, routing, route, List.of(error.getMessage()));
        }

        List<String> issues = qualityIssues(turns);
        if (!issues.isEmpty()) {
            // 质量门禁不调用 LLM，避免将角色或时间信息不可信的医疗对话发送到模型。
            LOG.warn("信息提取被质量门禁拦截: visitId={}, snapshotHash={}, route={}, turns={}, issues={}, elapsedMs={}",
                    visitId, shortHash(snapshot.snapshotHash()), route, turns.size(), issues.size(), elapsedMs(startedAt));
            return saveFailure(visitId, doctorId, snapshot, routing, route, issues);
        }

        try {
            LOG.info("信息提取 LLM 请求: visitId={}, snapshotHash={}, route={}, turns={}, patientTurns={}, doctorTurns={}, chars={}",
                    visitId, shortHash(snapshot.snapshotHash()), route, turns.size(), countTurns(turns, "PATIENT"),
                    countTurns(turns, "DOCTOR"), turnCharacters(turns));
            AiServiceClient.ExtractionResponse response = clients.extract(route, snapshot.snapshotHash(), aiTurns(turns));
            if (response == null || !"SUCCEEDED".equals(response.status())
                    || !Objects.equals(snapshot.snapshotHash(), response.sourceSnapshotHash())) {
                LOG.warn("信息提取 LLM 返回无效: visitId={}, snapshotHash={}, route={}, status={}, elapsedMs={}",
                        visitId, shortHash(snapshot.snapshotHash()), route, response == null ? null : response.status(),
                        elapsedMs(startedAt));
                return saveFailure(visitId, doctorId, snapshot, routing, route,
                        List.of("提取服务返回的快照标识无效，请重试。"));
            }
            Map<String, ClinicalFactVO> fields = validateFields(response.extraction(), turns);
            requireChiefComplaint(fields);
            ClinicalExtractionMapper.Version saved = extractions.insert(visitId, snapshot.id(), snapshot.snapshotHash(),
                    "GENERATED", json(fields), "[]", route.name(),
                    response.model() == null ? "clinical-extraction" : response.model(), doctorId);
            records.audit(doctorId, visitId, "CLINICAL_EXTRACTION_GENERATED_" + route.name(), saved.id());
            LOG.info("信息提取完成: visitId={}, snapshotHash={}, route={}, model={}, version={}, fields={}, evidence={}, elapsedMs={}",
                    visitId, shortHash(snapshot.snapshotHash()), route, response.model(), saved.versionNo(),
                    populatedFields(fields), evidenceCount(fields), elapsedMs(startedAt));
            return toVO(saved, snapshot, routing);
        } catch (BusinessException error) {
            // 模型不可用同样保存为 FAILED，前端才能展示明确原因并允许医生原地重试；绝不改走模板兜底。
            LOG.warn("信息提取失败: visitId={}, snapshotHash={}, route={}, code={}, elapsedMs={}",
                    visitId, shortHash(snapshot.snapshotHash()), route, error.code(), elapsedMs(startedAt));
            return saveFailure(visitId, doctorId, snapshot, routing, route, List.of(error.getMessage()));
        } catch (Exception error) {
            LOG.error("信息提取发生未预期错误: visitId={}, snapshotHash={}, route={}, exception={}, elapsedMs={}",
                    visitId, shortHash(snapshot.snapshotHash()), route, error.getClass().getSimpleName(), elapsedMs(startedAt), error);
            return saveFailure(visitId, doctorId, snapshot, routing, route,
                    List.of("提取结果校验失败，请修改转写后重试。"));
        }
    }

    @Transactional
    public ClinicalExtractionVO confirm(UUID visitId, UUID doctorId, DialogueSnapshot snapshot) {
        ClinicalExtractionMapper.Version current = extractions.current(visitId)
                .orElseThrow(() -> BusinessException.conflict("CLINICAL_EXTRACTION_REQUIRED", "请先生成信息提取结果"));
        if (!current.sourceSnapshotId().equals(snapshot.id()) || !current.sourceSnapshotHash().equals(snapshot.snapshotHash())) {
            throw BusinessException.conflict("CLINICAL_EXTRACTION_STALE", "转写已更新，请重新生成信息提取结果");
        }
        Map<String, ClinicalFactVO> fields = fields(current.contentJson());
        if (!hasChiefComplaint(fields)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CLINICAL_EXTRACTION_CHIEF_REQUIRED", "提取结果缺少可追溯的患者主诉，请先核对全文转写");
        }
        ClinicalExtractionMapper.Version confirmed = extractions.confirm(visitId, snapshot.id(), snapshot.snapshotHash(), doctorId)
                .orElseThrow(() -> BusinessException.conflict("CLINICAL_EXTRACTION_NOT_CONFIRMABLE", "当前提取结果不可确认，请重新生成"));
        records.audit(doctorId, visitId, "CLINICAL_EXTRACTION_CONFIRMED", confirmed.id());
        return toVO(confirmed, snapshot, routes.routing(snapshot));
    }

    /** 病历生成前的硬门禁：快照哈希必须完全一致，不能复用旧提取。 */
    @Transactional(readOnly = true)
    public Map<String, ClinicalFactVO> requireConfirmedFields(UUID visitId, DialogueSnapshot snapshot) {
        ClinicalExtractionMapper.Version confirmed = extractions.confirmedForSnapshot(
                visitId, snapshot.id(), snapshot.snapshotHash()).orElseThrow(() ->
                BusinessException.conflict("CLINICAL_EXTRACTION_REQUIRED", "请先生成并整体确认当前转写的信息提取结果"));
        Map<String, ClinicalFactVO> fields = fields(confirmed.contentJson());
        if (!hasChiefComplaint(fields)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CLINICAL_EXTRACTION_CHIEF_REQUIRED", "提取结果缺少患者主诉，请先核对全文转写");
        }
        return fields;
    }

    @Transactional
    public void invalidate(UUID visitId) {
        // 快照一旦变化，原证据引用可能已指向错误文本，必须立即失效而非静默复用。
        extractions.markCurrentStale(visitId);
    }

    private ClinicalExtractionVO saveFailure(UUID visitId, UUID doctorId, DialogueSnapshot snapshot,
                                            LlmRouting routing, LlmRoute route, List<String> issues) {
        ClinicalExtractionMapper.Version saved = extractions.insert(visitId, snapshot.id(), snapshot.snapshotHash(),
                "FAILED", "{}", json(issues), route.name(), "clinical-extraction", doctorId);
        records.audit(doctorId, visitId, "CLINICAL_EXTRACTION_FAILED_" + route.name(), saved.id());
        return toVO(saved, snapshot, routing);
    }

    private List<SnapshotTurn> snapshotTurns(DialogueSnapshot snapshot) {
        String raw = recordings.snapshotTurnsJson(snapshot.id()).orElseThrow(() ->
                new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_TURNS_REQUIRED", "当前对话快照缺少句段数据"));
        try {
            JsonNode nodes = objectMapper.readTree(raw);
            if (!nodes.isArray() || nodes.isEmpty()) {
                throw new IllegalArgumentException("snapshot turns must be a non-empty array");
            }
            List<SnapshotTurn> turns = new ArrayList<>();
            for (int index = 0; index < nodes.size(); index++) {
                JsonNode node = nodes.get(index);
                String role = node.path("role").asText("").strip().toUpperCase(Locale.ROOT);
                String text = node.path("text").asText("");
                Long start = number(node, "startMs", "start_ms");
                Long end = number(node, "endMs", "end_ms");
                String source = text(node, "role_source", "roleSource").toUpperCase(Locale.ROOT);
                Integer confidence = nullableInteger(node, "role_confidence", "roleConfidence");
                turns.add(new SnapshotTurn(index, role, text, start, end,
                        source.isBlank() ? "UNKNOWN" : source, confidence));
            }
            return turns;
        } catch (Exception error) {
            throw new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_TURNS_INVALID", "当前对话快照句段格式无效，请重新保存转写", error);
        }
    }

    private List<String> qualityIssues(List<SnapshotTurn> turns) {
        List<String> issues = new ArrayList<>();
        for (SnapshotTurn turn : turns) {
            String prefix = "第 " + (turn.index() + 1) + " 段：";
            if (blank(turn.text())) issues.add(prefix + "转写文本为空。");
            if (turn.startMs() == null || turn.endMs() == null || turn.startMs() < 0 || turn.endMs() < turn.startMs()) {
                issues.add(prefix + "时间戳缺失或无效。");
            } else if (turn.endMs() - turn.startMs() > MAX_TURN_DURATION_MS
                    || turn.text().codePointCount(0, turn.text().length()) > MAX_TURN_CHARACTERS) {
                issues.add(prefix + "句段过长，请在全文编辑中按说话人拆分后保存。");
            }
            if ("OTHER".equals(turn.role()) && !"MANUAL".equals(turn.roleSource())) {
                issues.add(prefix + "角色为未人工确认的其他人，不能作为医疗事实来源。");
            } else if (!"MANUAL".equals(turn.roleSource())
                    && (!Set.of("DOCTOR", "PATIENT").contains(turn.role())
                    || !"LLM".equals(turn.roleSource())
                    || turn.roleConfidence() == null || turn.roleConfidence() < roleReviewThreshold)) {
                issues.add(prefix + "说话角色尚未完成可信核对。");
            }
        }
        return issues;
    }

    private List<Map<String, Object>> aiTurns(List<SnapshotTurn> turns) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SnapshotTurn turn : turns) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", turn.index());
            item.put("role", turn.role());
            item.put("text", turn.text());
            item.put("start_ms", turn.startMs());
            item.put("end_ms", turn.endMs());
            result.add(item);
        }
        return result;
    }

    private Map<String, ClinicalFactVO> validateFields(Map<String, Object> response, List<SnapshotTurn> turns) {
        if (response == null || !response.keySet().equals(Set.of("fields"))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACTION_INVALID_RESPONSE", "提取模型返回了非标准字段");
        }
        Object rawFields = response == null ? null : response.get("fields");
        if (!(rawFields instanceof Map<?, ?> untyped) || !untyped.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                .equals(new LinkedHashSet<>(FIELD_ORDER))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACTION_INVALID_RESPONSE", "提取模型未返回完整的标准字段");
        }
        Map<String, ClinicalFactVO> fields = new LinkedHashMap<>();
        for (String key : FIELD_ORDER) {
            try {
                fields.put(key, validateField(key, untyped.get(key), turns));
            } catch (BusinessException error) {
                if ("chief_complaint".equals(key)) throw error;
                // 可选字段的无效证据绝不能进入病历，但也不应让一个模型编造字段阻断其余已验证事实。
                // 统一置空后，医生看到的是“未提取到”，而不是未经原文支持的临床结论。
                LOG.warn("信息提取丢弃可选字段: field={}, reason={}", key, error.code());
                fields.put(key, new ClinicalFactVO(null, null, List.of()));
            }
        }
        return fields;
    }

    private ClinicalFactVO validateField(String key, Object raw, List<SnapshotTurn> turns) {
        if (raw == null) return new ClinicalFactVO(null, null, List.of());
        if (!(raw instanceof Map<?, ?> item)
                || !item.keySet().equals(Set.of("value", "confidence", "evidence"))) {
            throw invalidField(key);
        }
        String value = string(item.get("value"));
        Integer confidence = integer(item.get("confidence"));
        if (value == null || value.isBlank() || confidence == null || confidence < MIN_FACT_CONFIDENCE || confidence > 100
                || !(item.get("evidence") instanceof List<?> evidenceItems) || evidenceItems.isEmpty()) {
            throw invalidField(key);
        }
        List<ClinicalEvidenceVO> evidence = new ArrayList<>();
        for (Object rawEvidence : evidenceItems) {
            if (!(rawEvidence instanceof Map<?, ?> evidenceItem)
                    || !evidenceItem.keySet().equals(Set.of("turn_index", "quote"))) throw invalidField(key);
            Integer index = integer(evidenceItem.get("turn_index"));
            String quote = string(evidenceItem.get("quote"));
            if (index == null || index < 0 || index >= turns.size() || quote == null || quote.isBlank()) throw invalidField(key);
            SnapshotTurn turn = turns.get(index);
            String expectedRole = PATIENT_FIELDS.contains(key) ? "PATIENT" : "DOCTOR";
            // 引用必须逐字存在于当前快照的指定句段中，防止模型以概括内容伪造证据。
            if (!expectedRole.equals(turn.role()) || !turn.text().contains(quote)) throw invalidField(key);
            evidence.add(new ClinicalEvidenceVO(index, turn.startMs(), turn.endMs(), turn.role(), quote));
        }
        return new ClinicalFactVO(value.strip(), confidence, List.copyOf(evidence));
    }

    /**
     * 当前病历链路必须以患者主诉为起点；全空或缺少主诉的模型结果即使 JSON 合法也没有业务价值。
     * 将其保存为 FAILED 而不是 GENERATED，既不会让医生误以为可以整体确认，也可以立即重新提取。
     */
    private void requireChiefComplaint(Map<String, ClinicalFactVO> fields) {
        ClinicalFactVO chief = fields.get("chief_complaint");
        if (chief == null || blank(chief.value()) || chief.evidence() == null || chief.evidence().isEmpty()) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACTION_CHIEF_REQUIRED",
                    "信息提取未找到带原文证据的患者主诉，请重新生成或核对转写中的患者症状描述");
        }
    }

    private ClinicalExtractionVO toVO(ClinicalExtractionMapper.Version version, DialogueSnapshot currentSnapshot,
                                       LlmRouting routing) {
        boolean matchesCurrent = version.sourceSnapshotId().equals(currentSnapshot.id())
                && version.sourceSnapshotHash().equals(currentSnapshot.snapshotHash());
        if (!matchesCurrent) {
            // 不向当前页面回显旧快照证据，防止医生误把过期引用当作当前转写的事实。
            return new ClinicalExtractionVO(version.extractionId().toString(), version.versionNo(), "STALE",
                    currentSnapshot.id().toString(), currentSnapshot.snapshotHash(), Map.of(),
                    List.of("当前转写快照已更新，请重新生成信息提取结果。"), version.createdAt(), null,
                    version.providerRoute(), routing.sourceRoute().name(), routeNames(routing), routing.selectionRequired());
        }
        Map<String, ClinicalFactVO> fields = fields(version.contentJson());
        List<String> qualityIssues = issues(version.qualityIssuesJson());
        if ("GENERATED".equals(version.status()) && !hasChiefComplaint(fields)) {
            // 兼容已经写入的旧全空版本：不回写历史审计数据，但对外明确为失败，允许直接重新生成。
            return new ClinicalExtractionVO(version.extractionId().toString(), version.versionNo(), "FAILED",
                    currentSnapshot.id().toString(), currentSnapshot.snapshotHash(), fields,
                    appendIssue(qualityIssues, "当前提取缺少带原文证据的患者主诉，不能整体确认，请重新生成。"),
                    version.createdAt(), null, version.providerRoute(), routing.sourceRoute().name(), routeNames(routing),
                    routing.selectionRequired());
        }
        return new ClinicalExtractionVO(version.extractionId().toString(), version.versionNo(), version.status(),
                currentSnapshot.id().toString(), currentSnapshot.snapshotHash(), fields,
                qualityIssues, version.createdAt(), version.confirmedAt(), version.providerRoute(),
                routing.sourceRoute().name(), routeNames(routing), routing.selectionRequired());
    }

    private boolean hasChiefComplaint(Map<String, ClinicalFactVO> fields) {
        ClinicalFactVO chief = fields.get("chief_complaint");
        return chief != null && !blank(chief.value()) && chief.evidence() != null && !chief.evidence().isEmpty();
    }

    private List<String> appendIssue(List<String> issues, String issue) {
        if (issues.contains(issue)) return issues;
        List<String> result = new ArrayList<>(issues);
        result.add(issue);
        return List.copyOf(result);
    }

    private ClinicalExtractionVO empty(DialogueSnapshot snapshot, LlmRouting routing) {
        return new ClinicalExtractionVO(null, 0, "PENDING", snapshot.id().toString(), snapshot.snapshotHash(),
                Map.of(), List.of(), null, null, null, routing.sourceRoute().name(), routeNames(routing),
                routing.selectionRequired());
    }

    private List<String> routeNames(LlmRouting routing) {
        return routing.availableRoutes().stream().map(Enum::name).toList();
    }

    private Map<String, ClinicalFactVO> fields(String source) {
        try {
            if (blank(source) || "{}".equals(source.strip())) return Map.of();
            return objectMapper.readValue(source, new TypeReference<LinkedHashMap<String, ClinicalFactVO>>() {});
        } catch (Exception error) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "CLINICAL_EXTRACTION_PARSE_FAILED", "信息提取结果读取失败", error);
        }
    }

    private List<String> issues(String source) {
        try {
            if (blank(source)) return List.of();
            return objectMapper.readValue(source, new TypeReference<List<String>>() {});
        } catch (Exception error) {
            return List.of("提取结果的质量说明读取失败。");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "CLINICAL_EXTRACTION_SERIALIZE_FAILED", "信息提取结果保存失败", error);
        }
    }

    private Long number(JsonNode node, String primary, String alternate) {
        JsonNode value = node.has(primary) ? node.get(primary) : node.get(alternate);
        if (value == null || value.isNull() || !value.canConvertToLong()) return null;
        return value.longValue();
    }

    private String text(JsonNode node, String primary, String alternate) {
        JsonNode value = node.has(primary) ? node.get(primary) : node.get(alternate);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }

    private Integer nullableInteger(JsonNode node, String primary, String alternate) {
        JsonNode value = node.has(primary) ? node.get(primary) : node.get(alternate);
        return value == null || value.isNull() || !value.canConvertToInt() ? null : value.intValue();
    }

    private BusinessException invalidField(String key) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACTION_INVALID_RESPONSE", "提取字段“" + key + "”缺少有效原文证据");
    }

    private Integer integer(Object value) {
        if (!(value instanceof Number number) || Math.floor(number.doubleValue()) != number.doubleValue()
                || number.doubleValue() < Integer.MIN_VALUE || number.doubleValue() > Integer.MAX_VALUE) return null;
        return number.intValue();
    }

    private int countTurns(List<SnapshotTurn> turns, String role) {
        return (int) turns.stream().filter(turn -> role.equals(turn.role())).count();
    }

    private int turnCharacters(List<SnapshotTurn> turns) {
        return turns.stream().mapToInt(turn -> turn.text().codePointCount(0, turn.text().length())).sum();
    }

    private int populatedFields(Map<String, ClinicalFactVO> fields) {
        return (int) fields.values().stream().filter(fact -> fact != null && !blank(fact.value())).count();
    }

    private int evidenceCount(Map<String, ClinicalFactVO> fields) {
        return fields.values().stream().filter(Objects::nonNull)
                .map(ClinicalFactVO::evidence).filter(Objects::nonNull).mapToInt(List::size).sum();
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String shortHash(String value) {
        if (blank(value)) return "";
        return value.substring(0, Math.min(12, value.length()));
    }

    private String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record SnapshotTurn(int index, String role, String text, Long startMs, Long endMs,
                                String roleSource, Integer roleConfidence) {}
}

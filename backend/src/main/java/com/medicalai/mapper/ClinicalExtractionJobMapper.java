package com.medicalai.mapper;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 持久化提取任务；同一接诊、快照和路由只保留一个任务，页面离开后仍可执行。 */
@Repository
public class ClinicalExtractionJobMapper {
    private static final RowMapper<Job> JOB = (rs, n) -> new Job(rs.getObject("id", UUID.class),
            rs.getObject("visit_id", UUID.class), rs.getString("source_snapshot_hash"),
            rs.getString("provider_route"), rs.getString("status"), rs.getString("last_error"));
    private final JdbcTemplate jdbc;

    public ClinicalExtractionJobMapper(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 在保存正式转写的同一事务中入队。历史数据只允许推断唯一且已知的来源环境。 */
    public void enqueueSnapshot(UUID visitId, UUID snapshotId) {
        // AUTO/UNKNOWN/FALLBACK 表示角色 LLM 尚未完成，不能提前把不完整快照交给信息提取。
        // 这里读取不可变快照而非可变 utterance，确保重试与审计使用同一份输入。
        Integer pendingRoles = jdbc.queryForObject("""
                SELECT count(*) FROM jsonb_array_elements(
                    (SELECT turns_json FROM dialogue_snapshot WHERE id=? AND visit_id=?)) turn
                WHERE upper(COALESCE(turn->>'role_source','UNKNOWN')) IN ('AUTO','UNKNOWN','FALLBACK')
                """, Integer.class, snapshotId, visitId);
        if (pendingRoles != null && pendingRoles > 0) return;
        var routes = jdbc.query("""
                SELECT COALESCE(v.ai_route, (
                    SELECT CASE WHEN count(DISTINCT CASE r.asr_route
                        WHEN 'LOCAL' THEN 'LOCAL' WHEN 'DASHSCOPE' THEN 'DASHSCOPE'
                        WHEN 'DASHSCOPE_ROLE_V2' THEN 'DASHSCOPE' ELSE 'UNKNOWN' END)=1
                        THEN min(CASE r.asr_route WHEN 'LOCAL' THEN 'LOCAL'
                            WHEN 'DASHSCOPE' THEN 'DASHSCOPE'
                            WHEN 'DASHSCOPE_ROLE_V2' THEN 'DASHSCOPE' ELSE 'UNKNOWN' END) END
                    FROM dialogue_snapshot_source src
                    JOIN asr_utterance u ON u.id=src.utterance_id
                    JOIN recording r ON r.id=u.recording_id WHERE src.snapshot_id=?))
                FROM visit v WHERE v.id=? AND v.status='ACTIVE'
                """, (rs, n) -> rs.getString(1), snapshotId, visitId);
        String route = routes.isEmpty() ? null : routes.getFirst();
        if ("LOCAL".equals(route) || "DASHSCOPE".equals(route)) enqueue(visitId, snapshotId, route);
    }

    @Transactional
    public void enqueue(UUID visitId, UUID snapshotId, String route) {
        jdbc.update("""
                INSERT INTO ai_job(id,job_type,visit_id,source_snapshot_hash,idempotency_key,status,provider_route)
                SELECT ?, 'CLINICAL_EXTRACT', s.visit_id,s.snapshot_hash,?, 'PENDING',?
                FROM dialogue_snapshot s JOIN visit v ON v.id=s.visit_id
                JOIN visit_transcript t ON t.visit_id=s.visit_id AND t.snapshot_id=s.id
                WHERE s.id=? AND s.visit_id=? AND s.authority_status='ADOPTED' AND v.status='ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM medical_record m WHERE m.visit_id=v.id AND m.status='CONFIRMED')
                ON CONFLICT (idempotency_key) DO UPDATE SET status='PENDING',last_error=NULL,
                    lease_token=NULL,locked_at=NULL,finished_at=NULL
                WHERE ai_job.status IN ('FAILED','SUCCEEDED','CANCELLED')
                """, UUID.randomUUID(), "clinical-extraction:" + visitId + ":" + snapshotId + ":" + route,
                route, snapshotId, visitId);
    }

    public Optional<Job> current(UUID visitId, String hash) {
        return jdbc.query("""
                SELECT * FROM ai_job WHERE visit_id=? AND source_snapshot_hash=? AND job_type='CLINICAL_EXTRACT'
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, JOB, visitId, hash).stream().findFirst();
    }

    @Transactional
    public Optional<Job> claim(String route, UUID token) {
        // 不自动重放可能仍在执行的模型调用；服务中断后展示失败，允许医生明确重试。
        jdbc.update("""
                UPDATE ai_job SET status='FAILED',last_error='信息提取已中断，请重试。',finished_at=medicalai_local_clock()
                WHERE job_type='CLINICAL_EXTRACT' AND provider_route=? AND status='RUNNING'
                  AND locked_at < medicalai_local_clock() - interval '90 seconds'
                """, route);
        jdbc.update("""
                UPDATE ai_job j SET status='CANCELLED',finished_at=medicalai_local_clock()
                WHERE j.job_type='CLINICAL_EXTRACT' AND j.provider_route=? AND j.status='PENDING'
                  AND NOT EXISTS (SELECT 1 FROM visit v JOIN visit_transcript t ON t.visit_id=v.id
                      JOIN dialogue_snapshot s ON s.id=t.snapshot_id
                      WHERE v.id=j.visit_id AND v.status='ACTIVE' AND s.authority_status='ADOPTED'
                        AND s.snapshot_hash=j.source_snapshot_hash
                        AND NOT EXISTS (SELECT 1 FROM medical_record m WHERE m.visit_id=v.id AND m.status='CONFIRMED'))
                """, route);
        return jdbc.query("""
                UPDATE ai_job j SET status='RUNNING',lease_token=?,locked_at=medicalai_local_clock(),
                    started_at=medicalai_local_clock(),attempt_count=attempt_count+1
                WHERE j.id=(SELECT q.id FROM ai_job q
                    WHERE q.job_type='CLINICAL_EXTRACT' AND q.provider_route=? AND q.status='PENDING'
                      AND NOT EXISTS (SELECT 1 FROM recording r WHERE r.visit_id=q.visit_id AND r.status<>'DONE')
                      AND NOT EXISTS (SELECT 1 FROM ai_job a WHERE a.visit_id=q.visit_id
                          AND a.job_type='ASR_TRANSCRIBE' AND a.status IN ('PENDING','RUNNING'))
                      AND NOT EXISTS (SELECT 1 FROM recording_session s WHERE s.visit_id=q.visit_id AND s.status IN ('OPEN','STOPPING'))
                    ORDER BY q.created_at,q.id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING j.*
                """, JOB, token, route).stream().findFirst();
    }

    public boolean renew(UUID id, UUID token) {
        return jdbc.update("""
                UPDATE ai_job SET locked_at=medicalai_local_clock()
                WHERE id=? AND job_type='CLINICAL_EXTRACT' AND status='RUNNING' AND lease_token=?
                """, id, token) == 1;
    }

    public void finish(UUID id, UUID token, String status, String error) {
        jdbc.update("""
                UPDATE ai_job SET status=?,last_error=?,finished_at=medicalai_local_clock(),lease_token=NULL
                WHERE id=? AND job_type='CLINICAL_EXTRACT' AND status='RUNNING' AND lease_token=?
                """, status, error == null ? null : error.substring(0, Math.min(1000, error.length())), id, token);
    }

    public record Job(UUID id, UUID visitId, String snapshotHash, String route, String status, String error) {}
}

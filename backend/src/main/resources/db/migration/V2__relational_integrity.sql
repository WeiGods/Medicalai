-- Keep V1 immutable: follow-up constraints work for existing and fresh databases.
ALTER TABLE visit
    ADD COLUMN patient_phone_snapshot varchar(64),
    ADD COLUMN doctor_name_snapshot varchar(128),
    ADD COLUMN created_by uuid REFERENCES doctor(id),
    ADD COLUMN updated_by uuid REFERENCES doctor(id);

CREATE UNIQUE INDEX uq_session_token ON login_session(access_token_hash);
CREATE INDEX ix_login_doctor_expiry ON login_session(doctor_id,expires_at);
CREATE INDEX ix_visit_doctor_date ON visit(doctor_id,visit_date DESC);
CREATE INDEX ix_visit_patient ON visit(patient_id);
CREATE INDEX ix_recording_visit ON recording(visit_id);
CREATE INDEX ix_ai_job_pending ON ai_job(status,created_at);

ALTER TABLE doctor ADD CONSTRAINT ck_doctor_status CHECK(status IN ('ACTIVE','DISABLED'));
ALTER TABLE doctor ADD CONSTRAINT ck_doctor_role CHECK(role IN ('DOCTOR','ADMIN'));
ALTER TABLE visit ADD CONSTRAINT ck_visit_status CHECK(status IN ('WAITING','ACTIVE','COMPLETED','CANCELLED','ARCHIVED'));
ALTER TABLE visit ADD CONSTRAINT ck_visit_age CHECK(patient_age_snapshot BETWEEN 0 AND 150);
ALTER TABLE recording ADD CONSTRAINT uq_recording_visit UNIQUE(id,visit_id);
ALTER TABLE recording_session ADD CONSTRAINT fk_session_recording_scope
    FOREIGN KEY(recording_id,visit_id) REFERENCES recording(id,visit_id);
ALTER TABLE recording_session ADD CONSTRAINT ck_stream_epoch CHECK(stream_epoch>0);
ALTER TABLE recording_session ADD CONSTRAINT ck_session_status CHECK(status IN ('OPEN','STOPPING','STOPPED','FAILED'));
ALTER TABLE recording_session ADD CONSTRAINT uq_session_scope UNIQUE(id,recording_id,visit_id);
ALTER TABLE asr_utterance ADD CONSTRAINT fk_utterance_scope
    FOREIGN KEY(session_id,recording_id,visit_id) REFERENCES recording_session(id,recording_id,visit_id);
ALTER TABLE asr_utterance ADD CONSTRAINT ck_revision CHECK(revision>=0);
ALTER TABLE asr_utterance ADD CONSTRAINT ck_result_type CHECK(result_type IN ('PREVIEW','CANONICAL'));
ALTER TABLE asr_utterance ADD CONSTRAINT ck_current_canonical CHECK(NOT is_current OR result_type='CANONICAL');
ALTER TABLE asr_utterance ADD CONSTRAINT ck_audio_range CHECK(start_ms>=0 AND end_ms>=start_ms);

-- Utterance identifiers are scoped to a recording; two patients may use the same engine ID.
ALTER TABLE asr_utterance DROP CONSTRAINT asr_utterance_utterance_id_revision_result_type_key;
ALTER TABLE asr_utterance ADD CONSTRAINT uq_utterance_revision
    UNIQUE(recording_id,utterance_id,revision,result_type);
DROP INDEX uq_current_canonical_utterance;
CREATE UNIQUE INDEX uq_current_canonical_utterance ON asr_utterance(recording_id,utterance_id)
    WHERE result_type='CANONICAL' AND is_current=true;

-- Multiple recordings/edits produce new visit snapshots, never overwrite a former snapshot.
ALTER TABLE dialogue_snapshot DROP CONSTRAINT dialogue_snapshot_visit_id_key;
ALTER TABLE dialogue_snapshot ADD CONSTRAINT uq_dialogue_version UNIQUE(visit_id,snapshot_version);
ALTER TABLE dialogue_snapshot ADD CONSTRAINT fk_dialogue_scope
    FOREIGN KEY(session_id,recording_id,visit_id) REFERENCES recording_session(id,recording_id,visit_id);
CREATE TABLE dialogue_snapshot_source (
    snapshot_id uuid NOT NULL REFERENCES dialogue_snapshot(id),
    utterance_id uuid NOT NULL REFERENCES asr_utterance(id),
    PRIMARY KEY(snapshot_id,utterance_id)
);

ALTER TABLE medical_record ADD CONSTRAINT uq_record_visit UNIQUE(id,visit_id);
ALTER TABLE visit ADD CONSTRAINT fk_current_record
    FOREIGN KEY(current_record_id,id) REFERENCES medical_record(id,visit_id);
ALTER TABLE medical_record_version ADD CONSTRAINT uq_record_version_id UNIQUE(id,record_id);
ALTER TABLE medical_record ADD CONSTRAINT fk_current_version
    FOREIGN KEY(id,current_version) REFERENCES medical_record_version(record_id,version_no) DEFERRABLE INITIALLY DEFERRED;
-- Empty record objects have no current version until the first draft succeeds.
ALTER TABLE medical_record ALTER COLUMN current_version DROP NOT NULL;
ALTER TABLE medical_record ALTER COLUMN current_version DROP DEFAULT;
UPDATE medical_record SET current_version=NULL WHERE current_version=0;
ALTER TABLE medical_record_confirmation ADD CONSTRAINT fk_confirmation_version
    FOREIGN KEY(version_id,record_id) REFERENCES medical_record_version(id,record_id);
CREATE UNIQUE INDEX uq_confirmation_version ON medical_record_confirmation(version_id);
ALTER TABLE medical_record_confirmation ADD CONSTRAINT ck_confirm_declaration CHECK(declaration=true);
ALTER TABLE medical_record ADD CONSTRAINT ck_confirmed_version CHECK(
    status<>'CONFIRMED' OR (confirmed_version=current_version AND confirmed_version IS NOT NULL
                           AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL));
ALTER TABLE ai_job ADD COLUMN locked_at timestamptz;
ALTER TABLE ai_job ADD COLUMN lease_token uuid;

CREATE TABLE record_export (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id uuid NOT NULL REFERENCES medical_record(id),
    version_id uuid NOT NULL,
    confirmation_id uuid NOT NULL REFERENCES medical_record_confirmation(id),
    format varchar(8) NOT NULL CHECK(format IN ('DOCX','PDF')),
    status varchar(16) NOT NULL CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    object_key varchar(512),
    created_by uuid NOT NULL REFERENCES doctor(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(version_id,record_id) REFERENCES medical_record_version(id,record_id)
);
CREATE TABLE audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id uuid REFERENCES doctor(id),
    visit_id uuid REFERENCES visit(id),
    action varchar(128) NOT NULL,
    resource_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_visit_time ON audit_log(visit_id,created_at);
COMMENT ON TABLE doctor IS '医生本地身份：以外部系统及其用户ID映射；姓名不是主键';
COMMENT ON TABLE login_session IS '本系统登录会话，仅存随机访问令牌SHA-256，不存OA凭据';
COMMENT ON TABLE patient IS '患者主数据镜像：外部来源及患者编号唯一';
COMMENT ON TABLE visit IS '一次接诊；医生患者信息在创建时快照，与主数据更新隔离';
COMMENT ON TABLE recording IS '接诊的一段录音，文件本体存对象存储';
COMMENT ON TABLE recording_session IS '录音流会话；与登录会话不同';
COMMENT ON TABLE asr_utterance IS '转写修订；PREVIEW禁止作为正式对话输入';
COMMENT ON TABLE dialogue_snapshot IS '不可变对话快照；同一接诊可因追加录音产生新版本';
COMMENT ON TABLE medical_record IS '一次接诊一份病历主对象';
COMMENT ON TABLE medical_record_version IS '病历版本及其来源快照';
COMMENT ON TABLE medical_record_confirmation IS '医生确认特定病历版本的历史';
COMMENT ON TABLE ai_job IS '持久化异步任务和幂等键';
COMMENT ON TABLE record_export IS '绑定确认版本的导出记录';

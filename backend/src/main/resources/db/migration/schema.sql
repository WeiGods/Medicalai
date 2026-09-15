-- MedicalAI 单文件数据库初始化脚本（PostgreSQL）
-- Spring Boot 每次启动都会执行本文件；所有 DDL 均按幂等方式编写。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS doctor (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    external_system varchar(32) NOT NULL,
    external_user_id varchar(128) NOT NULL,
    employee_no varchar(64),
    display_name varchar(128) NOT NULL,
    department_id varchar(128),
    department_name varchar(128),
    role varchar(32) NOT NULL DEFAULT 'DOCTOR' CHECK (role IN ('DOCTOR','ADMIN')),
    status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
    last_login_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_doctor_external_identity UNIQUE (external_system, external_user_id)
);

CREATE TABLE IF NOT EXISTS login_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id uuid NOT NULL REFERENCES doctor(id),
    login_type varchar(32) NOT NULL,
    access_token_hash varchar(256) NOT NULL,
    expires_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    client_info jsonb NOT NULL DEFAULT '{}'::jsonb,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_session_token UNIQUE (access_token_hash)
);

CREATE TABLE IF NOT EXISTS patient (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system varchar(32) NOT NULL,
    source_patient_id varchar(128) NOT NULL,
    patient_no varchar(64),
    name varchar(128) NOT NULL,
    gender varchar(16),
    birth_date date,
    phone_masked varchar(64),
    phone_ciphertext text,
    id_no_hash varchar(128),
    id_no_masked varchar(64),
    department_id varchar(128),
    department_name varchar(128),
    source_updated_at timestamptz,
    status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    raw_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_patient_source_identity UNIQUE (source_system, source_patient_id)
);

CREATE TABLE IF NOT EXISTS visit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_no varchar(64) NOT NULL UNIQUE,
    patient_id uuid NOT NULL REFERENCES patient(id),
    doctor_id uuid NOT NULL REFERENCES doctor(id),
    department_id varchar(128),
    department_name varchar(128),
    visit_date date NOT NULL DEFAULT current_date,
    status varchar(32) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','ACTIVE','COMPLETED','CANCELLED','ARCHIVED')),
    chief_complaint text,
    patient_name_snapshot varchar(128),
    patient_gender_snapshot varchar(16),
    patient_age_snapshot int CHECK (patient_age_snapshot BETWEEN 0 AND 150),
    patient_phone_snapshot varchar(64),
    doctor_name_snapshot varchar(128),
    started_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    archive_reason varchar(256),
    version int NOT NULL DEFAULT 0,
    created_by uuid REFERENCES doctor(id),
    updated_by uuid REFERENCES doctor(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS recording (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id uuid NOT NULL REFERENCES visit(id),
    recording_no varchar(64) NOT NULL,
    source_type varchar(32) NOT NULL,
    object_key varchar(512),
    file_name varchar(256),
    mime_type varchar(128),
    size_bytes bigint,
    duration_ms bigint,
    sha256 varchar(128),
    status varchar(32) NOT NULL DEFAULT 'UPLOADED',
    asr_route varchar(32),
    error_code varchar(64),
    error_message varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_recording_no UNIQUE (visit_id, recording_no),
    CONSTRAINT uq_recording_visit_scope UNIQUE (id, visit_id)
);

CREATE TABLE IF NOT EXISTS recording_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id uuid NOT NULL REFERENCES visit(id),
    recording_id uuid NOT NULL REFERENCES recording(id),
    stream_epoch bigint NOT NULL DEFAULT 1 CHECK (stream_epoch > 0),
    recording_fencing_token varchar(128) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','STOPPING','STOPPED','FAILED')),
    last_sequence bigint NOT NULL DEFAULT -1,
    last_event_id varchar(128),
    started_at timestamptz NOT NULL DEFAULT now(),
    stopped_at timestamptz,
    stop_operation_id varchar(128) UNIQUE,
    reconnect_count int NOT NULL DEFAULT 0,
    CONSTRAINT uq_recording_session_scope UNIQUE (id, recording_id, visit_id),
    CONSTRAINT fk_session_recording_scope FOREIGN KEY (recording_id, visit_id) REFERENCES recording(id, visit_id)
);

CREATE TABLE IF NOT EXISTS asr_utterance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id uuid NOT NULL REFERENCES visit(id),
    recording_id uuid NOT NULL REFERENCES recording(id),
    session_id uuid NOT NULL REFERENCES recording_session(id),
    utterance_id varchar(128) NOT NULL,
    revision int NOT NULL DEFAULT 0 CHECK (revision >= 0),
    result_type varchar(32) NOT NULL CHECK (result_type IN ('PREVIEW','CANONICAL')),
    text text NOT NULL,
    role varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    speaker_id int,
    role_source varchar(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (role_source IN ('AUTO','MANUAL','UNKNOWN')),
    anonymous_speaker_epoch int NOT NULL DEFAULT 1,
    start_ms bigint CHECK (start_ms >= 0),
    end_ms bigint CHECK (end_ms >= start_ms),
    is_current boolean NOT NULL DEFAULT false CHECK (NOT is_current OR result_type = 'CANONICAL'),
    source_event_id varchar(128),
    ignored_reason varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_utterance_revision UNIQUE (recording_id, utterance_id, revision, result_type),
    CONSTRAINT uq_utterance_recording_scope UNIQUE (id, recording_id, visit_id),
    CONSTRAINT fk_utterance_scope FOREIGN KEY (session_id, recording_id, visit_id)
        REFERENCES recording_session(id, recording_id, visit_id)
);

CREATE TABLE IF NOT EXISTS dialogue_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id uuid NOT NULL REFERENCES visit(id),
    recording_id uuid NOT NULL REFERENCES recording(id),
    session_id uuid NOT NULL REFERENCES recording_session(id),
    snapshot_version int NOT NULL DEFAULT 1,
    snapshot_hash varchar(128) NOT NULL UNIQUE,
    authority_status varchar(64) NOT NULL,
    turns_json jsonb NOT NULL,
    created_by uuid REFERENCES doctor(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dialogue_version UNIQUE (visit_id, snapshot_version),
    CONSTRAINT fk_dialogue_scope FOREIGN KEY (session_id, recording_id, visit_id)
        REFERENCES recording_session(id, recording_id, visit_id)
);

CREATE TABLE IF NOT EXISTS dialogue_snapshot_source (
    snapshot_id uuid NOT NULL REFERENCES dialogue_snapshot(id),
    utterance_id uuid NOT NULL REFERENCES asr_utterance(id),
    PRIMARY KEY (snapshot_id, utterance_id)
);

CREATE TABLE IF NOT EXISTS medical_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id uuid NOT NULL UNIQUE REFERENCES visit(id),
    current_version int,
    status varchar(32) NOT NULL DEFAULT 'DRAFT',
    confirmed_version int,
    confirmed_at timestamptz,
    confirmed_by uuid REFERENCES doctor(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_record_visit_scope UNIQUE (id, visit_id),
    CONSTRAINT ck_confirmed_version CHECK (
        status <> 'CONFIRMED' OR (confirmed_version = current_version
                                   AND confirmed_version IS NOT NULL
                                   AND confirmed_by IS NOT NULL
                                   AND confirmed_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS medical_record_version (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id uuid NOT NULL REFERENCES medical_record(id),
    version_no int NOT NULL,
    source_snapshot_id uuid NOT NULL REFERENCES dialogue_snapshot(id),
    source_snapshot_hash varchar(128) NOT NULL,
    content_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    edited_content_json jsonb,
    generation_status varchar(32) NOT NULL DEFAULT 'PENDING',
    generated_by varchar(128),
    created_by uuid REFERENCES doctor(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_record_version UNIQUE (record_id, version_no),
    CONSTRAINT uq_record_version_scope UNIQUE (id, record_id)
);

CREATE TABLE IF NOT EXISTS medical_record_confirmation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id uuid NOT NULL REFERENCES medical_record(id),
    version_id uuid NOT NULL REFERENCES medical_record_version(id),
    doctor_id uuid NOT NULL REFERENCES doctor(id),
    declaration boolean NOT NULL CHECK (declaration = true),
    confirmed_at timestamptz NOT NULL DEFAULT now(),
    client_ip varchar(64),
    CONSTRAINT fk_confirmation_version FOREIGN KEY (version_id, record_id)
        REFERENCES medical_record_version(id, record_id),
    CONSTRAINT uq_confirmation_version UNIQUE (version_id)
);

CREATE TABLE IF NOT EXISTS ai_job (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type varchar(32) NOT NULL,
    visit_id uuid NOT NULL REFERENCES visit(id),
    source_snapshot_hash varchar(128),
    idempotency_key varchar(256) NOT NULL UNIQUE,
    status varchar(32) NOT NULL DEFAULT 'PENDING',
    attempt_count int NOT NULL DEFAULT 0,
    provider_route varchar(32),
    result_ref uuid,
    last_error varchar(1024),
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    locked_at timestamptz,
    lease_token uuid
);

CREATE TABLE IF NOT EXISTS record_export (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id uuid NOT NULL REFERENCES medical_record(id),
    version_id uuid NOT NULL,
    confirmation_id uuid NOT NULL REFERENCES medical_record_confirmation(id),
    format varchar(8) NOT NULL CHECK (format IN ('DOCX','PDF')),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    object_key varchar(512),
    error_message varchar(1024),
    created_by uuid NOT NULL REFERENCES doctor(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_export_version FOREIGN KEY (version_id, record_id)
        REFERENCES medical_record_version(id, record_id)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    doctor_id uuid REFERENCES doctor(id),
    visit_id uuid REFERENCES visit(id),
    action varchar(128) NOT NULL,
    resource_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS visit_transcript (
    visit_id uuid PRIMARY KEY REFERENCES visit(id),
    snapshot_id uuid NOT NULL REFERENCES dialogue_snapshot(id),
    transcript_text text NOT NULL DEFAULT '',
    edited boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 兼容已由旧版迁移创建的数据库：补列、移除旧唯一约束并补齐复合关系。
ALTER TABLE visit ADD COLUMN IF NOT EXISTS patient_phone_snapshot varchar(64);
ALTER TABLE visit ADD COLUMN IF NOT EXISTS doctor_name_snapshot varchar(128);
ALTER TABLE visit ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES doctor(id);
ALTER TABLE visit ADD COLUMN IF NOT EXISTS updated_by uuid REFERENCES doctor(id);
ALTER TABLE visit DROP CONSTRAINT IF EXISTS fk_current_record;
ALTER TABLE visit DROP COLUMN IF EXISTS current_record_id;
ALTER TABLE patient ADD COLUMN IF NOT EXISTS id_no_masked varchar(64);
ALTER TABLE ai_job ADD COLUMN IF NOT EXISTS locked_at timestamptz;
ALTER TABLE ai_job ADD COLUMN IF NOT EXISTS lease_token uuid;
ALTER TABLE ai_job ADD COLUMN IF NOT EXISTS recording_id uuid REFERENCES recording(id);
ALTER TABLE ai_job ADD COLUMN IF NOT EXISTS provider_task_id varchar(256);
CREATE INDEX IF NOT EXISTS ix_ai_job_asr_pending ON ai_job(job_type, status, created_at);
ALTER TABLE record_export ADD COLUMN IF NOT EXISTS error_message varchar(1024);

-- 兼容旧版前端直接写入的伪成功导出：没有真实文件时必须回到可重试的 PENDING。
UPDATE record_export
SET status='PENDING', object_key=NULL, error_message='待后端重新生成导出文件'
WHERE status='SUCCEEDED' AND (object_key IS NULL OR object_key='client-generated');
INSERT INTO ai_job(id,job_type,visit_id,idempotency_key,status,result_ref,provider_route)
SELECT gen_random_uuid(),
       CASE WHEN e.format='DOCX' THEN 'EXPORT_DOCX' ELSE 'EXPORT_PDF' END,
       r.visit_id, 'export:' || e.id, 'PENDING', e.id, 'BACKEND'
FROM record_export e
JOIN medical_record r ON r.id=e.record_id
WHERE e.status='PENDING'
  AND NOT EXISTS (
      SELECT 1 FROM ai_job j
      WHERE j.idempotency_key='export:' || e.id
  )
ON CONFLICT (idempotency_key) DO NOTHING;

ALTER TABLE medical_record ALTER COLUMN current_version DROP NOT NULL;
ALTER TABLE medical_record ALTER COLUMN current_version DROP DEFAULT;
UPDATE medical_record SET current_version = NULL WHERE current_version = 0;
ALTER TABLE medical_record DROP CONSTRAINT IF EXISTS ck_confirmed_version;
ALTER TABLE medical_record ADD CONSTRAINT ck_confirmed_version CHECK (
    status <> 'CONFIRMED' OR (confirmed_version = current_version
                               AND confirmed_version IS NOT NULL
                               AND confirmed_by IS NOT NULL
                               AND confirmed_at IS NOT NULL)
);

ALTER TABLE asr_utterance DROP CONSTRAINT IF EXISTS asr_utterance_utterance_id_revision_result_type_key;
ALTER TABLE asr_utterance ADD COLUMN IF NOT EXISTS speaker_id int;
ALTER TABLE asr_utterance ADD COLUMN IF NOT EXISTS role_source varchar(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE dialogue_snapshot DROP CONSTRAINT IF EXISTS dialogue_snapshot_visit_id_key;
DROP INDEX IF EXISTS uq_current_canonical_utterance;

CREATE UNIQUE INDEX IF NOT EXISTS uq_doctor_active_visit ON visit(doctor_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uq_current_canonical_utterance
    ON asr_utterance(recording_id, utterance_id)
    WHERE result_type = 'CANONICAL' AND is_current = true;
CREATE UNIQUE INDEX IF NOT EXISTS uq_utterance_revision
    ON asr_utterance(recording_id, utterance_id, revision, result_type);
CREATE UNIQUE INDEX IF NOT EXISTS uq_recording_session_scope
    ON recording_session(id, recording_id, visit_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_utterance_recording_scope
    ON asr_utterance(id, recording_id, visit_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_dialogue_version
    ON dialogue_snapshot(visit_id, snapshot_version);
CREATE UNIQUE INDEX IF NOT EXISTS uq_record_visit_scope
    ON medical_record(id, visit_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_record_version
    ON medical_record_version(record_id, version_no);
CREATE UNIQUE INDEX IF NOT EXISTS uq_record_version_scope
    ON medical_record_version(id, record_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_confirmation_version
    ON medical_record_confirmation(version_id);
CREATE INDEX IF NOT EXISTS ix_login_doctor_expiry ON login_session(doctor_id, expires_at);
CREATE INDEX IF NOT EXISTS ix_visit_doctor_date ON visit(doctor_id, visit_date DESC);
CREATE INDEX IF NOT EXISTS ix_visit_patient ON visit(patient_id);
CREATE INDEX IF NOT EXISTS ix_recording_visit ON recording(visit_id);
CREATE INDEX IF NOT EXISTS ix_recording_status ON recording(visit_id, status);
CREATE INDEX IF NOT EXISTS ix_ai_job_pending ON ai_job(status, created_at);
CREATE INDEX IF NOT EXISTS ix_record_version_record ON medical_record_version(record_id, version_no DESC);
CREATE INDEX IF NOT EXISTS ix_record_export_record ON record_export(record_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_visit_time ON audit_log(visit_id, created_at);

-- 当前病历通过 medical_record.visit_id（唯一）和 medical_record.current_version 定位。
-- current_version 的复合外键必须在相关表全部创建后添加；先删除再添加保证幂等。
ALTER TABLE medical_record DROP CONSTRAINT IF EXISTS fk_current_version;
ALTER TABLE medical_record ADD CONSTRAINT fk_current_version
    FOREIGN KEY (id, current_version) REFERENCES medical_record_version(record_id, version_no)
    DEFERRABLE INITIALLY DEFERRED;

-- 说明：旧版数据库中的命名约束由 V1～V4 已经建立；新数据库在 CREATE TABLE 时直接建立。
-- 以下注释覆盖所有表和字段。

COMMENT ON TABLE doctor IS '医生本地身份：以外部系统及外部用户 ID 映射到本系统医生';
COMMENT ON COLUMN doctor.id IS '医生内部 UUID 主键';
COMMENT ON COLUMN doctor.external_system IS '外部身份系统编码，例如 LOCAL_DEMO、OA 或 HIS';
COMMENT ON COLUMN doctor.external_user_id IS '外部系统中的用户唯一标识';
COMMENT ON COLUMN doctor.employee_no IS '医生工号';
COMMENT ON COLUMN doctor.display_name IS '医生展示姓名';
COMMENT ON COLUMN doctor.department_id IS '所属科室外部 ID';
COMMENT ON COLUMN doctor.department_name IS '所属科室名称';
COMMENT ON COLUMN doctor.role IS '系统角色：DOCTOR 医生、ADMIN 管理员';
COMMENT ON COLUMN doctor.status IS '账号状态：ACTIVE 启用、DISABLED 停用';
COMMENT ON COLUMN doctor.last_login_at IS '最近一次成功登录时间';
COMMENT ON COLUMN doctor.metadata IS '外部身份扩展属性 JSON';
COMMENT ON COLUMN doctor.created_at IS '记录创建时间';
COMMENT ON COLUMN doctor.updated_at IS '记录最后更新时间';

COMMENT ON TABLE login_session IS '本系统登录会话，仅保存访问令牌哈希，不保存外部系统凭据';
COMMENT ON COLUMN login_session.id IS '登录会话 UUID 主键';
COMMENT ON COLUMN login_session.doctor_id IS '关联医生 ID';
COMMENT ON COLUMN login_session.login_type IS '登录方式，例如 DEMO、SSO';
COMMENT ON COLUMN login_session.access_token_hash IS '访问令牌的 SHA-256 哈希值';
COMMENT ON COLUMN login_session.expires_at IS '会话过期时间';
COMMENT ON COLUMN login_session.last_seen_at IS '最近一次使用会话的时间';
COMMENT ON COLUMN login_session.client_info IS '客户端信息 JSON，例如浏览器和设备';
COMMENT ON COLUMN login_session.revoked_at IS '会话主动撤销时间，NULL 表示未撤销';
COMMENT ON COLUMN login_session.created_at IS '会话创建时间';

COMMENT ON TABLE patient IS '患者主数据镜像：保存外部来源或手工创建的患者基础信息';
COMMENT ON COLUMN patient.id IS '患者内部 UUID 主键';
COMMENT ON COLUMN patient.source_system IS '患者来源系统编码，例如 MANUAL 或 HIS';
COMMENT ON COLUMN patient.source_patient_id IS '来源系统中的患者唯一 ID';
COMMENT ON COLUMN patient.patient_no IS '患者业务编号/病案号';
COMMENT ON COLUMN patient.name IS '患者姓名';
COMMENT ON COLUMN patient.gender IS '患者性别';
COMMENT ON COLUMN patient.birth_date IS '患者出生日期';
COMMENT ON COLUMN patient.phone_masked IS '脱敏后的联系电话，用于展示和业务传递';
COMMENT ON COLUMN patient.phone_ciphertext IS '加密保存的联系电话，暂不用于展示';
COMMENT ON COLUMN patient.id_no_hash IS '身份证号不可逆哈希，用于安全去重';
COMMENT ON COLUMN patient.id_no_masked IS '脱敏后的身份证号，用于展示';
COMMENT ON COLUMN patient.department_id IS '患者来源科室外部 ID';
COMMENT ON COLUMN patient.department_name IS '患者来源科室名称';
COMMENT ON COLUMN patient.source_updated_at IS '来源系统最后更新时间';
COMMENT ON COLUMN patient.status IS '患者状态：ACTIVE 启用等';
COMMENT ON COLUMN patient.raw_snapshot IS '来源系统原始数据快照 JSON，需按敏感数据策略管理';
COMMENT ON COLUMN patient.created_at IS '记录创建时间';
COMMENT ON COLUMN patient.updated_at IS '记录最后更新时间';

COMMENT ON TABLE visit IS '一次医生对患者的接诊记录，是临床工作流的业务中心';
COMMENT ON COLUMN visit.id IS '接诊 UUID 主键';
COMMENT ON COLUMN visit.visit_no IS '接诊业务编号';
COMMENT ON COLUMN visit.patient_id IS '关联患者 ID';
COMMENT ON COLUMN visit.doctor_id IS '负责接诊的医生 ID';
COMMENT ON COLUMN visit.department_id IS '接诊科室外部 ID';
COMMENT ON COLUMN visit.department_name IS '接诊科室名称快照';
COMMENT ON COLUMN visit.visit_date IS '接诊日期';
COMMENT ON COLUMN visit.status IS '接诊状态：WAITING、ACTIVE、COMPLETED、CANCELLED、ARCHIVED';
COMMENT ON COLUMN visit.chief_complaint IS '就诊主诉（可在创建接诊时填写）';
COMMENT ON COLUMN visit.patient_name_snapshot IS '创建接诊时的患者姓名快照';
COMMENT ON COLUMN visit.patient_gender_snapshot IS '创建接诊时的患者性别快照';
COMMENT ON COLUMN visit.patient_age_snapshot IS '创建接诊时的患者年龄快照';
COMMENT ON COLUMN visit.patient_phone_snapshot IS '创建接诊时的脱敏电话快照';
COMMENT ON COLUMN visit.doctor_name_snapshot IS '创建接诊时的医生姓名快照';
COMMENT ON COLUMN visit.started_at IS '开始接诊时间';
COMMENT ON COLUMN visit.completed_at IS '完成接诊时间';
COMMENT ON COLUMN visit.cancelled_at IS '取消接诊时间';
COMMENT ON COLUMN visit.archive_reason IS '归档原因';
COMMENT ON COLUMN visit.version IS '接诊记录乐观锁版本号，每次状态变更递增';
COMMENT ON COLUMN visit.created_by IS '创建该接诊的医生 ID';
COMMENT ON COLUMN visit.updated_by IS '最近更新该接诊的医生 ID';
COMMENT ON COLUMN visit.created_at IS '接诊记录创建时间';
COMMENT ON COLUMN visit.updated_at IS '接诊记录最后更新时间';

COMMENT ON TABLE recording IS '接诊录音文件元数据；音频本体存储在文件系统或对象存储';
COMMENT ON COLUMN recording.id IS '录音 UUID 主键';
COMMENT ON COLUMN recording.visit_id IS '所属接诊 ID';
COMMENT ON COLUMN recording.recording_no IS '接诊内录音序号';
COMMENT ON COLUMN recording.source_type IS '录音来源：UPLOAD 上传';
COMMENT ON COLUMN recording.object_key IS '文件在对象存储中的键或本地存储路径';
COMMENT ON COLUMN recording.file_name IS '原始文件名';
COMMENT ON COLUMN recording.mime_type IS '文件 MIME 类型';
COMMENT ON COLUMN recording.size_bytes IS '文件大小，单位字节';
COMMENT ON COLUMN recording.duration_ms IS '录音时长，单位毫秒';
COMMENT ON COLUMN recording.sha256 IS '文件内容 SHA-256，用于完整性校验和去重';
COMMENT ON COLUMN recording.status IS '录音状态：UPLOADED、PROCESSING、DONE、FAILED 等';
COMMENT ON COLUMN recording.asr_route IS 'ASR 处理路由，例如 UPLOADED、DASHSCOPE';
COMMENT ON COLUMN recording.error_code IS '录音处理失败错误码';
COMMENT ON COLUMN recording.error_message IS '录音处理失败详情';
COMMENT ON COLUMN recording.created_at IS '录音记录创建时间';
COMMENT ON COLUMN recording.updated_at IS '录音记录最后更新时间';

COMMENT ON TABLE recording_session IS '录音流或录音处理会话，与登录会话不同';
COMMENT ON COLUMN recording_session.id IS '录音会话 UUID 主键';
COMMENT ON COLUMN recording_session.visit_id IS '所属接诊 ID';
COMMENT ON COLUMN recording_session.recording_id IS '所属录音 ID';
COMMENT ON COLUMN recording_session.stream_epoch IS '流世代号，用于区分重连后的新流';
COMMENT ON COLUMN recording_session.recording_fencing_token IS '录音写入栅栏令牌，防止旧会话继续写入';
COMMENT ON COLUMN recording_session.status IS '会话状态：OPEN、STOPPING、STOPPED、FAILED';
COMMENT ON COLUMN recording_session.last_sequence IS '最近处理的音频事件序号';
COMMENT ON COLUMN recording_session.last_event_id IS '最近处理的上游事件 ID';
COMMENT ON COLUMN recording_session.started_at IS '录音会话开始时间';
COMMENT ON COLUMN recording_session.stopped_at IS '录音会话停止时间';
COMMENT ON COLUMN recording_session.stop_operation_id IS '停止操作幂等 ID';
COMMENT ON COLUMN recording_session.reconnect_count IS '断线重连次数';

COMMENT ON TABLE asr_utterance IS 'ASR 转写句段及修订记录，保存原始识别结果的可追溯版本';
COMMENT ON COLUMN asr_utterance.id IS '句段 UUID 主键';
COMMENT ON COLUMN asr_utterance.visit_id IS '所属接诊 ID';
COMMENT ON COLUMN asr_utterance.recording_id IS '来源录音 ID';
COMMENT ON COLUMN asr_utterance.session_id IS '产生该结果的录音会话 ID';
COMMENT ON COLUMN asr_utterance.utterance_id IS 'ASR 引擎句段业务 ID，在录音范围内使用';
COMMENT ON COLUMN asr_utterance.revision IS '同一句段的修订序号，从 0 开始';
COMMENT ON COLUMN asr_utterance.result_type IS '结果类型：PREVIEW 预览、CANONICAL 正式';
COMMENT ON COLUMN asr_utterance.text IS '句段转写文本';
COMMENT ON COLUMN asr_utterance.role IS '说话角色：DOCTOR、PATIENT、UNKNOWN';
COMMENT ON COLUMN asr_utterance.anonymous_speaker_epoch IS '匿名说话人分段世代号';
COMMENT ON COLUMN asr_utterance.start_ms IS '句段开始时间，单位毫秒';
COMMENT ON COLUMN asr_utterance.end_ms IS '句段结束时间，单位毫秒';
COMMENT ON COLUMN asr_utterance.is_current IS '是否为该句段当前正式版本';
COMMENT ON COLUMN asr_utterance.source_event_id IS '产生该句段的上游事件 ID';
COMMENT ON COLUMN asr_utterance.ignored_reason IS '句段被忽略的原因';
COMMENT ON COLUMN asr_utterance.created_at IS '句段记录创建时间';

COMMENT ON TABLE dialogue_snapshot IS '不可变对话快照；同一接诊可有多个版本，病历生成引用其 hash';
COMMENT ON COLUMN dialogue_snapshot.id IS '对话快照 UUID 主键';
COMMENT ON COLUMN dialogue_snapshot.visit_id IS '所属接诊 ID';
COMMENT ON COLUMN dialogue_snapshot.recording_id IS '生成快照时对应的最后一段录音 ID';
COMMENT ON COLUMN dialogue_snapshot.session_id IS '生成快照时对应的录音会话 ID';
COMMENT ON COLUMN dialogue_snapshot.snapshot_version IS '接诊内对话快照版本号';
COMMENT ON COLUMN dialogue_snapshot.snapshot_hash IS '快照内容哈希，用于幂等和完整性校验';
COMMENT ON COLUMN dialogue_snapshot.authority_status IS '快照权威状态，例如 ADOPTED';
COMMENT ON COLUMN dialogue_snapshot.turns_json IS '不可变医患对话句段 JSON';
COMMENT ON COLUMN dialogue_snapshot.created_by IS '创建/采用该快照的医生 ID';
COMMENT ON COLUMN dialogue_snapshot.created_at IS '快照创建时间';

COMMENT ON TABLE dialogue_snapshot_source IS '对话快照与其来源 ASR 句段的多对多映射表';
COMMENT ON COLUMN dialogue_snapshot_source.snapshot_id IS '对话快照 ID';
COMMENT ON COLUMN dialogue_snapshot_source.utterance_id IS '来源 ASR 句段 ID';

COMMENT ON TABLE visit_transcript IS '接诊当前转写文本的可变投影，用于展示和医生编辑，不是病历表';
COMMENT ON COLUMN visit_transcript.visit_id IS '接诊 ID，同时作为该接诊唯一文本投影的主键';
COMMENT ON COLUMN visit_transcript.snapshot_id IS '当前文本对应的对话快照 ID';
COMMENT ON COLUMN visit_transcript.transcript_text IS '当前整段转写文本';
COMMENT ON COLUMN visit_transcript.edited IS '是否被医生手工编辑过';
COMMENT ON COLUMN visit_transcript.created_at IS '文本投影创建时间';
COMMENT ON COLUMN visit_transcript.updated_at IS '文本投影最后更新时间';

COMMENT ON TABLE medical_record IS '一次接诊对应的一份病历主对象，维护状态和当前版本指针';
COMMENT ON COLUMN medical_record.id IS '病历 UUID 主键';
COMMENT ON COLUMN medical_record.visit_id IS '所属接诊 ID，一个接诊唯一一份病历';
COMMENT ON COLUMN medical_record.current_version IS '当前病历版本号，NULL 表示尚无版本';
COMMENT ON COLUMN medical_record.status IS '病历状态：DRAFT、CONFIRMED 等';
COMMENT ON COLUMN medical_record.confirmed_version IS '已确认的病历版本号';
COMMENT ON COLUMN medical_record.confirmed_at IS '病历确认时间';
COMMENT ON COLUMN medical_record.confirmed_by IS '确认病历的医生 ID';
COMMENT ON COLUMN medical_record.created_at IS '病历主对象创建时间';
COMMENT ON COLUMN medical_record.updated_at IS '病历主对象最后更新时间';

COMMENT ON TABLE medical_record_version IS '病历内容版本，保存 AI 原始内容和医生编辑内容';
COMMENT ON COLUMN medical_record_version.id IS '病历版本 UUID 主键';
COMMENT ON COLUMN medical_record_version.record_id IS '所属病历 ID';
COMMENT ON COLUMN medical_record_version.version_no IS '病历在所属病历内的版本号';
COMMENT ON COLUMN medical_record_version.source_snapshot_id IS '生成该版本所依据的对话快照 ID';
COMMENT ON COLUMN medical_record_version.source_snapshot_hash IS '生成时记录的对话快照哈希';
COMMENT ON COLUMN medical_record_version.content_json IS 'AI 生成的原始病历内容 JSON';
COMMENT ON COLUMN medical_record_version.edited_content_json IS '医生编辑后的病历内容 JSON';
COMMENT ON COLUMN medical_record_version.generation_status IS '内容状态：PENDING、SUCCEEDED、EDITED 等';
COMMENT ON COLUMN medical_record_version.generated_by IS '生成来源或模型标识';
COMMENT ON COLUMN medical_record_version.created_by IS '创建该版本的医生 ID';
COMMENT ON COLUMN medical_record_version.created_at IS '病历版本创建时间';

COMMENT ON TABLE medical_record_confirmation IS '医生对具体病历版本的确认历史';
COMMENT ON COLUMN medical_record_confirmation.id IS '确认记录 UUID 主键';
COMMENT ON COLUMN medical_record_confirmation.record_id IS '所属病历 ID';
COMMENT ON COLUMN medical_record_confirmation.version_id IS '被确认的病历版本 ID';
COMMENT ON COLUMN medical_record_confirmation.doctor_id IS '执行确认的医生 ID';
COMMENT ON COLUMN medical_record_confirmation.declaration IS '医生确认声明，当前约束必须为 TRUE';
COMMENT ON COLUMN medical_record_confirmation.confirmed_at IS '确认发生时间';
COMMENT ON COLUMN medical_record_confirmation.client_ip IS '发起确认请求的客户端 IP';

COMMENT ON TABLE ai_job IS '异步 AI/导出任务及其幂等、重试和租约信息';
COMMENT ON COLUMN ai_job.id IS '任务 UUID 主键';
COMMENT ON COLUMN ai_job.job_type IS '任务类型：ASR_TRANSCRIBE、MEDICAL_RECORD_GENERATE、EXPORT_DOCX、EXPORT_PDF 等';
COMMENT ON COLUMN ai_job.visit_id IS '任务所属接诊 ID';
COMMENT ON COLUMN ai_job.source_snapshot_hash IS '任务输入对话快照哈希';
COMMENT ON COLUMN ai_job.idempotency_key IS '客户端请求与业务对象组合生成的幂等键';
COMMENT ON COLUMN ai_job.status IS '任务状态：PENDING、RUNNING、SUCCEEDED、FAILED 等';
COMMENT ON COLUMN ai_job.attempt_count IS '任务已尝试执行次数';
COMMENT ON COLUMN ai_job.provider_route IS '实际使用的 AI/导出服务路由';
COMMENT ON COLUMN ai_job.result_ref IS '任务结果引用 ID';
COMMENT ON COLUMN ai_job.last_error IS '最近一次失败错误信息';
COMMENT ON COLUMN ai_job.started_at IS '任务开始执行时间';
COMMENT ON COLUMN ai_job.finished_at IS '任务完成或失败时间';
COMMENT ON COLUMN ai_job.created_at IS '任务创建时间';
COMMENT ON COLUMN ai_job.locked_at IS '任务被 worker 租约锁定的时间';
COMMENT ON COLUMN ai_job.lease_token IS 'worker 租约令牌';

COMMENT ON TABLE record_export IS '病历导出记录，绑定到具体已确认版本和导出文件';
COMMENT ON COLUMN record_export.id IS '导出记录 UUID 主键';
COMMENT ON COLUMN record_export.record_id IS '所属病历 ID';
COMMENT ON COLUMN record_export.version_id IS '被导出的病历版本 ID';
COMMENT ON COLUMN record_export.confirmation_id IS '对应的病历确认记录 ID';
COMMENT ON COLUMN record_export.format IS '导出格式：DOCX 或 PDF';
COMMENT ON COLUMN record_export.status IS '导出状态：PENDING、RUNNING、SUCCEEDED、FAILED';
COMMENT ON COLUMN record_export.object_key IS '生成文件在对象存储中的键';
COMMENT ON COLUMN record_export.error_message IS '导出失败的错误信息';
COMMENT ON COLUMN record_export.created_by IS '发起导出的医生 ID';
COMMENT ON COLUMN record_export.created_at IS '导出记录创建时间';

COMMENT ON TABLE audit_log IS '业务操作审计日志';
COMMENT ON COLUMN audit_log.id IS '审计日志 UUID 主键';
COMMENT ON COLUMN audit_log.doctor_id IS '执行操作的医生 ID，可为空表示系统操作';
COMMENT ON COLUMN audit_log.visit_id IS '关联接诊 ID';
COMMENT ON COLUMN audit_log.action IS '操作动作编码，例如 RECORDING_UPLOADED、MEDICAL_RECORD_CONFIRMED';
COMMENT ON COLUMN audit_log.resource_id IS '被操作资源 ID';
COMMENT ON COLUMN audit_log.created_at IS '审计事件发生时间';

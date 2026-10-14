export interface Doctor {
  id: string
  display_name: string
  department_name: string
  role: string
  source: string
}

export interface LoginResult {
  access_token: string
  token_type: string
  expires_in: number
  doctor: Doctor
}

export interface Patient {
  id: string
  patient_no: string
  name: string
  gender: string
  age: number | null
  phone_masked: string
  id_no_masked: string
  status: string
  created_by: string | null
  created_by_name: string | null
}

export interface Visit {
  id: string
  visit_no: string
  patient_id: string
  doctor_id: string
  status: 'WAITING' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'ARCHIVED' | string
  department_name: string
  patient_name: string | null
  patient_gender: string | null
  patient_age: number | null
  doctor_name: string | null
  chief_complaint: string | null
  version: number
  created_at: string
  last_activity_at: string
  ai_route?: LlmProvider | null
  ai_route_locked?: boolean
}

export type DemoRole = 'DOCTOR' | 'DEPARTMENT_HEAD'

export interface Recording {
  id: string
  visit_id: string
  recording_no: string
  source_type: 'UPLOAD' | string
  file_name: string | null
  mime_type: string | null
  size_bytes: number | null
  duration_ms: number | null
  status: 'UPLOADED' | 'PROCESSING' | 'DONE' | 'FAILED' | string
  error_message?: string | null
  audio_url: string | null
  created_at: string
}

/**
 * Raw ASR output shown on the audio page while role analysis continues.
 * These records are intentionally separate from canonical transcript turns.
 */
export interface AsrPreviewSegment {
  utterance_id?: string | null
  text: string
  start_ms: number
  end_ms: number
  speaker_id?: number | null
}

export interface AsrPreviewRecording {
  recording_id: string
  recording_no: string | null
  file_name: string | null
  status?: string | null
  error_message?: string | null
  segments: AsrPreviewSegment[]
}

export interface Utterance {
  id?: string
  speaker_id?: number | null
  role: 'DOCTOR' | 'PATIENT' | 'OTHER' | string
  text: string
  start_ms: number
  end_ms: number
  role_source?: 'AUTO' | 'LLM' | 'FALLBACK' | 'MANUAL' | 'UNKNOWN' | string
  role_confidence?: number | null
  role_provider_route?: string | null
  role_review_required?: boolean
}

export type LlmProvider = 'DASHSCOPE' | 'LOCAL'

export type AiRoute = LlmProvider

export interface AiRouteSettings {
  route: AiRoute
  asr_available: boolean
  role_available: boolean
  extraction_available: boolean
  asr_message?: string | null
  role_message?: string | null
  extraction_message?: string | null
  updated_by?: string | null
  updated_at?: string | null
  unavailable_capabilities: string[]
  public_asr_available?: boolean
  public_role_available?: boolean
  public_extraction_available?: boolean
  public_asr_message?: string | null
  public_role_message?: string | null
  public_extraction_message?: string | null
  local_asr_available?: boolean
  local_role_available?: boolean
  local_extraction_available?: boolean
  local_asr_message?: string | null
  local_role_message?: string | null
  local_extraction_message?: string | null
}

export interface Transcript {
  snapshot_id: string | null
  snapshot_version: number
  snapshot_hash: string | null
  authority_status: string | null
  transcript: string
  edited: boolean
  source_dirty: boolean
  turns: Utterance[]
  source_route: 'DASHSCOPE' | 'LOCAL' | 'MIXED' | 'UNKNOWN' | string
  available_routes: LlmProvider[]
  route_selection_required: boolean
  /** 后端默认的角色识别 LLM 路由；未下发时前端跟随后端默认。 */
  default_route?: LlmProvider
}

export interface ClinicalEvidence {
  turn_index: number
  start_ms: number
  end_ms: number
  role: 'DOCTOR' | 'PATIENT' | string
  quote: string
}

export interface ClinicalFact {
  value: string | null
  confidence: number | null
  evidence: ClinicalEvidence[]
}

export interface ClinicalExtraction {
  extraction_id: string | null
  version_no: number
  status: 'PENDING' | 'GENERATED' | 'CONFIRMED' | 'FAILED' | 'STALE' | string
  snapshot_id: string | null
  snapshot_hash: string | null
  fields: Record<string, ClinicalFact>
  quality_issues: string[]
  generated_at: string | null
  confirmed_at: string | null
  provider_route: LlmProvider | null
  source_route: 'DASHSCOPE' | 'LOCAL' | 'MIXED' | 'UNKNOWN' | string
  available_routes: LlmProvider[]
  route_selection_required: boolean
  /** 后端默认的信息提取 LLM 路由；未下发时前端跟随后端默认。 */
  default_route?: LlmProvider
}

export type AsrProvider = 'DASHSCOPE' | 'LOCAL'

export interface AsrJob {
  provider_route: AsrProvider
  job_id: string
  status: string
  total_recordings: number
  completed_recordings: number
  error_message: string | null
  transcript: Transcript | null
}

export interface MedicalRecordContent {
  name: string
  gender: string
  age: number | null
  phone: string
  chief: string
  present: string
  past: string
  opinion: string
  medication: string
  followup: string
  doctor: string
  date: string
}

export interface MedicalRecord {
  record_id: string | null
  visit_id: string
  version_no: number
  status: string
  generation_status: string
  content: MedicalRecordContent | null
  confirmed: boolean
  confirmed_at: string | null
  confirmed_by_name: string | null
  source_dirty: boolean
}

export interface Confirmation {
  id: string
  version_no: number
  doctor_name: string
  confirmed_at: string
}

export interface RecordExport {
  id: string
  version_no: number
  template_id: string | null
  template_revision_id: string | null
  template_name: string
  template_revision_no: number
  format: 'DOCX' | 'PDF' | string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string
  doctor_name: string
  created_at: string
  error_message?: string | null
}

export interface ExportTemplate {
  id: string
  template_key: string
  name: string
  description: string
  status: 'ACTIVE' | 'DISABLED' | string
  default_template: boolean
  current_revision_id: string
  current_revision_no: number
  definition_json?: string | null
  updated_at: string
}

export interface ExportTemplateRevision {
  id: string
  template_id: string
  revision_no: number
  definition_json: string
  created_by: string | null
  created_at: string
}

export type AuditAction = 'LOGIN' | 'VISIT_DETAIL_VIEWED' | 'RECORDING_UPLOADED' | 'RECORDING_DELETED' | 'MEDICAL_RECORD_CONFIRMED' | 'MEDICAL_RECORD_EXPORT' | 'PATIENT_DELETED'

export interface AuditLog {
  id: string
  action: AuditAction | string
  result: 'SUCCESS' | 'FAILED' | string
  operator_name: string | null
  patient_name: string | null
  patient_no: string | null
  visit_no: string | null
  detail: string | null
  client_ip: string | null
  created_at: string
}

export interface PatientDeletionResult {
  deletion_id: string
  cleanup_status: 'COMPLETED' | 'PENDING' | string
}

export interface AuditLogPage {
  items: AuditLog[]
  total: number
  page: number
  page_size: number
}

export interface AuditOperator {
  id: string
  display_name: string
}

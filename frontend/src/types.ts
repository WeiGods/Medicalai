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

export type AuditAction = 'LOGIN' | 'VISIT_DETAIL_VIEWED' | 'RECORDING_UPLOADED' | 'RECORDING_DELETED' | 'MEDICAL_RECORD_CONFIRMED' | 'MEDICAL_RECORD_EXPORT'

export interface AuditLog {
  id: string
  action: AuditAction | string
  result: 'SUCCESS' | 'FAILED' | string
  operator_name: string | null
  patient_name: string | null
  visit_no: string | null
  detail: string | null
  client_ip: string | null
  created_at: string
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

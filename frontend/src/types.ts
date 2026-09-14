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
}

export interface Recording {
  id: string
  visit_id: string
  recording_no: string
  source_type: 'UPLOAD' | 'SAMPLE' | string
  file_name: string | null
  mime_type: string | null
  size_bytes: number | null
  duration_ms: number | null
  status: 'UPLOADED' | 'PROCESSING' | 'DONE' | 'FAILED' | string
  audio_url: string | null
  created_at: string
}

export interface Utterance {
  role: 'DOCTOR' | 'PATIENT' | string
  text: string
  start_ms: number
  end_ms: number
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
  format: 'DOCX' | 'PDF' | string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string
  doctor_name: string
  created_at: string
}

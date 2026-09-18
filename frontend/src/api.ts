import type {
  AsrProvider, AsrJob, Confirmation, Doctor, LoginResult, LlmProvider, MedicalRecord, Patient, RecordExport,
  Recording, Transcript, Visit, ClinicalExtraction, AuditAction, AuditLogPage, AuditOperator, DemoRole
} from './types'

const base = import.meta.env.VITE_API_BASE_URL || ''

async function request<T>(path: string, options: RequestInit = {}, json = true): Promise<T> {
  const headers = new Headers(options.headers)
  if (json && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const token = localStorage.getItem('medicalai_token')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(`${base}${path}`, { ...options, headers })
  if (response.status === 401) {
    clearSession()
    window.dispatchEvent(new Event('medicalai:unauthorized'))
  }
  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const body = await response.json()
      message = body.message || message
    } catch {}
    throw new Error(message)
  }
  return response.status === 204 ? undefined as T : await response.json()
}

export function clearSession() {
  localStorage.removeItem('medicalai_token')
  localStorage.removeItem('medicalai_doctor')
}

export const api = {
  login: async (name: string, role: DemoRole) => {
    const result = await request<LoginResult>('/api/v1/auth/demo-login', {
      method: 'POST',
      body: JSON.stringify({ doctor_name: name, role })
    })
    localStorage.setItem('medicalai_token', result.access_token)
    return result
  },
  me: () => request<Doctor>('/api/v1/auth/me'),
  logout: async () => {
    if (localStorage.getItem('medicalai_token')) {
      await request<void>('/api/v1/auth/logout', { method: 'POST' })
    }
    clearSession()
  },
  patients: (keyword = '') => request<Patient[]>(`/api/v1/patients${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`),
  visits: () => request<Visit[]>('/api/v1/visits'),
  createPatient: (payload: { name: string; gender: string; age: number | null; phone?: string; idNo?: string }) => request<Patient>('/api/v1/patients', {
    method: 'POST',
    body: JSON.stringify({
      name: payload.name,
      gender: payload.gender,
      age: payload.age,
      phone: payload.phone?.trim() || null,
      id_no: payload.idNo?.trim() || null
    })
  }),
  createVisit: (patientId: string) => request<Visit>('/api/v1/visits', {
    method: 'POST',
    body: JSON.stringify({ patient_id: patientId })
  }),
  startVisit: (id: string) => request<Visit>(`/api/v1/visits/${id}/start`, { method: 'POST' }),
  completeVisit: (id: string) => request<Visit>(`/api/v1/visits/${id}/complete`, { method: 'POST' }),
  cancelVisit: (id: string) => request<void>(`/api/v1/visits/${id}/cancel`, { method: 'POST' }),

  recordings: (visitId: string) => request<Recording[]>(`/api/v1/visits/${visitId}/recordings`),
  uploadRecording: (visitId: string, file: File, durationMs: number) => {
    const body = new FormData()
    body.append('file', file)
    body.append('duration_ms', String(Math.round(durationMs)))
    return request<Recording>(`/api/v1/visits/${visitId}/recordings`, { method: 'POST', body }, false)
  },
  transcribe: (visitId: string, provider: AsrProvider) => request<AsrJob>(`/api/v1/visits/${visitId}/recordings/transcribe`, { method: 'POST', body: JSON.stringify({ provider }) }),
  transcribeStatus: (visitId: string, jobId: string) => request<AsrJob>(`/api/v1/visits/${visitId}/recordings/transcribe/${jobId}`),
  transcript: (visitId: string) => request<Transcript>(`/api/v1/visits/${visitId}/transcript`),
  saveTranscript: (visitId: string, transcript: string) => request<Transcript>(`/api/v1/visits/${visitId}/transcript`, {
    method: 'PUT',
    body: JSON.stringify({ transcript })
  }),
  updateUtteranceRole: (visitId: string, utteranceId: string, role: 'DOCTOR' | 'PATIENT' | 'OTHER') => request<Transcript>(
    `/api/v1/visits/${visitId}/transcript/utterances/${utteranceId}/role`, {
      method: 'PATCH',
      body: JSON.stringify({ role })
    }),
  reclassifyTranscriptRoles: (visitId: string, provider?: LlmProvider) => request<Transcript>(
    `/api/v1/visits/${visitId}/transcript/roles/reclassify`, {
      method: 'POST', body: provider ? JSON.stringify({ provider }) : undefined
    }),
  clinicalExtraction: (visitId: string) => request<ClinicalExtraction>(`/api/v1/visits/${visitId}/clinical-extraction`),
  generateClinicalExtraction: (visitId: string, provider?: LlmProvider) => request<ClinicalExtraction>(
    `/api/v1/visits/${visitId}/clinical-extraction/generate`, {
      method: 'POST', body: provider ? JSON.stringify({ provider }) : undefined
    }),
  confirmClinicalExtraction: (visitId: string) => request<ClinicalExtraction>(
    `/api/v1/visits/${visitId}/clinical-extraction/confirm`, { method: 'POST' }),

  medicalRecord: (visitId: string) => request<MedicalRecord>(`/api/v1/visits/${visitId}/medical-record`),
  generateMedicalRecord: (visitId: string) => request<MedicalRecord>(`/api/v1/visits/${visitId}/medical-record/generate`, { method: 'POST' }),
  saveMedicalRecord: (visitId: string, content: unknown) => request<MedicalRecord>(`/api/v1/visits/${visitId}/medical-record`, {
    method: 'PUT',
    body: JSON.stringify(content)
  }),
  editMedicalRecord: (visitId: string) => request<MedicalRecord>(`/api/v1/visits/${visitId}/medical-record/edit`, { method: 'POST' }),
  confirmMedicalRecord: (visitId: string, declaration: boolean) => request<MedicalRecord>(`/api/v1/visits/${visitId}/medical-record/confirm`, {
    method: 'POST',
    body: JSON.stringify({ declaration })
  }),
  confirmations: (visitId: string) => request<Confirmation[]>(`/api/v1/visits/${visitId}/confirmations`),
  recordExport: (visitId: string, format: 'DOCX' | 'PDF') => request<RecordExport[]>(`/api/v1/visits/${visitId}/exports`, {
    method: 'POST',
    body: JSON.stringify({ format })
  }),
  exports: (visitId: string) => request<RecordExport[]>(`/api/v1/visits/${visitId}/exports`),
  auditLogs: (query: { from?: string; to?: string; doctorId?: string; action?: AuditAction; page?: number; pageSize?: number }) => {
    const params = new URLSearchParams()
    if (query.from) params.set('from', query.from)
    if (query.to) params.set('to', query.to)
    if (query.doctorId) params.set('doctorId', query.doctorId)
    if (query.action) params.set('action', query.action)
    if (query.page) params.set('page', String(query.page))
    if (query.pageSize) params.set('pageSize', String(query.pageSize))
    const suffix = params.size ? `?${params.toString()}` : ''
    return request<AuditLogPage>(`/api/v1/audit-logs${suffix}`)
  },
  auditOperators: () => request<AuditOperator[]>('/api/v1/audit-logs/operators'),
  exportStatus: (exportId: string) => request<{ id: string; format: string; status: string; object_key?: string | null; error_message?: string | null }>(`/api/v1/exports/${exportId}`),
  exportBlob: async (exportId: string) => {
    const headers = new Headers()
    const token = localStorage.getItem('medicalai_token')
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(`${base}/api/v1/exports/${exportId}/download`, { headers })
    if (!response.ok) {
      let message = `导出文件下载失败（${response.status}）`
      try {
        const error = await response.json() as { message?: string }
        if (error.message) message = error.message
      } catch {
        // 下载端点可能由代理返回非 JSON 错误页，保留包含 HTTP 状态的稳定提示。
      }
      const error = Object.assign(new Error(message), { status: response.status })
      throw error
    }
    return response.blob()
  },
  audioUrl: (recordingId: string) => `${base}/api/v1/recordings/${recordingId}/audio`,
  /**
   * 音频与其他 API 一样受 Bearer Token 拦截器保护。
   *
   * <p>原生 <audio src="..."> 请求无法附带该请求头，因此显式获取字节后由调用方创建对象 URL。
   */
  audioBlob: async (recordingId: string, signal?: AbortSignal) => {
    const headers = new Headers()
    const token = localStorage.getItem('medicalai_token')
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(`${base}/api/v1/recordings/${recordingId}/audio`, { headers, signal })
    if (response.status === 401) {
      clearSession()
      window.dispatchEvent(new Event('medicalai:unauthorized'))
    }
    if (!response.ok) {
      let message = `音频请求失败（${response.status}）`
      try {
        const body = await response.json()
        message = body.message || message
      } catch {}
      throw new Error(message)
    }
    return response.blob()
  }
}

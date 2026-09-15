import type {
  AsrProvider, AsrJob, Confirmation, Doctor, LoginResult, MedicalRecord, Patient, RecordExport,
  Recording, Transcript, Visit
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
  login: async (name: string) => {
    const result = await request<LoginResult>('/api/v1/auth/demo-login', {
      method: 'POST',
      body: JSON.stringify({ doctor_name: name })
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
  exportStatus: (exportId: string) => request<{ id: string; format: string; status: string; object_key?: string | null; error_message?: string | null }>(`/api/v1/exports/${exportId}`),
  exportBlob: async (exportId: string) => {
    const headers = new Headers()
    const token = localStorage.getItem('medicalai_token')
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(`${base}/api/v1/exports/${exportId}/download`, { headers })
    if (!response.ok) throw new Error(`导出文件下载失败（${response.status}）`)
    return response.blob()
  },
  audioUrl: (recordingId: string) => `${base}/api/v1/recordings/${recordingId}/audio`,
  /**
   * Audio is protected by the same bearer-token interceptor as the rest of the
   * API.  A native <audio src="..."> request cannot attach that header, so
   * fetch the bytes explicitly and let the caller create an object URL.
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

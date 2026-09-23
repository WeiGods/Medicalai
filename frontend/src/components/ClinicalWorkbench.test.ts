import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ClinicalWorkbench from './ClinicalWorkbench.vue'
import { api } from '../api'
import type { ClinicalExtraction, Doctor, MedicalRecord, Patient, Recording, Transcript, Visit } from '../types'

vi.mock('../api', () => ({
  api: {
    patients: vi.fn(),
    visits: vi.fn(),
    recordings: vi.fn(),
    transcript: vi.fn(),
    clinicalExtraction: vi.fn(),
    medicalRecord: vi.fn(),
    confirmations: vi.fn(),
    exports: vi.fn(),
    exportTemplates: vi.fn(),
    managedTemplates: vi.fn(),
    saveTranscript: vi.fn(),
    retranscribeRecording: vi.fn(),
    transcribe: vi.fn()
  }
}))

const doctor: Doctor = {
  id: 'doctor-1', display_name: '测试医生', department_name: '内科', role: 'DOCTOR', source: 'DEMO'
}

const patient: Patient = {
  id: 'patient-1', patient_no: 'P-001', name: '测试患者', gender: '男', age: 32,
  phone_masked: '138****0000', id_no_masked: '', status: 'ACTIVE', created_by: doctor.id, created_by_name: doctor.display_name
}

const activeVisit: Visit = {
  id: 'visit-1', visit_no: 'V-001', patient_id: patient.id, doctor_id: doctor.id, status: 'ACTIVE',
  department_name: doctor.department_name, patient_name: patient.name, patient_gender: patient.gender,
  patient_age: patient.age, doctor_name: doctor.display_name, chief_complaint: null, version: 1,
  created_at: '2026-09-21T08:00:00Z', last_activity_at: '2026-09-21T08:00:00Z'
}

const doneRecording: Recording = {
  id: 'recording-1', visit_id: activeVisit.id, recording_no: 'REC-001', source_type: 'UPLOAD',
  file_name: 'first.wav', mime_type: 'audio/wav', size_bytes: 1000, duration_ms: 1000,
  status: 'DONE', audio_url: '/audio/recording-1', created_at: '2026-09-21T08:01:00Z'
}

const uploadedRecording: Recording = {
  ...doneRecording, id: 'recording-2', recording_no: 'REC-002', file_name: 'supplement.wav', status: 'UPLOADED'
}

function transcript(edited = false): Transcript {
  return {
    snapshot_id: 'snapshot-1', snapshot_version: 1, snapshot_hash: 'hash-1', authority_status: 'ADOPTED',
    transcript: '患者：头痛', edited, source_dirty: false,
    turns: [{ id: 'turn-1', role: 'PATIENT', text: '头痛', start_ms: 0, end_ms: 1000, role_source: 'LLM', role_review_required: false }],
    source_route: 'DASHSCOPE', available_routes: ['DASHSCOPE'], route_selection_required: false
  }
}

const pendingExtraction: ClinicalExtraction = {
  extraction_id: null, version_no: 0, status: 'PENDING', snapshot_id: null, snapshot_hash: null,
  fields: {}, quality_issues: [], generated_at: null, confirmed_at: null, provider_route: null,
  source_route: 'UNKNOWN', available_routes: [], route_selection_required: false
}

function record(draft = false, confirmed = false): MedicalRecord {
  return {
    record_id: draft ? 'record-1' : null, visit_id: activeVisit.id, version_no: draft ? 1 : 0,
    status: confirmed ? 'CONFIRMED' : 'DRAFT', generation_status: draft ? 'SUCCEEDED' : 'PENDING', content: null,
    confirmed, confirmed_at: confirmed ? '2026-09-21T08:30:00Z' : null,
    confirmed_by_name: confirmed ? doctor.display_name : null, source_dirty: false
  }
}

function configure(options: {
  patients?: Patient[]
  recordings?: Recording[]
  transcript?: Transcript
  record?: MedicalRecord
  visit?: Visit
} = {}) {
  vi.mocked(api.patients).mockResolvedValue(options.patients || [patient])
  vi.mocked(api.visits).mockResolvedValue([options.visit || activeVisit])
  vi.mocked(api.recordings).mockResolvedValue(options.recordings || [doneRecording])
  vi.mocked(api.transcript).mockResolvedValue(options.transcript || transcript())
  vi.mocked(api.clinicalExtraction).mockResolvedValue(pendingExtraction)
  vi.mocked(api.medicalRecord).mockResolvedValue(options.record || record())
  vi.mocked(api.confirmations).mockResolvedValue([])
  vi.mocked(api.exports).mockResolvedValue([])
  vi.mocked(api.exportTemplates).mockResolvedValue([])
  vi.mocked(api.managedTemplates).mockResolvedValue([])
}

async function mountWorkbench(options?: Parameters<typeof configure>[0]) {
  configure(options)
  const wrapper = mount(ClinicalWorkbench, { props: { doctor } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('scrollTo', vi.fn())
})

describe('patient avatar colors', () => {
  it('randomly assigns distinct colors and keeps each patient color consistent across views', async () => {
    const patients = [
      patient,
      { ...patient, id: 'patient-2', patient_no: 'M-SECOND', name: '李四' },
      { ...patient, id: 'patient-3', patient_no: 'M-THIRD', name: '王五' }
    ]
    const wrapper = await mountWorkbench({ patients })
    const tiles = wrapper.findAll('.queue-cards .patient-tile')
    const coloredClasses = ['lilac', 'blue', 'rose', 'amber', 'cyan']
    const tone = (element: ReturnType<typeof wrapper.get>) =>
      element.classes().find(className => coloredClasses.includes(className)) || 'default'

    expect(tiles).toHaveLength(3)
    expect(new Set(tiles.map(tile => tone(tile.get('.avatar')))).size).toBe(3)
    expect(tone(tiles[0].get('.avatar'))).toBe(tone(wrapper.get('.recent .avatar')))
    expect(tone(tiles[0].get('.avatar'))).toBe(tone(wrapper.get('.patient-summary .avatar')))
  })
})

describe('supplemental recording workflow', () => {
  it('returns from transcript to audio without mutating existing data and highlights audio', async () => {
    const wrapper = await mountWorkbench()

    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')

    expect(wrapper.text()).toContain('问诊录音')
    expect(wrapper.text()).toContain('first.wav')
    expect(wrapper.get('.step.current').text()).toContain('问诊音频')
    expect(api.transcribe).not.toHaveBeenCalled()
  })

  it('does not expose the return action for a signed record', async () => {
    const wrapper = await mountWorkbench({ record: record(true, true) })

    expect(wrapper.find('button[aria-label="返回录音上传"]').exists()).toBe(false)
  })

  it('does not expose recording re-transcription or deletion on the transcript page', async () => {
    const wrapper = await mountWorkbench()

    expect(wrapper.find('button[aria-label="重新转写 first.wav"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="删除 first.wav"]').exists()).toBe(false)
  })

  it('hides the return action while a transcript save is in progress', async () => {
    vi.mocked(api.saveTranscript).mockImplementation(() => new Promise(() => undefined))
    const wrapper = await mountWorkbench()

    await wrapper.get('.transcript-tabs button:nth-child(2)').trigger('click')
    await wrapper.get('#transcript-edit').setValue('患者：修改后的头痛描述')
    await wrapper.get('.transcript-actions button').trigger('click')
    await flushPromises()

    expect(wrapper.find('button[aria-label="返回录音上传"]').exists()).toBe(false)
  })

  it('requires confirmation before re-transcribing supplemental audio when transcript text was edited', async () => {
    vi.mocked(api.transcribe).mockImplementation(() => new Promise(() => undefined))
    const wrapper = await mountWorkbench({ recordings: [doneRecording, uploadedRecording], transcript: transcript(true) })

    await wrapper.get('.transcribe-action button').trigger('click')

    expect(wrapper.text()).toContain('重新转写确认')
    expect(api.transcribe).not.toHaveBeenCalled()

    await wrapper.get('button[aria-label="确认重新转写"]').trigger('click')
    expect(api.transcribe).toHaveBeenCalledTimes(1)
    expect(api.transcribe).toHaveBeenCalledWith(activeVisit.id, 'DASHSCOPE')
  })

  it('requires confirmation before re-transcribing supplemental audio when a draft exists', async () => {
    const wrapper = await mountWorkbench({ recordings: [doneRecording, uploadedRecording], record: record(true) })

    await wrapper.get('.transcribe-action button').trigger('click')

    expect(wrapper.text()).toContain('重新转写确认')
    expect(api.transcribe).not.toHaveBeenCalled()
  })

  it('submits supplemental audio directly when there is no edited transcript or draft', async () => {
    vi.mocked(api.transcribe).mockImplementation(() => new Promise(() => undefined))
    const wrapper = await mountWorkbench({ recordings: [doneRecording, uploadedRecording] })

    await wrapper.get('.transcribe-action button').trigger('click')

    expect(wrapper.text()).not.toContain('重新转写确认')
    expect(api.transcribe).toHaveBeenCalledWith(activeVisit.id, 'DASHSCOPE')
  })

  it('re-transcribes an existing audio file with the selected ASR provider after confirmation', async () => {
    vi.mocked(api.retranscribeRecording).mockImplementation(() => new Promise(() => undefined))
    const wrapper = await mountWorkbench({ recordings: [doneRecording], transcript: transcript(true) })

    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    await wrapper.get('button[aria-label="重新转写 first.wav"]').trigger('click')

    expect(wrapper.text()).toContain('重新转写录音')
    expect(api.retranscribeRecording).not.toHaveBeenCalled()

    await wrapper.get('button[aria-label="确认重新转写录音"]').trigger('click')
    expect(api.retranscribeRecording).toHaveBeenCalledWith(activeVisit.id, doneRecording.id, 'DASHSCOPE')
  })

  it('allows an active draft to expose deletion for a transcribed audio file', async () => {
    const wrapper = await mountWorkbench({ recordings: [doneRecording] })

    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    expect(wrapper.find('button[aria-label="删除 first.wav"]').exists()).toBe(true)
  })
})

describe('template management navigation', () => {
  it('is visible only to a department head and does not render the patient workflow when open', async () => {
    configure()
    const head: Doctor = { ...doctor, role: 'DEPARTMENT_HEAD' }
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()

    await wrapper.get('button.nav-item:nth-child(2)').trigger('click')

    expect(wrapper.text()).toContain('模板管理')
    expect(wrapper.find('.queue').exists()).toBe(false)
    expect(wrapper.find('.bottom-bar').exists()).toBe(false)
  })

  it('does not show template management to a doctor', async () => {
    const wrapper = await mountWorkbench()
    expect(wrapper.text()).not.toContain('模板管理')
  })
})

describe('department head read-only visit view', () => {
  it('keeps long visit metadata inside the department head queue tile', async () => {
    const longVisit: Visit = {
      ...activeVisit,
      visit_no: 'VIS-20260923-1234567890-ABCDEFGHIJKLMN',
      doctor_name: '这是一个很长的接诊医生姓名用于测试卡片布局'
    }
    configure({ visit: longVisit })
    const head: Doctor = { ...doctor, role: 'DEPARTMENT_HEAD' }
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()

    const tile = wrapper.get('.visit-tile')
    expect(tile.get('.patient-tile-content').exists()).toBe(true)
    expect(tile.get('.patient-meta').attributes('title')).toContain(longVisit.visit_no)
    expect(tile.get('.patient-owner').attributes('title')).toContain(longVisit.doctor_name)
  })

  it('loads visit artifacts and exposes navigation without write controls', async () => {
    configure({ record: record(true, true) })
    const head: Doctor = { ...doctor, role: 'DEPARTMENT_HEAD' }
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()

    expect(wrapper.find('.read-only-banner').exists()).toBe(true)
    expect(wrapper.findAll('button').some(button => button.text().includes('生成病历'))).toBe(false)

    await wrapper.get('.step:nth-child(2)').trigger('click')
    expect(wrapper.text()).toContain('问诊录音')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text().includes('开始转写'))).toBe(false)

    await wrapper.get('.step:nth-child(4)').trigger('click')
    expect(wrapper.text()).toContain('门诊病历')
    expect(wrapper.findAll('button').some(button => button.text().includes('重新生成'))).toBe(false)

    await wrapper.get('.step:nth-child(5)').trigger('click')
    expect(wrapper.text()).toContain('导出记录')
    expect(wrapper.text()).not.toContain('选择导出格式')
    expect(wrapper.findAll('button').some(button => button.text().includes('导出 Word'))).toBe(false)
  })
})

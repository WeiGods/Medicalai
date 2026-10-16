import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ClinicalWorkbench from './ClinicalWorkbench.vue'
import { api } from '../api'
import type { AiRouteSettings, AsrJob, AsrPreviewRecording, ClinicalExtraction, Doctor, ExportTemplate, MedicalRecord, Patient, RecordExport, Recording, Transcript, Visit } from '../types'

vi.mock('../api', () => ({
  api: {
    patients: vi.fn(),
    visits: vi.fn(),
    recordings: vi.fn(),
    asrPreviews: vi.fn(),
    activeTranscription: vi.fn(),
    uploadRecording: vi.fn(),
    transcript: vi.fn(),
    clinicalExtraction: vi.fn(),
    medicalRecord: vi.fn(),
    confirmations: vi.fn(),
    exports: vi.fn(),
    audioBlob: vi.fn(),
    exportTemplates: vi.fn(),
    exportBlob: vi.fn(),
    exportPreviewBlob: vi.fn(),
    recordExport: vi.fn(),
    managedTemplates: vi.fn(),
    saveTranscript: vi.fn(),
    retranscribeRecording: vi.fn(),
    transcribe: vi.fn(),
    reclassifyTranscriptRoles: vi.fn(),
    generateClinicalExtraction: vi.fn(),
    aiRouteSettings: vi.fn(),
    updateAiRouteSettings: vi.fn(),
    deletePatient: vi.fn(),
    patientDeletionStatus: vi.fn()
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

const aiRouteSettings: AiRouteSettings = {
  route: 'DASHSCOPE', asr_available: true, role_available: true, extraction_available: true,
  unavailable_capabilities: [], public_asr_available: true, public_role_available: true,
  public_extraction_available: true, local_asr_available: true, local_role_available: true,
  local_extraction_available: true
}

function transcript(edited = false): Transcript {
  return {
    snapshot_id: 'snapshot-1', snapshot_version: 1, snapshot_hash: 'hash-1', authority_status: 'ADOPTED',
    transcript: '患者：头痛', edited, source_dirty: false,
    turns: [{ id: 'turn-1', role: 'PATIENT', text: '头痛', start_ms: 0, end_ms: 1000, role_source: 'LLM', role_review_required: false }],
    source_route: 'DASHSCOPE', available_routes: ['DASHSCOPE'], route_selection_required: false,
    default_route: 'DASHSCOPE'
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
  asrPreviews?: AsrPreviewRecording[]
  activeAsrJob?: AsrJob | null
  transcript?: Transcript
  extraction?: ClinicalExtraction
  record?: MedicalRecord
  visit?: Visit
  visits?: Visit[]
  exports?: RecordExport[]
  templates?: ExportTemplate[]
} = {}) {
  vi.mocked(api.patients).mockResolvedValue(options.patients || [patient])
  vi.mocked(api.visits).mockResolvedValue(options.visits || [options.visit || activeVisit])
  vi.mocked(api.recordings).mockResolvedValue(options.recordings || [doneRecording])
  vi.mocked(api.asrPreviews).mockResolvedValue(options.asrPreviews || [])
  vi.mocked(api.activeTranscription).mockResolvedValue(options.activeAsrJob || null)
  vi.mocked(api.transcript).mockResolvedValue(options.transcript || transcript())
  vi.mocked(api.clinicalExtraction).mockResolvedValue(options.extraction || pendingExtraction)
  vi.mocked(api.medicalRecord).mockResolvedValue(options.record || record())
  vi.mocked(api.confirmations).mockResolvedValue([])
  vi.mocked(api.exports).mockResolvedValue(options.exports || [])
  vi.mocked(api.exportTemplates).mockResolvedValue(options.templates || [])
  vi.mocked(api.managedTemplates).mockResolvedValue([])
  vi.mocked(api.aiRouteSettings).mockResolvedValue(aiRouteSettings)
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

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
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

describe('automatic clinical extraction display', () => {
  const queued: ClinicalExtraction = { ...pendingExtraction, status: 'QUEUED', snapshot_id: 'snapshot-1', snapshot_hash: 'hash-1' }
  const generated: ClinicalExtraction = { ...queued, status: 'GENERATED', fields: {
    chief_complaint: { value: '头痛三天', confidence: 95, evidence: [] }
  } }

  it('places the summary alongside the dialogue inside the active transcript content area', async () => {
    const wrapper = await mountWorkbench({ extraction: generated })
    const layout = wrapper.get('.transcript-view > .card > .transcript-dialogue-layout')
    expect(layout.find('.transcript-body').exists()).toBe(true)
    expect(layout.get('.clinical-summary .clinical-summary-chief').text()).toBe('头痛三天')
    expect(api.generateClinicalExtraction).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('renders extraction facts with readable status, confidence, and evidence layers', async () => {
    const readable: ClinicalExtraction = {
      ...generated,
      fields: {
        chief_complaint: {
          value: '头痛三天',
          confidence: 95,
          evidence: [{ turn_index: 0, start_ms: 1200, end_ms: 2300, role: 'PATIENT', quote: '我头痛三天了' }]
        }
      }
    }
    const wrapper = await mountWorkbench({ extraction: readable })

    await wrapper.get('.transcript-tabs button:nth-child(3)').trigger('click')

    expect(wrapper.get('.extraction-header').text()).toContain('信息提取结果')
    expect(wrapper.find('.extraction-content .extraction-facts').exists()).toBe(true)
    expect(wrapper.find('.extraction-actions').exists()).toBe(true)
    const fact = wrapper.get('.extraction-fact')
    expect(fact.get('.fact-head b').text()).toBe('主诉')
    expect(fact.get('.confidence-pill').classes()).toContain('confidence-high')
    expect(fact.get('.fact-evidence').text()).toContain('患者')
    expect(fact.get('.fact-evidence').text()).toContain('我头痛三天了')
    wrapper.unmount()
  })

  it('polls a persisted task through completion without switching away from the dialogue', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountWorkbench({ extraction: queued })
      expect(wrapper.get('.clinical-summary').text()).toContain('正在提取')
      vi.mocked(api.clinicalExtraction).mockResolvedValue(generated)
      await vi.advanceTimersByTimeAsync(1600)
      await flushPromises()
      expect(wrapper.get('.clinical-summary-chief').text()).toBe('头痛三天')
      expect(wrapper.get('.transcript-tabs button.active').text()).toBe('医患对话')
      const calls = vi.mocked(api.clinicalExtraction).mock.calls.length
      await vi.advanceTimersByTimeAsync(5000)
      expect(api.clinicalExtraction).toHaveBeenCalledTimes(calls)
      wrapper.unmount()
    } finally { vi.useRealTimers() }
  })

  it('retries in place with an independent loading state and does not repeat the request', async () => {
    let finish!: (value: ClinicalExtraction) => void
    vi.mocked(api.generateClinicalExtraction).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = await mountWorkbench({ extraction: { ...queued, status: 'FAILED', quality_issues: ['请重试'] } })
    await wrapper.get('.clinical-summary-actions button').trigger('click')
    expect(wrapper.get('.clinical-summary').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.transcript-body .role-control select').attributes('disabled')).toBeUndefined()
    expect(api.generateClinicalExtraction).toHaveBeenCalledTimes(1)
    finish(generated)
    await flushPromises()
    expect(wrapper.get('.clinical-summary-chief').text()).toBe('头痛三天')
    expect(wrapper.get('.transcript-tabs button.active').text()).toBe('医患对话')
    wrapper.unmount()
  })

  it('ignores an in-flight extraction response after switching to another patient', async () => {
    let finish!: (value: ClinicalExtraction) => void
    const second = { ...patient, id: 'patient-2', name: '第二位患者' }
    const secondVisit = { ...activeVisit, id: 'visit-2', patient_id: second.id }
    vi.mocked(api.generateClinicalExtraction).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const wrapper = await mountWorkbench({ patients: [patient, second], visits: [activeVisit, secondVisit],
      extraction: { ...queued, status: 'FAILED' } })
    await wrapper.get('.clinical-summary-actions button').trigger('click')
    vi.mocked(api.transcript).mockResolvedValue({ ...transcript(), snapshot_id: 'snapshot-2', snapshot_hash: 'hash-2' })
    vi.mocked(api.clinicalExtraction).mockResolvedValue(pendingExtraction)
    await wrapper.vm.selectPatient(second.id)
    finish(generated)
    await flushPromises()
    expect(wrapper.get('.clinical-summary').text()).not.toContain('头痛三天')
    wrapper.unmount()
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
    expect(api.transcribe).toHaveBeenCalledWith(activeVisit.id)
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
    expect(api.transcribe).toHaveBeenCalledWith(activeVisit.id)
  })

  it('re-transcribes an existing audio file with the visit route after confirmation', async () => {
    vi.mocked(api.retranscribeRecording).mockImplementation(() => new Promise(() => undefined))
    const wrapper = await mountWorkbench({ recordings: [doneRecording], transcript: transcript(true) })

    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    await wrapper.get('button[aria-label="重新转写 first.wav"]').trigger('click')

    expect(wrapper.text()).toContain('重新转写录音')
    expect(api.retranscribeRecording).not.toHaveBeenCalled()

    await wrapper.get('button[aria-label="确认重新转写录音"]').trigger('click')
    expect(api.retranscribeRecording).toHaveBeenCalledWith(activeVisit.id, doneRecording.id)
  })

  it('allows an active draft to expose deletion for a transcribed audio file', async () => {
    const wrapper = await mountWorkbench({ recordings: [doneRecording] })

    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    expect(wrapper.find('button[aria-label="删除 first.wav"]').exists()).toBe(true)
  })
})

describe('recording playback', () => {
  it('keeps audio playable while its transcription is processing', async () => {
    const processingRecording = { ...doneRecording, status: 'PROCESSING' as const }
    const wrapper = await mountWorkbench({ recordings: [processingRecording] })

    expect(wrapper.get('.audio-item .badge').text()).toBe('转写中')
    expect(wrapper.find('.audio-item .audio-player').exists()).toBe(true)

    vi.mocked(api.audioBlob).mockResolvedValue(new Blob(['audio'], { type: 'audio/wav' }))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:processing-recording')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
    await wrapper.get('.audio-item .play-btn').trigger('click')
    await flushPromises()

    expect(api.audioBlob).toHaveBeenCalledWith(processingRecording.id, expect.any(AbortSignal))
    expect(wrapper.find('.audio-item .audio-player').exists()).toBe(true)
  })

  it('does not abort an audio download when the play button is clicked twice while loading', async () => {
    const wrapper = await mountWorkbench()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    let resolveAudio!: (blob: Blob) => void
    const pendingAudio = new Promise<Blob>(resolve => { resolveAudio = resolve })
    vi.mocked(api.audioBlob).mockReturnValue(pendingAudio)

    const firstClick = wrapper.get('.audio-item .play-btn').trigger('click')
    await vi.waitFor(() => expect(api.audioBlob).toHaveBeenCalledTimes(1))
    const requestSignal = vi.mocked(api.audioBlob).mock.calls[0][1]

    await wrapper.get('.audio-item .play-btn').trigger('click')
    expect(api.audioBlob).toHaveBeenCalledTimes(1)
    expect(requestSignal?.aborted).toBe(false)
    expect(wrapper.get('.audio-item .play-btn').attributes('disabled')).toBeDefined()

    resolveAudio(new Blob(['audio'], { type: 'audio/wav' }))
    await firstClick
  })

  it('keeps the custom controls in place and reuses loaded audio when paused or replayed', async () => {
    const wrapper = await mountWorkbench()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    await flushPromises()
    vi.mocked(api.audioBlob).mockResolvedValue(new Blob(['audio'], { type: 'audio/wav' }))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:recording')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play')
      .mockRejectedValueOnce(new DOMException('User gesture required', 'NotAllowedError'))
      .mockResolvedValue(undefined)

    await wrapper.get('.audio-item .play-btn').trigger('click')
    await flushPromises()

    expect(api.audioBlob).toHaveBeenCalledWith(doneRecording.id, expect.any(AbortSignal))
    expect(wrapper.findAll('.recording-native-player')).toHaveLength(0)
    expect(wrapper.get('.audio-engine').attributes('controls')).toBeUndefined()
    expect(wrapper.findAll('.audio-player')).toHaveLength(1)
    expect(wrapper.find('.audio-progress').exists()).toBe(true)
    expect(wrapper.find('select[aria-label="播放速度：first.wav"]').exists()).toBe(true)
    expect(wrapper.get('.audio-status').text()).toContain('音频已载入')
    expect(play).toHaveBeenCalledTimes(1)

    const player = wrapper.get('.audio-engine').element as HTMLAudioElement
    await player.play()
    player.dispatchEvent(new Event('pause'))
    await player.play()
    player.dispatchEvent(new Event('ended'))
    expect(player.getAttribute('src')).toBe('blob:recording')
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    await player.play()

    expect(api.audioBlob).toHaveBeenCalledTimes(1)
    expect(play).toHaveBeenCalledTimes(4)
  })

  it('allows playback to be retried after the audio request fails', async () => {
    const wrapper = await mountWorkbench()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    vi.mocked(api.audioBlob)
      .mockRejectedValueOnce(new Error('网络暂时不可用'))
      .mockResolvedValueOnce(new Blob(['audio'], { type: 'audio/wav' }))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:retry')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)

    await wrapper.get('.audio-item .play-btn').trigger('click')
    await flushPromises()

    expect(wrapper.get('.audio-error').text()).toContain('网络暂时不可用')
    await wrapper.get('.audio-retry').trigger('click')
    await flushPromises()

    expect(api.audioBlob).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.audio-error').exists()).toBe(false)
    expect(wrapper.find('.audio-player').exists()).toBe(true)
  })

  it('shows media duration and applies seek, volume, and playback rate to the audio engine', async () => {
    const wrapper = await mountWorkbench()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    vi.mocked(api.audioBlob).mockResolvedValue(new Blob(['audio'], { type: 'audio/wav' }))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:controls')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)

    await wrapper.get('.audio-item .play-btn').trigger('click')
    await flushPromises()
    const player = wrapper.get('.audio-engine').element as HTMLAudioElement
    Object.defineProperties(player, {
      duration: { configurable: true, value: 75 },
      currentTime: { configurable: true, writable: true, value: 0 }
    })
    player.dispatchEvent(new Event('loadedmetadata'))
    await flushPromises()

    expect(wrapper.get('.audio-time').text()).toBe('00:00 / 01:15')
    await wrapper.get('.audio-progress').setValue('25')
    await wrapper.get('.audio-volume-control input').setValue('0.4')
    await wrapper.get('.audio-rate-control select').setValue('1.5')

    expect(player.currentTime).toBe(25)
    expect(player.volume).toBe(0.4)
    expect(player.playbackRate).toBe(1.5)
  })

  it('releases the previous recording when switching and releases the active blob on unmount', async () => {
    const secondRecording: Recording = {
      ...doneRecording, id: 'recording-2', recording_no: 'REC-002', file_name: 'second.wav', audio_url: '/audio/recording-2'
    }
    const wrapper = await mountWorkbench({ recordings: [doneRecording, secondRecording] })
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    vi.mocked(api.audioBlob).mockResolvedValue(new Blob(['audio'], { type: 'audio/wav' }))
    vi.spyOn(URL, 'createObjectURL')
      .mockReturnValueOnce('blob:first')
      .mockReturnValueOnce('blob:second')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)

    await wrapper.findAll('.audio-item .play-btn')[0].trigger('click')
    await flushPromises()
    await wrapper.findAll('.audio-item .play-btn')[1].trigger('click')
    await flushPromises()

    expect(vi.mocked(api.audioBlob).mock.calls.map(([id]) => id)).toEqual([doneRecording.id, secondRecording.id])
    expect(revoke).toHaveBeenCalledWith('blob:first')
    expect((wrapper.get('.audio-engine').element as HTMLAudioElement).getAttribute('src')).toBe('blob:second')

    wrapper.unmount()
    expect(revoke).toHaveBeenCalledWith('blob:second')
  })

  it('releases cached audio when leaving the audio page', async () => {
    const wrapper = await mountWorkbench()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    vi.mocked(api.audioBlob).mockResolvedValue(new Blob(['audio'], { type: 'audio/wav' }))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:leave-audio')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)

    await wrapper.get('.audio-item .play-btn').trigger('click')
    await flushPromises()
    await wrapper.get('.step:nth-child(3)').trigger('click')

    expect(revoke).toHaveBeenCalledWith('blob:leave-audio')
    expect(wrapper.find('.audio-engine').exists()).toBe(false)
    wrapper.unmount()
  })

  it('releases cached audio when switching to another patient encounter', async () => {
    const secondPatient: Patient = { ...patient, id: 'patient-2', patient_no: 'P-002', name: '另一位患者' }
    const secondVisit: Visit = {
      ...activeVisit, id: 'visit-2', visit_no: 'V-002', patient_id: secondPatient.id,
      patient_name: secondPatient.name
    }
    const wrapper = await mountWorkbench({ patients: [patient, secondPatient], visits: [activeVisit, secondVisit] })
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    vi.mocked(api.audioBlob).mockResolvedValue(new Blob(['audio'], { type: 'audio/wav' }))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:change-visit')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)

    await wrapper.get('.audio-item .play-btn').trigger('click')
    await flushPromises()
    await wrapper.findAll('.queue-cards .patient-tile')[1].trigger('click')
    await flushPromises()

    expect(revoke).toHaveBeenCalledWith('blob:change-visit')
    expect(wrapper.find('.audio-engine').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('ASR raw preview', () => {
  it('keeps the not-started preview compact while preserving recording actions', async () => {
    const wrapper = await mountWorkbench({ recordings: [uploadedRecording], asrPreviews: [] })
    await wrapper.get('.step:nth-child(2)').trigger('click')

    expect(wrapper.find('.audio-view.audio-view-active').exists()).toBe(true)
    expect(wrapper.find('.audio-source-grid').exists()).toBe(true)
    expect(wrapper.find('.audio-view-asr-empty').exists()).toBe(true)
    expect(wrapper.get('.asr-preview-empty').text()).toContain('完成录音转写后，ASR 原文将在此显示')
    expect(wrapper.find('.transcribe-action button').exists()).toBe(true)
    wrapper.unmount()
  })

  it('loads and displays raw ASR segments on the audio page without changing canonical transcript', async () => {
    const preview: AsrPreviewRecording = {
      recording_id: doneRecording.id,
      recording_no: doneRecording.recording_no,
      file_name: doneRecording.file_name,
      status: 'PROCESSING',
      error_message: null,
      segments: [{ utterance_id: 'preview-1', text: '原始识别文字', start_ms: 1250, end_ms: 2400, speaker_id: 2 }]
    }
    const wrapper = await mountWorkbench({ asrPreviews: [preview] })

    expect(api.asrPreviews).toHaveBeenCalledWith(activeVisit.id)
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')

    const card = wrapper.get('.asr-preview-card')
    expect(card.text()).toContain('ASR 原文预览')
    expect(card.text()).toContain('原始识别文字')
    expect(card.text()).toContain('角色判断中')
    expect(wrapper.find('.asr-preview-card').exists()).toBe(true)
    await wrapper.get('.step:nth-child(3)').trigger('click')
    expect(wrapper.get('.transcript-body').text()).toContain('头痛')
    expect(wrapper.get('.transcript-body').text()).not.toContain('原始识别文字')
  })

  it('keeps the preview visible for a completed read-only visit', async () => {
    const completedVisit = { ...activeVisit, status: 'COMPLETED' as const }
    const preview: AsrPreviewRecording = {
      recording_id: doneRecording.id,
      recording_no: doneRecording.recording_no,
      file_name: doneRecording.file_name,
      status: 'DONE',
      segments: [{ text: '已完成原文', start_ms: 0, end_ms: 900 }]
    }
    const wrapper = await mountWorkbench({ visit: completedVisit, visits: [completedVisit], asrPreviews: [preview] })
    await wrapper.get('.step:nth-child(2)').trigger('click')

    expect(wrapper.get('.asr-preview-card').text()).toContain('已完成原文')
    expect(wrapper.get('.asr-preview-card').text()).toContain('已完成')
  })

  it('uses the completed reading layout and opens the dialogue from the shortcut', async () => {
    const completedVisit = { ...activeVisit, status: 'COMPLETED' as const }
    const preview: AsrPreviewRecording = {
      recording_id: doneRecording.id,
      recording_no: doneRecording.recording_no,
      file_name: doneRecording.file_name,
      status: 'DONE',
      segments: [{ text: '已完成原文', start_ms: 0, end_ms: 900 }]
    }
    const wrapper = await mountWorkbench({ visit: completedVisit, visits: [completedVisit], asrPreviews: [preview] })
    await wrapper.get('.step:nth-child(2)').trigger('click')

    expect(wrapper.find('.audio-view.audio-view-completed').exists()).toBe(true)
    expect(wrapper.find('.audio-view-completed > .asr-preview-card').exists()).toBe(true)
    expect(wrapper.get('.audio-view-completed .audio-card-head-actions button').text()).toContain('查看转写结果')
    expect(wrapper.get('.asr-preview-actions button').text()).toContain('查看转写结果')

    await wrapper.get('.audio-view-completed .audio-card-head-actions button').trigger('click')
    expect(wrapper.find('.transcript-view').exists()).toBe(true)
    expect(wrapper.get('.transcript-tabs button.active').text()).toBe('医患对话')
    wrapper.unmount()
  })

  it('restores preview polling after loading a visit with an in-progress recording', async () => {
    const processingRecording = { ...doneRecording, status: 'PROCESSING' as const }
    const setInterval = vi.spyOn(window, 'setInterval')
    const clearInterval = vi.spyOn(window, 'clearInterval')
    const wrapper = await mountWorkbench({ recordings: [processingRecording], asrPreviews: [] })
    const poll = setInterval.mock.calls.find(([, delay]) => delay === 3000)?.[0] as (() => Promise<void>) | undefined

    expect(poll).toBeDefined()
    expect(api.asrPreviews).toHaveBeenCalledTimes(1)
    await poll?.()
    await flushPromises()
    expect(api.asrPreviews).toHaveBeenCalledTimes(2)
    wrapper.unmount()
    expect(clearInterval).toHaveBeenCalled()
  })

  it('restores polling for a queued PENDING job after a page reload', async () => {
    const pendingJob: AsrJob = {
      job_id: 'job-pending', provider_route: 'LOCAL', status: 'PENDING',
      total_recordings: 1, completed_recordings: 0, error_message: null, transcript: null
    }
    const setInterval = vi.spyOn(window, 'setInterval')
    const wrapper = await mountWorkbench({ recordings: [uploadedRecording], activeAsrJob: pendingJob })
    const poll = setInterval.mock.calls.find(([, delay]) => delay === 3000)?.[0] as (() => Promise<void>) | undefined

    expect(api.activeTranscription).toHaveBeenCalledWith(activeVisit.id)
    expect(poll).toBeDefined()
    await poll?.()
    await flushPromises()
    expect(api.activeTranscription).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('keeps polling after a terminal recording when the preview refresh fails', async () => {
    const processingRecording = { ...doneRecording, status: 'PROCESSING' as const }
    const setInterval = vi.spyOn(window, 'setInterval')
    const clearInterval = vi.spyOn(window, 'clearInterval')
    const wrapper = await mountWorkbench({ recordings: [processingRecording], asrPreviews: [] })
    const poll = setInterval.mock.calls.find(([, delay]) => delay === 3000)?.[0] as (() => Promise<void>) | undefined
    vi.mocked(api.recordings).mockResolvedValue([doneRecording])
    vi.mocked(api.asrPreviews).mockRejectedValueOnce(new Error('preview temporarily unavailable'))
    vi.mocked(api.activeTranscription).mockResolvedValue(null)

    await poll?.()
    await flushPromises()
    expect(wrapper.get('.asr-preview-card').text()).toContain('preview temporarily unavailable')
    expect(clearInterval).not.toHaveBeenCalled()

    vi.mocked(api.asrPreviews).mockResolvedValue([])
    await poll?.()
    await flushPromises()
    expect(clearInterval).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('isolates preview loading failures from the canonical visit data', async () => {
    configure()
    vi.mocked(api.asrPreviews).mockRejectedValue(new Error('preview service unavailable'))
    const wrapper = mount(ClinicalWorkbench, { props: { doctor } })
    await flushPromises()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')

    const card = wrapper.get('.asr-preview-card')
    expect(card.text()).toContain('ASR 原文预览暂时无法加载：preview service unavailable')
    expect(wrapper.text()).toContain('转写结果')
    expect(api.transcript).toHaveBeenCalledWith(activeVisit.id)
  })

  it('clears the previous visit preview when switching patients', async () => {
    const secondPatient: Patient = { ...patient, id: 'patient-2', patient_no: 'P-002', name: '另一位患者' }
    const secondVisit: Visit = {
      ...activeVisit, id: 'visit-2', visit_no: 'V-002', patient_id: secondPatient.id,
      patient_name: secondPatient.name
    }
    const preview: AsrPreviewRecording = {
      recording_id: doneRecording.id,
      recording_no: doneRecording.recording_no,
      file_name: doneRecording.file_name,
      status: 'DONE',
      segments: [{ text: '上一位患者的原文', start_ms: 0, end_ms: 900 }]
    }
    configure({ patients: [patient, secondPatient], visits: [activeVisit, secondVisit], asrPreviews: [preview] })
    vi.mocked(api.asrPreviews).mockImplementation(async visitId => visitId === activeVisit.id ? [preview] : [])
    const wrapper = mount(ClinicalWorkbench, { props: { doctor } })
    await flushPromises()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    expect(wrapper.get('.asr-preview-card').text()).toContain('上一位患者的原文')

    await wrapper.findAll('.queue-cards .patient-tile')[1].trigger('click')
    await flushPromises()
    await wrapper.get('.step:nth-child(2)').trigger('click')

    expect(wrapper.get('.asr-preview-card').text()).not.toContain('上一位患者的原文')
    expect(wrapper.find('.asr-preview-empty').exists()).toBe(true)
  })
})

describe('microphone input selection', () => {
  it('requests permission before listing device names hidden by the browser', async () => {
    const temporaryTrack = { stop: vi.fn() }
    const temporaryStream = { getTracks: () => [temporaryTrack] } as unknown as MediaStream
    const enumerateDevices = vi.fn()
      .mockResolvedValueOnce([
        { kind: 'audioinput', deviceId: 'usb-mic', label: '' },
        { kind: 'audioinput', deviceId: 'communications', label: '' }
      ])
      .mockResolvedValueOnce([
        { kind: 'audioinput', deviceId: 'usb-mic', label: '' },
        { kind: 'audioinput', deviceId: 'communications', label: '' }
      ])
      .mockResolvedValueOnce([
        { kind: 'audioinput', deviceId: 'usb-mic', label: 'USB Audio Device' },
        { kind: 'audioinput', deviceId: 'communications', label: 'Communications - Microphone' }
      ])
    const getUserMedia = vi.fn().mockResolvedValue(temporaryStream)
    const mediaDevices = { enumerateDevices, getUserMedia, addEventListener: vi.fn(), removeEventListener: vi.fn() }
    const navigatorStub = { userAgent: 'happy-dom', mediaDevices } as unknown as Navigator
    vi.stubGlobal('navigator', navigatorStub)

    const wrapper = await mountWorkbench()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.get('.recording-input-refresh').attributes('disabled')).toBeUndefined())
    await wrapper.get('.recording-input-refresh').trigger('click')
    await flushPromises()

    expect(getUserMedia).toHaveBeenCalledWith({ audio: true })
    expect(temporaryTrack.stop).toHaveBeenCalledOnce()
    expect(wrapper.get('select[aria-label="网页录音输入设备"]').text()).toContain('USB Audio Device')
    expect(wrapper.get('select[aria-label="网页录音输入设备"]').text()).toContain('Communications - Microphone')
    wrapper.unmount()
  })

  it('opens the selected input device when recording starts', async () => {
    const inputTrack = {
      label: 'USB Audio Device',
      getSettings: () => ({ deviceId: 'usb-mic' }),
      stop: vi.fn()
    }
    const inputStream = {
      getAudioTracks: () => [inputTrack],
      getTracks: () => [inputTrack]
    } as unknown as MediaStream
    const mediaDevices = {
      enumerateDevices: vi.fn().mockResolvedValue([
        { kind: 'audioinput', deviceId: 'usb-mic', label: 'USB Audio Device' },
        { kind: 'audioinput', deviceId: 'built-in-mic', label: 'Built-in Microphone' }
      ]),
      getUserMedia: vi.fn().mockResolvedValue(inputStream),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }
    const navigatorStub = { userAgent: 'happy-dom', mediaDevices } as unknown as Navigator
    vi.stubGlobal('navigator', navigatorStub)

    class TestMediaRecorder {
      static isTypeSupported() { return true }
      readonly mimeType = 'audio/webm;codecs=opus'
      state: RecordingState = 'inactive'
      ondataavailable: ((event: BlobEvent) => void) | null = null
      onstop: ((event: Event) => void) | null = null
      start() { this.state = 'recording' }
      stop() { this.state = 'inactive' }
      pause() { this.state = 'paused' }
      resume() { this.state = 'recording' }
    }
    vi.stubGlobal('MediaRecorder', TestMediaRecorder)

    const wrapper = await mountWorkbench()
    await wrapper.get('button[aria-label="返回录音上传"]').trigger('click')
    await flushPromises()
    await wrapper.get('select[aria-label="网页录音输入设备"]').setValue('usb-mic')
    await wrapper.get('.recorder-main-btn').trigger('click')
    await flushPromises()

    expect(mediaDevices.getUserMedia).toHaveBeenCalledWith({ audio: { deviceId: { exact: 'usb-mic' } } })
    expect(wrapper.text()).toContain('本次录音输入：USB Audio Device')
    expect(wrapper.get('select[aria-label="网页录音输入设备"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('clears the live recording timer and signal state when a recording ends', async () => {
    const track = { label: 'USB Audio Device', getSettings: () => ({ deviceId: 'usb-mic' }), stop: vi.fn() }
    const stream = {
      getAudioTracks: () => [track],
      getTracks: () => [track]
    } as unknown as MediaStream
    const mediaDevices = {
      enumerateDevices: vi.fn().mockResolvedValue([]),
      getUserMedia: vi.fn().mockResolvedValue(stream),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }
    vi.stubGlobal('navigator', { userAgent: 'happy-dom', mediaDevices } as unknown as Navigator)
    const now = vi.spyOn(Date, 'now').mockReturnValue(1000)

    class TestMediaRecorder {
      static isTypeSupported() { return true }
      readonly mimeType = 'audio/webm;codecs=opus'
      state: RecordingState = 'inactive'
      ondataavailable: ((event: BlobEvent) => void) | null = null
      onstop: ((event: Event) => void) | null = null
      start() { this.state = 'recording' }
      stop() {
        this.state = 'inactive'
        this.ondataavailable?.({ data: new Blob(['captured-audio'], { type: this.mimeType }) } as BlobEvent)
        this.onstop?.(new Event('stop'))
      }
      pause() { this.state = 'paused' }
      resume() { this.state = 'recording' }
    }
    vi.stubGlobal('MediaRecorder', TestMediaRecorder)

    const NativeURL = globalThis.URL
    vi.stubGlobal('URL', Object.assign(class TestURL extends NativeURL {}, {
      createObjectURL: vi.fn(() => 'blob:recording-duration'),
      revokeObjectURL: vi.fn()
    }))
    class TestAudio {
      duration = 2
      onloadedmetadata: (() => void) | null = null
      onerror: (() => void) | null = null
      set preload(_value: string) {}
      set src(_value: string) { queueMicrotask(() => this.onloadedmetadata?.()) }
      removeAttribute() {}
    }
    vi.stubGlobal('Audio', TestAudio)
    vi.mocked(api.uploadRecording).mockResolvedValue(doneRecording)

    const wrapper = await mountWorkbench({ recordings: [uploadedRecording] })
    expect(wrapper.text()).toContain('网页录音')
    await wrapper.get('.recorder-main-btn').trigger('click')
    await flushPromises()
    expect(wrapper.get('.recorder-main-btn').text()).toContain('暂停录音')

    now.mockReturnValue(33000)
    await wrapper.get('.recorder-main-btn').trigger('click')
    expect(wrapper.get('.recorder-timer').text()).toBe('00:32')
    expect(wrapper.text()).toContain('本次录音输入：USB Audio Device')

    await wrapper.get('.recorder-stop-btn').trigger('click')
    await vi.waitFor(() => expect(api.uploadRecording).toHaveBeenCalledOnce())

    expect(wrapper.get('.recorder-timer').text()).toBe('00:00')
    expect(wrapper.get('.recorder-live').text()).toContain('点击开始录音')
    expect(wrapper.find('.recording-input-current').exists()).toBe(false)
    expect(api.uploadRecording).toHaveBeenCalledWith(activeVisit.id, expect.any(File), 2000)
    wrapper.unmount()
  })
})

describe('transcript review navigation', () => {
  it('scrolls to a flagged utterance inside the dialogue region', async () => {
    const source = transcript()
    const wrapper = await mountWorkbench({
      transcript: {
        ...source,
        turns: [{ ...source.turns[0], role_review_required: true }]
      }
    })
    const container = wrapper.get('.transcript-body').element
    const dialogue = wrapper.get('.dialogue').element
    const scrollTo = vi.fn()
    Object.defineProperties(container, {
      scrollHeight: { configurable: true, value: 600 },
      clientHeight: { configurable: true, value: 200 },
      scrollTo: { configurable: true, value: scrollTo }
    })
    Object.defineProperties(dialogue, {
      offsetTop: { configurable: true, value: 400 },
      offsetHeight: { configurable: true, value: 40 }
    })

    await wrapper.get('.role-attention-action').trigger('click')

    expect(scrollTo).toHaveBeenCalledWith({ top: 320, behavior: 'smooth' })
  })
})

describe('llm route selection', () => {
  function routedTranscript(): Transcript {
    const source = transcript()
    return {
      ...source,
      turns: [{ ...source.turns[0], role_source: 'AUTO', role_review_required: true }],
      available_routes: ['DASHSCOPE', 'LOCAL'],
      route_selection_required: true,
      default_route: 'DASHSCOPE'
    }
  }

  it('uses the unified visit route for role recognition without a selector', async () => {
    const routed = routedTranscript()
    vi.mocked(api.reclassifyTranscriptRoles).mockResolvedValue(routed)
    const wrapper = await mountWorkbench({ transcript: routed })

    expect(wrapper.find('.route-selector').exists()).toBe(false)
    await wrapper.get('.role-attention-action').trigger('click')
    await flushPromises()

    expect(api.reclassifyTranscriptRoles).toHaveBeenCalledWith(activeVisit.id)
  })

  it('keeps the route selector hidden when only one route is available', async () => {
    const wrapper = await mountWorkbench()

    expect(wrapper.find('.route-selector').exists()).toBe(false)
  })

  it('shows only the unified route on the audio page', async () => {
    vi.mocked(api.transcribe).mockImplementation(() => new Promise(() => undefined))
    const wrapper = await mountWorkbench({
      recordings: [uploadedRecording],
      transcript: { ...transcript(), default_route: 'LOCAL' }
    })

    expect(wrapper.findAll('input[name="role-llm-provider"]')).toHaveLength(0)
    expect(wrapper.findAll('input[name="asr-provider"]')).toHaveLength(0)
    expect(wrapper.text()).toContain('本次接诊 AI 处理环境：公网')
    await wrapper.get('.transcribe-action button').trigger('click')

    expect(api.transcribe).toHaveBeenCalledWith(activeVisit.id)
  })

  it('places transcription inside the pending audio card beside its player', async () => {
    const wrapper = await mountWorkbench({
      visit: { ...activeVisit, ai_route: 'LOCAL' },
      recordings: [doneRecording, uploadedRecording]
    })
    await wrapper.get('.step:nth-child(2)').trigger('click')

    const note = wrapper.get('.audio-route-note')
    expect(note.text()).toContain('本次接诊 AI 处理环境：本地')
    expect(note.text()).toContain('转写、角色识别和信息提取统一使用此环境')
    expect(note.element.nextElementSibling?.classList.contains('audio-source-grid')).toBe(true)
    const audioCards = wrapper.findAll('.audio-item')
    const action = wrapper.get('.transcribe-action button')
    expect(audioCards).toHaveLength(2)
    expect(action.element.closest('.audio-item')).toBe(audioCards[1].element)
    expect(action.element.closest('.audio-playback-row')).toBe(audioCards[1].get('.audio-player').element.parentElement)
    expect(audioCards[0].find('.transcribe-action').exists()).toBe(false)
    expect(wrapper.find('.audio-recording-layout').exists()).toBe(false)
    expect(action.text()).toContain('开始转写')
  })

  it('keeps a single batch action inside the last pending audio card when multiple recordings need transcription', async () => {
    const failedRecording: Recording = { ...uploadedRecording, id: 'recording-3', file_name: 'failed.wav', status: 'FAILED' }
    const wrapper = await mountWorkbench({ recordings: [uploadedRecording, failedRecording, doneRecording] })
    await wrapper.get('.step:nth-child(2)').trigger('click')

    const action = wrapper.get('.transcribe-action button')
    expect(wrapper.findAll('.transcribe-action')).toHaveLength(1)
    expect(action.element.closest('.audio-item')).toBe(wrapper.findAll('.audio-item')[1].element)
    expect(action.text()).toContain('重试转写')
  })

  it('generates extraction with the unified route and no extraction selector', async () => {
    const routedExtraction: ClinicalExtraction = {
      ...pendingExtraction, available_routes: ['DASHSCOPE', 'LOCAL'], route_selection_required: true, default_route: 'LOCAL'
    }
    vi.mocked(api.generateClinicalExtraction).mockImplementation(() => new Promise(() => undefined))
    const wrapper = await mountWorkbench({ extraction: routedExtraction })

    await wrapper.get('.transcript-tabs button:nth-child(3)').trigger('click')
    expect(wrapper.get('.transcript-tabs button:nth-child(3)').text()).toBe('信息提取结果')
    expect(wrapper.get('.extraction-heading h3').text()).toBe('信息提取结果')
    expect(wrapper.get('.extraction-heading p').text()).toContain('原文依据')
    expect(wrapper.get('.extraction-action-buttons .btn').text()).toContain('生成信息提取')
    expect(wrapper.find('.extraction-actions .route-selector').exists()).toBe(false)
    await wrapper.get('.extraction-action-buttons > button.btn').trigger('click')

    expect(api.generateClinicalExtraction).toHaveBeenCalledWith(activeVisit.id)
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
  it('groups visits by doctor and defaults to actionable visits with doctor and status filters', async () => {
    const otherPatient: Patient = { ...patient, id: 'patient-2', patient_no: 'P-002', name: '另一位患者' }
    const otherDoctorVisit: Visit = {
      ...activeVisit, id: 'visit-2', patient_id: otherPatient.id, doctor_id: 'doctor-2', doctor_name: '李医生',
      visit_no: 'V-002', status: 'WAITING', last_activity_at: '2026-09-22T08:00:00Z'
    }
    const completedVisit: Visit = {
      ...activeVisit, id: 'visit-3', visit_no: 'V-003', status: 'COMPLETED',
      last_activity_at: '2026-09-23T08:00:00Z'
    }
    configure({ patients: [patient, otherPatient], visits: [activeVisit, otherDoctorVisit, completedVisit] })
    const head: Doctor = { ...doctor, role: 'DEPARTMENT_HEAD' }
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()

    expect(wrapper.findAll('.doctor-queue-group')).toHaveLength(2)
    expect(wrapper.findAll('.visit-tile')).toHaveLength(2)
    expect(wrapper.text()).toContain('测试医生')
    expect(wrapper.text()).toContain('李医生')
    expect(wrapper.text()).not.toContain('V-003')

    await wrapper.get('select[aria-label="按接诊状态筛选"]').setValue('ALL')
    expect(wrapper.findAll('.visit-tile')).toHaveLength(3)

    await wrapper.get('select[aria-label="按接诊医生筛选"]').setValue('doctor-2')
    expect(wrapper.findAll('.doctor-queue-group')).toHaveLength(1)
    expect(wrapper.findAll('.visit-tile')).toHaveLength(1)
    expect(wrapper.text()).toContain('另一位患者')
    wrapper.unmount()
  })

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
    expect(tile.find('.patient-tile-content').exists()).toBe(true)
    expect(tile.get('.patient-meta').attributes('title')).toContain(longVisit.visit_no)
    expect(wrapper.get('.doctor-group-name b').text()).toContain(longVisit.doctor_name)
    expect(tile.get('.patient-owner').attributes('title')).toContain('最近更新')
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
    expect(wrapper.text()).toContain('病历预览')
    expect(wrapper.findAll('button').some(button => button.text().includes('实时预览'))).toBe(true)
    expect(wrapper.findAll('button').some(button => button.text().includes('导出 Word'))).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text().includes('导出 PDF'))).toBe(false)
  })

  it('offers deletion only to a department head and confirms patient identity and visit count before calling the API', async () => {
    const secondVisit: Visit = { ...activeVisit, id: 'visit-2', visit_no: 'V-002', status: 'COMPLETED' }
    configure({ visits: [activeVisit, secondVisit] })
    const head: Doctor = { ...doctor, role: 'DEPARTMENT_HEAD' }
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()

    await wrapper.get('.patient-actions button.danger').trigger('click')
    expect(wrapper.text()).toContain('患者编号 P-001')
    expect(wrapper.text()).toContain('共包含 2 条接诊记录')
    expect(api.deletePatient).not.toHaveBeenCalled()

    await wrapper.get('.modal-actions button:first-child').trigger('click')
    expect(api.deletePatient).not.toHaveBeenCalled()
    wrapper.unmount()

    const doctorWrapper = await mountWorkbench()
    expect(doctorWrapper.find('.patient-actions button.danger').exists()).toBe(false)
  })

  it('deletes after the second confirmation, refreshes the queue, and clears the deleted patient details', async () => {
    configure()
    const head: Doctor = { ...doctor, role: 'DEPARTMENT_HEAD' }
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()
    vi.mocked(api.deletePatient).mockResolvedValue({ deletion_id: 'deletion-1', cleanup_status: 'COMPLETED' })
    vi.mocked(api.patients).mockResolvedValue([])
    vi.mocked(api.visits).mockResolvedValue([])

    await wrapper.get('.patient-actions button.danger').trigger('click')
    await wrapper.get('.modal-body input[type="checkbox"]').setValue(true)
    await wrapper.get('.modal-actions button.danger').trigger('click')
    await flushPromises()

    expect(api.deletePatient).toHaveBeenCalledWith(patient.id)
    expect(api.patients).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.patient-summary h2').text()).toContain('未选择患者')
    expect(wrapper.text()).not.toContain('测试患者')
    wrapper.unmount()
  })

  it('shows the persistent file cleanup message while deletion cleanup is pending', async () => {
    configure()
    const head: Doctor = { ...doctor, role: 'DEPARTMENT_HEAD' }
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()
    vi.mocked(api.deletePatient).mockResolvedValue({ deletion_id: 'deletion-2', cleanup_status: 'PENDING' })

    await wrapper.get('.patient-actions button.danger').trigger('click')
    await wrapper.get('.modal-body input[type="checkbox"]').setValue(true)
    await wrapper.get('.modal-actions button.danger').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('业务数据已删除，文件清理中。')
    expect(api.patientDeletionStatus).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('historical export downloads and read-only previews', () => {
  const template: ExportTemplate = {
    id: 'template-1', template_key: 'standard', name: '标准病历', description: '标准版式', status: 'ACTIVE',
    default_template: true, current_revision_id: 'revision-1', current_revision_no: 2,
    updated_at: '2026-09-21T08:00:00Z'
  }
  const historicalExport: RecordExport = {
    id: 'export-1', version_no: 1, template_id: template.id, template_revision_id: template.current_revision_id,
    template_name: template.name, template_revision_no: 2, format: 'PDF', status: 'SUCCEEDED',
    doctor_name: doctor.display_name, created_at: '2026-09-21T08:35:00Z'
  }
  const head: Doctor = { ...doctor, id: 'head-1', display_name: '科室长', role: 'DEPARTMENT_HEAD' }

  it.each([
    ['DOCTOR', 'PDF'], ['DOCTOR', 'DOCX'], ['DEPARTMENT_HEAD', 'PDF'], ['DEPARTMENT_HEAD', 'DOCX']
  ])('lets %s download a historical %s even when the current version is unsigned', async (role, format) => {
    configure({
      record: { ...record(true), version_no: 3 },
      visit: { ...activeVisit, status: 'COMPLETED' },
      exports: [{ ...historicalExport, format }]
    })
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: role === 'DOCTOR' ? doctor : head } })
    await flushPromises()
    await wrapper.get('.step:nth-child(5)').trigger('click')
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:historical-export')
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    let fileName = ''
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      fileName = this.download
    })
    vi.mocked(api.exportBlob).mockResolvedValue(new Blob(['historical content']))

    await wrapper.get(`button[aria-label="下载 v1.0 ${format}"]`).trigger('click')
    await flushPromises()

    expect(api.exportBlob).toHaveBeenCalledWith(historicalExport.id)
    expect(fileName).toBe(`【20260921】测试医生_测试患者_V1.${format === 'DOCX' ? 'docx' : 'pdf'}`)
    expect(createUrl).toHaveBeenCalledOnce()
    expect(revokeUrl).toHaveBeenCalledWith('blob:historical-export')
    expect(api.recordExport).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('only offers downloads for successful exports', async () => {
    const wrapper = await mountWorkbench({
      record: record(true, true),
      exports: ['SUCCEEDED', 'PENDING', 'RUNNING', 'FAILED'].map((status, index) => ({
        ...historicalExport, id: `export-${index}`, status
      }))
    })
    await wrapper.get('.step:nth-child(5)').trigger('click')

    expect(wrapper.findAll('.log-table button')).toHaveLength(1)
    expect(wrapper.findAll('.log-table tbody tr')).toHaveLength(4)
    wrapper.unmount()
  })

  it('keeps the download action available after a failed request', async () => {
    const wrapper = await mountWorkbench({ record: record(true, true), exports: [historicalExport] })
    await wrapper.get('.step:nth-child(5)').trigger('click')
    vi.mocked(api.exportBlob).mockRejectedValue(new Error('文件下载失败'))

    await wrapper.get('button[aria-label="下载 v1.0 PDF"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('button[aria-label="下载 v1.0 PDF"]').attributes('disabled')).toBeUndefined()
    expect(api.recordExport).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('lets a department head preview another doctor\'s signed record with the selected template', async () => {
    configure({ record: record(true, true), templates: [template] })
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()
    await wrapper.get('.step:nth-child(5)').trigger('click')
    vi.mocked(api.exportPreviewBlob).mockResolvedValue(new Blob(['%PDF']))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:read-only-preview')
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})

    await wrapper.get('.template-picker button').trigger('click')
    await flushPromises()

    expect(api.exportPreviewBlob).toHaveBeenCalledWith(activeVisit.id, 'PDF', template.current_revision_id)
    expect(wrapper.get('iframe[title="PDF 预览"]').attributes('src')).toBe('blob:read-only-preview')
    expect(wrapper.find('.export-options').exists()).toBe(false)
    expect(wrapper.find('.bottom-bar').exists()).toBe(false)
    expect(api.recordExport).not.toHaveBeenCalled()
    await wrapper.get('button[aria-label="关闭预览"]').trigger('click')
    expect(revokeUrl).toHaveBeenCalledWith('blob:read-only-preview')
    wrapper.unmount()
  })

  it('disables template preview until the current record is signed', async () => {
    configure({ record: record(true), templates: [template] })
    const wrapper = mount(ClinicalWorkbench, { props: { doctor: head } })
    await flushPromises()
    await wrapper.get('.step:nth-child(5)').trigger('click')

    expect(wrapper.get('.template-picker button').attributes('disabled')).toBeDefined()
    expect(api.exportPreviewBlob).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

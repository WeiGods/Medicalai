<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import Icon from './Icon.vue'
import type { Confirmation, Doctor, MedicalRecord, MedicalRecordContent, Patient, RecordExport, Recording, Transcript, Visit } from '../types'

const props = defineProps<{ doctor: Doctor }>()
const emit = defineEmits<{ (event: 'logout'): void }>()

type MainView = 'workbench' | 'audio' | 'transcript' | 'record' | 'confirm' | 'export' | 'audit' | 'config'
type WorkflowView = 'workbench' | 'audio' | 'transcript' | 'record' | 'confirm' | 'export'
type ModalKind = 'new-visit' | 'cancel' | 'finish' | 'regenerate' | 'confirm' | 'help' | 'activity' | null
type NewPatientMode = 'existing' | 'manual'
type ManualPatientForm = { name: string; gender: string; age: number | '' | null; phone: string; idNo: string }

const patients = ref<Patient[]>([])
const visits = ref<Visit[]>([])
const selectedPatientId = ref('')
const view = ref<MainView>('workbench')
const queueSearch = ref('')
const busy = ref(false)
const actionBusy = ref<'' | 'upload' | 'transcribe' | 'generate' | 'save' | 'confirm'>('')
const recordings = ref<Recording[]>([])
const transcript = ref<Transcript | null>(null)
const record = ref<MedicalRecord | null>(null)
const recordForm = ref<MedicalRecordContent | null>(null)
const confirmations = ref<Confirmation[]>([])
const exports = ref<RecordExport[]>([])
const transcriptTab = ref<'dialogue' | 'edit' | 'facts'>('dialogue')
const transcriptDraft = ref('')
const modal = ref<ModalKind>(null)
const modalError = ref('')
const confirmChecked = ref(false)
const newPatientId = ref('')
const newPatientMode = ref<NewPatientMode>('existing')
const newPatientForm = ref<ManualPatientForm>({ name: '', gender: '', age: null, phone: '', idNo: '' })
const dragging = ref(false)
const toastText = ref('')
const toastVisible = ref(false)
const playingId = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const modalDialog = ref<HTMLElement | null>(null)
const activity = ref<{ time: string; text: string }[]>([])
const recordingElapsedMs = ref(0)
let toastTimer: number | undefined
let recordingTimer: number | undefined
let recordingStartedAt = 0
let audioPlayer: HTMLAudioElement | null = null
let recorder: MediaRecorder | null = null
let recorderStream: MediaStream | null = null
let recordChunks: Blob[] = []
let audioObjectUrl: string | null = null
let audioLoadAbort: AbortController | null = null
let visitStateRevision = 0
const recordingState = ref<'idle' | 'recording' | 'paused'>('idle')


watch(modal, async value => {
  document.body.classList.toggle('modal-open', !!value)
  if (value === 'new-visit') {
    modalError.value = ''
    newPatientMode.value = 'existing'
    newPatientId.value = canCreateVisit(selectedPatientId.value)
      ? selectedPatientId.value
      : patients.value.find(patient => canCreateVisit(patient.id))?.id || ''
    newPatientForm.value = { name: '', gender: '', age: null, phone: '', idNo: '' }
  }
  if (value) {
    await nextTick()
    modalDialog.value?.focus()
  }
})

const titles: Record<MainView, [string, string]> = {
  workbench: ['接诊工作台', '从医患对话到结构化病历，让每一次接诊更从容。'],
  audio: ['录音上传', '完整上传问诊录音，支持一次接诊关联多段录音。'],
  transcript: ['转写结果', '回看医患对话，核对并编辑本次接诊采用的转写文本。'],
  record: ['病历展示与编辑', '按照标准模板整理记录，由医生核对与完善。'],
  confirm: ['医生确认', '核对必填信息，确认本次接诊的病历版本。'],
  export: ['病历导出', '将已确认的病历由后端生成 Word 或 PDF 文件。'],
  audit: ['日志审计', '查看当前接诊的确认与导出审计记录。'],
  config: ['系统配置', '查看当前服务的接入环境与健康状态。']
}

const recordFields = [
  { key: 'name', label: '患者姓名', required: true, input: true },
  { key: 'gender', label: '性别', required: true, input: true },
  { key: 'age', label: '年龄', required: true, input: true },
  { key: 'phone', label: '联系方式', required: true, input: true },
  { key: 'chief', label: '患者主诉', required: true, input: false },
  { key: 'present', label: '患者现病史', required: false, input: false },
  { key: 'past', label: '患者既往史', required: false, input: false },
  { key: 'opinion', label: '医生处理意见', required: false, input: false },
  { key: 'medication', label: '用药情况', required: false, input: false },
  { key: 'followup', label: '复诊建议', required: false, input: false },
  { key: 'doctor', label: '接诊医生', required: true, input: true },
  { key: 'date', label: '接诊日期', required: true, input: true }
] as const

/** Pick the visit that represents a patient in the queue.
 * An active visit wins over waiting, followed by the most recent historical visit.
 * This keeps patient-level status correct when a patient has more than one visit.
 */
function patientVisit(patientId: string): Visit | null {
  if (!patientId) return null
  const list = visits.value
    // Cancelled visits are deleted by the backend and are ignored here for
    // compatibility with older data that may still contain a cancelled row.
    .filter(visit => visit.patient_id === patientId && visit.status !== 'CANCELLED')
    .slice()
    .sort((a, b) => {
      const priority = (status: string) => status === 'ACTIVE' ? 0 : status === 'WAITING' ? 1 : 2
      const statusDelta = priority(a.status) - priority(b.status)
      if (statusDelta) return statusDelta
      return String(b.created_at || '').localeCompare(String(a.created_at || ''))
    })
  return list[0] || null
}

function patientStatus(patientId: string) {
  return patientVisit(patientId)?.status || 'UNSCHEDULED'
}

function statusClass(visit: Visit | null) {
  const status = visit?.status || 'WAITING'
  return ({ WAITING: 'waiting', ACTIVE: 'active', COMPLETED: 'completed', CANCELLED: 'cancelled', ARCHIVED: 'archived' } as Record<string, string>)[status] || 'waiting'
}

function exportStatusLabel(status: string) {
  return ({
    PENDING: '排队中',
    RUNNING: '生成中',
    SUCCEEDED: '已完成',
    FAILED: '失败'
  } as Record<string, string>)[status] || status
}

function compactVisitNo(value: string | null | undefined) {
  if (!value) return '待创建'
  const visitNo = String(value)
  if (visitNo.length <= 19) return visitNo
  return `${visitNo.slice(0, 15)}…`
}

function patientVisitNo(patientId: string) {
  return compactVisitNo(patientVisit(patientId)?.visit_no)
}

const currentPatient = computed(() => patients.value.find(p => p.id === selectedPatientId.value) || null)
const canCreateVisit = (patientId: string) => !patientVisit(patientId)
const currentVisit = computed(() => {
  return patientVisit(selectedPatientId.value)
})
const queuePatients = computed(() => {
  const keyword = queueSearch.value.trim().toLowerCase()
  if (!keyword) return patients.value
  return patients.value.filter(p => [p.name, p.patient_no, p.phone_masked]
    .some(value => String(value || '').toLowerCase().includes(keyword)))
})
const closed = computed(() => !!currentVisit.value && ['COMPLETED', 'CANCELLED', 'ARCHIVED'].includes(currentVisit.value.status))
const completed = computed(() => currentVisit.value?.status === 'COMPLETED')
const waiting = computed(() => !currentVisit.value || currentVisit.value.status === 'WAITING')
const active = computed(() => currentVisit.value?.status === 'ACTIVE')
const allTranscribed = computed(() => recordings.value.length > 0 && recordings.value.every(r => r.status === 'DONE'))
const confirmed = computed(() => !!record.value?.confirmed)
const exported = computed(() => !!record.value && exports.value.some(item =>
  item.version_no === record.value?.version_no && item.status === 'SUCCEEDED'))
const sourceDirty = computed(() => !!record.value?.source_dirty)
const locked = computed(() => closed.value || confirmed.value || !!actionBusy.value)
const workflowView = computed<WorkflowView>(() => {
  if (waiting.value) return 'workbench'
  if (!recordings.value.length || !allTranscribed.value) return 'audio'
  if (sourceDirty.value) return 'transcript'
  if (!record.value?.record_id) return 'transcript'
  if (!confirmed.value) return view.value === 'confirm' ? 'confirm' : 'record'
  return 'export'
})
const stage = computed(() => {
  const stages: WorkflowView[] = ['workbench', 'audio', 'transcript', 'record', 'confirm', 'export']
  // A completed visit is immutable, but its retained artifacts are still
  // reviewable. Highlight the page the doctor is reviewing rather than
  // always pinning the progress indicator to the final step.
  if (completed.value && view.value !== 'workbench' && stages.includes(view.value as WorkflowView)) {
    return stages.indexOf(view.value as WorkflowView)
  }
  return stages.indexOf(workflowView.value)
})
const activeCount = computed(() => patients.value.filter(patient => patientVisit(patient.id)?.status === 'ACTIVE').length)
const waitingCount = computed(() => patients.value.filter(patient => patientVisit(patient.id)?.status === 'WAITING').length)
const openVisitCount = computed(() => patients.value.filter(patient => ['ACTIVE', 'WAITING'].includes(patientVisit(patient.id)?.status || '')).length)
const completedCount = computed(() => patients.value.filter(patient => patientVisit(patient.id)?.status === 'COMPLETED').length)
const missingFields = computed(() => {
  const content = recordForm.value
  if (!content) return []
  const missing: string[] = []
  if (!content.name.trim()) missing.push('患者姓名')
  if (!content.gender.trim()) missing.push('性别')
  if (content.age === null || Number.isNaN(content.age) || content.age < 0 || content.age > 150) missing.push('有效年龄')
  if (!content.phone.trim()) missing.push('联系方式')
  if (!content.chief.trim()) missing.push('患者主诉')
  if (!content.doctor.trim()) missing.push('接诊医生')
  if (!content.date.trim()) missing.push('接诊日期')
  return missing
})
const segments = computed(() => {
  const source = transcript.value
  if (!source) return []
  if (source.turns.length && !source.edited) {
    return source.turns.map(item => ({ role: item.role === 'DOCTOR' ? '医生' : item.role === 'PATIENT' ? '患者' : '其他人', time: formatTime(item.start_ms), text: item.text }))
  }
  return source.transcript.split(/\n+/).filter(Boolean).map((line, index) => {
    const match = line.match(/^(医生|患者)[：:]\s*(.*)$/)
    return { role: match ? match[1] : '其他人', time: source.turns[index] ? formatTime(source.turns[index].start_ms) : '00:00', text: match ? match[2] : line }
  })
})
const facts = computed(() => {
  const lines = segments.value.filter(item => item.role === '患者').map(item => item.text)
  return [
    ['症状与主诉', lines[0] || ''],
    ['时间线与伴随症状', lines[1] || ''],
    ['病史与生活情况', lines.slice(2).join(' ') || '']
  ].filter(([, value]) => value)
})

const statusText = (visit: Visit | null) => {
  if (!visit) return '待接诊'
  return ({ WAITING: '待接诊', ACTIVE: '接诊中', COMPLETED: '已完成', CANCELLED: '已取消', ARCHIVED: '已归档' })[visit.status] || visit.status
}
const formatBytes = (size: number | null) => size == null ? '—' : `${(size / 1024 / 1024).toFixed(1)} MB`
const formatDuration = (ms: number | null) => {
  if (!ms || ms <= 0) return '—'
  return `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor((ms % 60000) / 1000)).padStart(2, '0')}`
}
const formatRecordingDuration = (ms: number) => `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor((ms % 60000) / 1000)).padStart(2, '0')}`
const formatTime = (ms: number) => `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor((ms % 60000) / 1000)).padStart(2, '0')}`
const formatDateTime = (value: string | null) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : ''
const visitDate = computed(() => currentVisit.value ? currentVisit.value.created_at.slice(0, 10) : new Date().toISOString().slice(0, 10))
const isPlaying = (item: Recording) => playingId.value === item.id
const avatarClass = (patient: Patient | null) => patient?.patient_no === '002' ? 'lilac' : patient?.patient_no === '003' ? 'blue' : ''
const highlightText = (text: string) => [{ text, mark: false }]
const recordingStateLabel = computed(() => ({ idle: '准备录音', recording: '正在录音', paused: '已暂停' })[recordingState.value])
const recordingTimeLabel = computed(() => formatRecordingDuration(recordingElapsedMs.value))

function toast(text: string) {
  toastText.value = text
  toastVisible.value = true
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => { toastVisible.value = false }, 3400)
}

function addLog(text: string) {
  activity.value.unshift({ time: new Date().toLocaleString('zh-CN', { hour12: false }), text })
}

async function refreshCore(keepSelection = true) {
  const [patientList, visitList] = await Promise.all([api.patients(), api.visits()])
  patients.value = patientList
  visits.value = visitList
  if (!keepSelection || !patientList.some(p => p.id === selectedPatientId.value)) {
    selectedPatientId.value = patientList[0]?.id || ''
  }
  newPatientId.value = selectedPatientId.value
}

async function refreshVisitState() {
  const visit = currentVisit.value
  const revision = ++visitStateRevision
  if (!visit) {
    recordings.value = []
    transcript.value = null
    record.value = null
    recordForm.value = null
    confirmations.value = []
    exports.value = []
    transcriptDraft.value = ''
    return
  }
  const [recordingList, transcriptState, recordState, confirmationList, exportList] = await Promise.all([
    api.recordings(visit.id),
    api.transcript(visit.id),
    api.medicalRecord(visit.id),
    api.confirmations(visit.id),
    api.exports(visit.id)
  ])
  if (revision !== visitStateRevision || currentVisit.value?.id !== visit.id) return
  recordings.value = recordingList
  transcript.value = transcriptState
  record.value = recordState
  recordForm.value = recordState.content ? JSON.parse(JSON.stringify(recordState.content)) : null
  confirmations.value = confirmationList
  exports.value = exportList
  transcriptDraft.value = transcriptState.transcript
}

async function loadAll(keepSelection = true) {
  busy.value = true
  try {
    await refreshCore(keepSelection)
    await refreshVisitState()
  } catch (error) {
    toast(error instanceof Error ? error.message : '数据加载失败')
  } finally {
    busy.value = false
  }
}

async function selectPatient(patientId: string) {
  if (busy.value || actionBusy.value) return
  if (recordingState.value !== 'idle' || recorder) {
    toast('请先结束当前网页录音，再切换患者。')
    return
  }
  stopAudio()
  visitStateRevision += 1
  selectedPatientId.value = patientId
  newPatientId.value = patientId
  view.value = 'workbench'
  transcriptTab.value = 'dialogue'
  await loadAll(true)
  navigate(workflowView.value)
}

function navigate(next: MainView) {
  const workflowViews: WorkflowView[] = ['workbench', 'audio', 'transcript', 'record', 'confirm', 'export']
  if (!workflowViews.includes(next as WorkflowView)) {
    view.value = next
  } else {
    const requested = next as WorkflowView
    const current = workflowView.value
    if (completed.value && requested !== 'workbench') {
      view.value = requested
    } else if (completed.value) {
      view.value = workflowView.value
      toast('已完成接诊仅支持查看步骤 02 至 06。')
    } else {
    const sameStep = requested === current
    const reviewTransition = (requested === 'record' || requested === 'confirm')
      && (current === 'record' || current === 'confirm')
      && !!record.value?.record_id && !confirmed.value
    if (sameStep || reviewTransition) view.value = requested
    else {
      view.value = current
      toast('请按当前接诊流程完成本步骤后再继续。')
    }
    }
  }
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function showWorkflowStep() {
  navigate(workflowView.value)
}

async function createAndStart(patientId: string) {
  busy.value = true
  try {
    const visit = await api.createVisit(patientId)
    await api.startVisit(visit.id)
    selectedPatientId.value = patientId
    await loadAll(true)
    navigate('audio')
    toast('新接诊已创建，可上传录音。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '创建接诊失败')
  } finally {
    busy.value = false
  }
}

async function startVisit() {
  if (!currentPatient.value) return
  if (!currentVisit.value) {
    await createAndStart(currentPatient.value.id)
    return
  }
  busy.value = true
  try {
    await api.startVisit(currentVisit.value.id)
    await loadAll(true)
    navigate('audio')
    toast(`已开始 ${currentPatient.value.name} 的接诊，可上传录音。`)
  } catch (error) {
    toast(error instanceof Error ? error.message : '开始接诊失败')
  } finally {
    busy.value = false
  }
}

async function doCancel() {
  if (!currentVisit.value) return
  if (recordingState.value !== 'idle' || recorder) {
    modalError.value = '请先结束当前网页录音，再取消接诊。'
    return
  }
  busy.value = true
  try {
    await api.cancelVisit(currentVisit.value.id)
    modal.value = null
    await loadAll(true)
    navigate('workbench')
    toast('本次接诊及其录音、转写、病历和导出文件已清空。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '取消接诊失败')
  } finally {
    busy.value = false
  }
}

async function doFinish() {
  if (!currentVisit.value) return
  busy.value = true
  try {
    await api.completeVisit(currentVisit.value.id)
    modal.value = null
    await loadAll(true)
    navigate('export')
    toast('本次接诊已完成，完整记录已保留。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '结束接诊失败')
  } finally {
    busy.value = false
  }
}

async function createNewVisit() {
  modalError.value = ''
  if (newPatientMode.value === 'manual') {
    const form = newPatientForm.value
    const name = form.name.trim()
    const gender = form.gender.trim()
    const rawAge = form.age
    const age = rawAge === null || rawAge === '' ? null : Number(rawAge)
    if (!name) {
      modalError.value = '请输入患者姓名。'
      return
    }
    if (!gender) {
      modalError.value = '请选择患者性别。'
      return
    }
    if (age !== null && (!Number.isInteger(age) || age < 0 || age > 150)) {
      modalError.value = '年龄请输入 0 到 150 之间的整数。'
      return
    }
    busy.value = true
    try {
      const patient = await api.createPatient({ name, gender, age, phone: form.phone, idNo: form.idNo })
      selectedPatientId.value = patient.id
      newPatientId.value = patient.id
      await createAndStart(patient.id)
      modal.value = null
    } catch (error) {
      modalError.value = error instanceof Error ? error.message : '创建患者失败'
    } finally {
      busy.value = false
    }
    return
  }
  if (!newPatientId.value) {
    modalError.value = '请选择一位患者。'
    return
  }
  if (!canCreateVisit(newPatientId.value)) {
    modalError.value = '该患者已有接诊，完成接诊后不支持重复接诊。'
    return
  }
  modal.value = null
  await createAndStart(newPatientId.value)
}

function openNewVisitModal() {
  modalError.value = ''
  newPatientMode.value = 'existing'
  newPatientId.value = canCreateVisit(selectedPatientId.value)
    ? selectedPatientId.value
    : patients.value.find(patient => canCreateVisit(patient.id))?.id || ''
  newPatientForm.value = { name: '', gender: '', age: null, phone: '', idNo: '' }
  modal.value = 'new-visit'
}

function triggerUpload() {
  if (!locked.value) fileInput.value?.click()
}

async function audioDuration(file: File, fallbackDuration = 0) {
  return new Promise<number>(resolve => {
    const audio = new Audio()
    const url = URL.createObjectURL(file)
    const done = (value: number) => {
      audio.removeAttribute('src')
      URL.revokeObjectURL(url)
      resolve(value)
    }
    audio.preload = 'metadata'
    audio.onloadedmetadata = () => done(Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration * 1000 : fallbackDuration)
    audio.onerror = () => done(fallbackDuration)
    audio.src = url
  })
}

async function uploadFiles(files: FileList | File[] | null, fallbackDuration = 0) {
  if (!currentVisit.value || locked.value) return
  for (const file of Array.from(files || [])) {
    if (!/\.(wav|mp3|m4a|webm)$/i.test(file.name)) {
      toast('仅支持 MP3、WAV、M4A 或 WEBM 格式。')
      continue
    }
    const duration = await audioDuration(file, fallbackDuration)
    if (duration <= 0) {
      toast(`无法读取「${file.name}」，请选用有效音频。`)
      continue
    }
    actionBusy.value = 'upload'
    try {
      await api.uploadRecording(currentVisit.value.id, file, duration)
      addLog(`上传录音：${file.name}`)
    } catch (error) {
      toast(error instanceof Error ? error.message : '上传失败')
    }
  }
  actionBusy.value = ''
  await loadAll(true)
  if (view.value === 'workbench') toast('录音已上传，可开始转写。')
}

async function startTranscription() {
  if (!currentVisit.value || locked.value) return
  actionBusy.value = 'transcribe'
  try {
    const visitId = currentVisit.value.id
    const job = await api.transcribe(visitId)
    await loadAll(true)
    addLog('已提交真实 ASR 转写任务')
    for (let attempt = 0; attempt < 240; attempt++) {
      await new Promise(resolve => window.setTimeout(resolve, 3000))
      const state = await api.transcribeStatus(visitId, job.job_id)
      await loadAll(true)
      if (state.status === 'SUCCEEDED') {
        transcript.value = state.transcript
        transcriptDraft.value = state.transcript?.transcript || ''
        addLog('完成真实 ASR 转写')
        navigate('transcript')
        toast('转写完成，请核对文本并生成病历。')
        return
      }
      if (state.status === 'FAILED') throw new Error(state.error_message || '转写失败')
    }
    throw new Error('转写等待超时，请稍后查看任务状态')
  } catch (error) {
    // The server may have recovered a stale PROCESSING row before rejecting
    // this submission (for example, when storage credentials are missing).
    // Reload so the card immediately returns to a retryable state.
    await loadAll(true)
    toast(error instanceof Error ? error.message : '转写失败')
  } finally {
    actionBusy.value = ''
  }
}

async function saveTranscript() {
  if (!currentVisit.value || locked.value) return
  if (!transcriptDraft.value.trim()) {
    toast('采用的转写文本不能为空。')
    return
  }
  actionBusy.value = 'save'
  try {
    transcript.value = await api.saveTranscript(currentVisit.value.id, transcriptDraft.value.trim())
    addLog('保存采用的转写文本')
    await loadAll(true)
    navigate('transcript')
    toast('转写文本已保存，可生成病历。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存转写失败')
  } finally {
    actionBusy.value = ''
  }
}

async function copyTranscript() {
  if (!transcriptDraft.value) return
  await navigator.clipboard.writeText(transcriptDraft.value)
  toast('转写文本已复制。')
}

function refreshRecordingElapsed() {
  if (recordingState.value !== 'recording') return
  recordingElapsedMs.value = Math.max(0, Date.now() - recordingStartedAt)
}

function startRecordingTimer() {
  window.clearInterval(recordingTimer)
  recordingStartedAt = Date.now() - recordingElapsedMs.value
  recordingTimer = window.setInterval(refreshRecordingElapsed, 100)
}

function pauseRecordingTimer() {
  refreshRecordingElapsed()
  window.clearInterval(recordingTimer)
  recordingTimer = undefined
}

function resetRecordingTimer() {
  window.clearInterval(recordingTimer)
  recordingTimer = undefined
  recordingStartedAt = 0
}

async function startRecording() {
  if (!currentVisit.value || locked.value) return
  if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
    toast('当前浏览器不支持录音功能。')
    return
  }
  try {
    recorderStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    recordChunks = []
    const supportedMimeType = [
      'audio/webm;codecs=opus',
      'audio/webm',
      'audio/mp4'
    ].find(type => MediaRecorder.isTypeSupported(type))
    recorder = supportedMimeType
      ? new MediaRecorder(recorderStream, { mimeType: supportedMimeType })
      : new MediaRecorder(recorderStream)
    recorder.ondataavailable = event => { if (event.data.size) recordChunks.push(event.data) }
    recorder.onstop = () => {
      pauseRecordingTimer()
      recorderStream?.getTracks().forEach(track => track.stop())
      recorderStream = null
      recordingState.value = 'idle'
      const type = recorder?.mimeType || 'audio/webm'
      const extension = type.includes('mp4') ? 'm4a' : 'webm'
      const file = new File([new Blob(recordChunks, { type })], `${currentPatient.value?.name || '问诊'}_浏览器录音.${extension}`, { type })
      recorder = null
      if (file.size) uploadFiles([file], recordingElapsedMs.value)
    }
    recorder.start()
    recordingElapsedMs.value = 0
    recordingState.value = 'recording'
    startRecordingTimer()
  } catch {
    recorderStream?.getTracks().forEach(track => track.stop())
    recorderStream = null
    recorder = null
    resetRecordingTimer()
    toast('无法访问麦克风，请检查浏览器权限。')
  }
}

function resumeRecording() {
  pauseRecording()
}

function pauseRecording() {
  if (!recorder) return
  if (recorder.state === 'recording') {
    recorder.pause()
    pauseRecordingTimer()
    recordingState.value = 'paused'
  } else if (recorder.state === 'paused') {
    recorder.resume()
    recordingState.value = 'recording'
    startRecordingTimer()
  }
}

function stopRecording() {
  if (recorder && recorder.state !== 'inactive') {
    pauseRecordingTimer()
    recorder.stop()
  }
}

async function generateRecord() {
  if (!currentVisit.value || locked.value) return
  actionBusy.value = 'generate'
  try {
    record.value = await api.generateMedicalRecord(currentVisit.value.id)
    recordForm.value = record.value.content ? JSON.parse(JSON.stringify(record.value.content)) : null
    addLog(`生成病历草稿 v${record.value.version_no}`)
    await loadAll(true)
    navigate('record')
    toast('病历草稿已生成，请医生核对。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '病历生成失败')
  } finally {
    actionBusy.value = ''
  }
}

async function saveDraft() {
  if (!currentVisit.value || !recordForm.value || locked.value) return
  actionBusy.value = 'save'
  try {
    record.value = await api.saveMedicalRecord(currentVisit.value.id, recordForm.value)
    recordForm.value = record.value.content ? JSON.parse(JSON.stringify(record.value.content)) : null
    await loadAll(true)
    navigate('record')
    toast('病历草稿已保存。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存病历失败')
  } finally {
    actionBusy.value = ''
  }
}

async function editRecord() {
  if (!currentVisit.value || closed.value || busy.value || actionBusy.value) return
  busy.value = true
  try {
    record.value = await api.editMedicalRecord(currentVisit.value.id)
    recordForm.value = record.value.content ? JSON.parse(JSON.stringify(record.value.content)) : null
    await loadAll(true)
    navigate('record')
    toast('已进入修改状态，修改完成后请重新确认。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '进入修改失败')
  } finally {
    busy.value = false
  }
}

function requestConfirm() {
  if (missingFields.value.length) {
    navigate('record')
    toast('请完善：' + missingFields.value.join('、'))
    return
  }
  if (view.value !== 'confirm') {
    navigate('confirm')
    return
  }
  modalError.value = ''
  confirmChecked.value = false
  modal.value = 'confirm'
}

async function doConfirm() {
  if (!currentVisit.value || !confirmChecked.value) {
    modalError.value = '请勾选核对声明后确认。'
    return
  }
  actionBusy.value = 'confirm'
  try {
    record.value = await api.confirmMedicalRecord(currentVisit.value.id, true)
    addLog(`${props.doctor.display_name}医生确认病历 v${record.value.version_no}`)
    modal.value = null
    await loadAll(true)
    navigate('export')
    toast('医生确认已保存，现在可以导出当前版本。')
  } catch (error) {
    modalError.value = error instanceof Error ? error.message : '确认失败'
  } finally {
    actionBusy.value = ''
  }
}

async function recordExportLog(format: 'DOCX' | 'PDF') {
  if (!currentVisit.value) return
  exports.value = await api.recordExport(currentVisit.value.id, format)
  addLog(format === 'DOCX' ? '发起 Word 导出' : '发起 PDF 导出')
}

async function waitForExport(format: 'DOCX' | 'PDF') {
  if (!currentVisit.value || !record.value) return null
  const versionNo = record.value.version_no
  let item = exports.value.find(e => e.format === format && e.version_no === versionNo)
  // Reuse an already completed export (especially for completed visits) instead
  // of creating duplicate jobs every time the user opens the export page.
  if (item?.status === 'SUCCEEDED') return item.id
  if (!item || item.status === 'FAILED') {
    await recordExportLog(format)
    item = exports.value.find(e => e.format === format && e.version_no === versionNo)
  }
  if (!item) return null
  for (let attempt = 0; attempt < 30; attempt++) {
    const state = await api.exportStatus(item.id)
    if (state.status === 'SUCCEEDED') return item.id
    if (state.status === 'FAILED') throw new Error(state.error_message || '病历导出失败')
    await new Promise(resolve => window.setTimeout(resolve, 500))
  }
  throw new Error('导出处理超时，请稍后重试')
}

async function downloadExport(exportId: string, format: 'DOCX' | 'PDF') {
  const blob = await api.exportBlob(exportId)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `medical-record-${exportId}.${format === 'DOCX' ? 'docx' : 'pdf'}`
  link.click()
  URL.revokeObjectURL(url)
}

async function exportWord() {
  if (busy.value || !record.value?.content || !confirmed.value) return
  busy.value = true
  try {
    const exportId = await waitForExport('DOCX')
    if (exportId) await downloadExport(exportId, 'DOCX')
    await loadAll(true)
    toast('Word 病历已生成并下载。')
  } catch (error) { toast(error instanceof Error ? error.message : 'Word 导出失败') }
  finally { busy.value = false }
}

async function exportPdf() {
  if (busy.value || !record.value?.content || !confirmed.value) return
  busy.value = true
  try {
    const exportId = await waitForExport('PDF')
    if (exportId) await downloadExport(exportId, 'PDF')
    await loadAll(true)
    toast('PDF 病历已生成并下载。')
  } catch (error) { toast(error instanceof Error ? error.message : 'PDF 导出失败') }
  finally { busy.value = false }
}

async function exportFromBottom() {
  if (!confirmed.value || busy.value) return
  if (view.value !== 'export') {
    navigate('export')
    return
  }
  // The bottom action is available on every workflow page. Once already on
  // the export page, make it useful by exporting the default Word document;
  // PDF remains available through the explicit format card above.
  await exportWord()
}

async function playRecording(item: Recording) {
  if (!item.audio_url) return
  if (playingId.value === item.id) {
    stopAudio()
    return
  }
  stopAudio()
  playingId.value = item.id
  const controller = new AbortController()
  audioLoadAbort = controller
  try {
    // The audio endpoint is authenticated. Fetching the bytes first lets us
    // attach the bearer token; a native Audio(src) request cannot do that.
    const blob = await api.audioBlob(item.id, controller.signal)
    if (controller.signal.aborted || playingId.value !== item.id) return
    audioObjectUrl = URL.createObjectURL(blob)
    const player = new Audio(audioObjectUrl)
    player.preload = 'auto'
    audioPlayer = player
    player.onended = () => {
      if (audioPlayer === player) stopAudio()
    }
    player.onerror = () => {
      if (audioPlayer !== player) return
      const mediaError = player.error
      console.warn('[audio] playback failed', {
        recordingId: item.id,
        code: mediaError?.code,
        message: mediaError?.message,
        blobType: blob.type,
        blobSize: blob.size
      })
      stopAudio()
      toast('音频无法播放，请检查录音格式或后端音频接口。')
    }
    await player.play()
  } catch (error) {
    if (controller.signal.aborted) return
    console.warn('[audio] audio request failed', { recordingId: item.id, error })
    if (playingId.value === item.id) {
      stopAudio()
      toast(error instanceof Error ? error.message : '音频无法播放。')
    }
  } finally {
    if (audioLoadAbort === controller) audioLoadAbort = null
  }
}

function stopAudio() {
  audioLoadAbort?.abort()
  audioLoadAbort = null
  if (audioPlayer) {
    audioPlayer.pause()
    audioPlayer.onended = null
    audioPlayer.onerror = null
    audioPlayer.removeAttribute('src')
    audioPlayer.load()
  }
  if (audioObjectUrl) URL.revokeObjectURL(audioObjectUrl)
  audioObjectUrl = null
  audioPlayer = null
  playingId.value = ''
}

function stopRecorder() {
  pauseRecordingTimer()
  if (recorder && recorder.state !== 'inactive') recorder.stop()
  else {
    recorderStream?.getTracks().forEach(track => track.stop())
    recorderStream = null
    recorder = null
    recordingState.value = 'idle'
  }
}

function updateField(key: string, event: Event) {
  if (!recordForm.value || locked.value) return
  const target = event.target as HTMLInputElement | HTMLTextAreaElement
  if (key === 'age') recordForm.value.age = target.value === '' ? null : Number(target.value)
  else (recordForm.value as unknown as Record<string, string>)[key] = target.value
}

onMounted(async () => {
  await loadAll(false)
  navigate(workflowView.value)
})

onUnmounted(() => {
  window.clearTimeout(toastTimer)
  stopAudio()
  stopRecorder()
  resetRecordingTimer()
  document.body.classList.remove('modal-open')
})

defineExpose({ selectPatient })
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark"><Icon name="pulse" /></div>
        <div><strong>听诊助手</strong><small>CLINICAL COPILOT</small></div>
      </div>
      <div class="nav-label">工作空间</div>
      <nav class="nav-list">
        <button class="nav-item" :class="{ active: view === 'workbench' }" @click="showWorkflowStep()">
          <Icon name="users" /><span>患者 / 接诊</span><span class="nav-count">{{ String(openVisitCount).padStart(2, '0') }}</span>
        </button>
        <button class="nav-item" :class="{ active: view === 'audit' }" @click="navigate('audit')"><Icon name="list" />日志审计</button>
        <button class="nav-item" :class="{ active: view === 'config' }" @click="navigate('config')"><Icon name="gear" />系统配置</button>
      </nav>
      <div class="recent">
        <div class="nav-label">最近接诊</div>
        <button v-for="patient in patients.slice(0, 3)" :key="patient.id" class="recent-item" @click="selectPatient(patient.id)">
          <span class="avatar" :class="avatarClass(patient)">{{ patient.name[0] }}</span><span>{{ patient.name }}</span>
          <span class="dot" :class="{ green: patientStatus(patient.id) === 'ACTIVE' }"></span>
        </button>
      </div>
      <div class="sidebar-bottom">
        <div class="care-note"><strong>让记录回归简单</strong><p>多一点时间，留给患者。</p><Icon name="pulse" /></div>
        <div class="side-footer"><button class="text-btn" @click="modal='help'"><Icon name="help" /> 使用帮助</button></div>
      </div>
    </aside>

    <header class="header">
      <div class="breadcrumb"><Icon name="building" /><span>{{ doctor.department_name }}</span><Icon name="chevron" /><b>{{ titles[view][0] }}</b></div>
      <div class="header-right">
        <span class="header-date">{{ new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }) }}</span>
        <div class="doctor-profile">
          <span class="avatar">{{ doctor.display_name.slice(0, 1) }}</span>
          <div><b>{{ doctor.display_name }} 医生</b><span>{{ doctor.department_name }} · 主治医师</span></div>
        </div>
        <button class="text-btn" @click="emit('logout')">退出</button>
      </div>
    </header>

    <main class="main">
      <div class="page-heading">
        <div>
          <div class="eyebrow"><i class="dot green"></i> CLINICAL WORKSPACE</div>
          <h1>{{ titles[view][0] }}</h1>
          <p>{{ titles[view][1] }}</p>
        </div>
        <div class="heading-actions" aria-hidden="true"></div>
      </div>

      <section class="card queue">
        <div class="queue-head">
          <div class="queue-title"><Icon name="users" />今日接诊 <span class="badge">{{ patients.length }} 位</span>
            <div class="queue-stats"><span>待接诊 <b>{{ String(waitingCount).padStart(2, '0') }}</b></span><i></i><span>接诊中 <b>{{ String(activeCount).padStart(2, '0') }}</b></span><i></i><span>已完成 <b>{{ String(completedCount).padStart(2, '0') }}</b></span></div>
          </div>
          <div class="queue-search-wrap">
            <input v-model="queueSearch" class="queue-search" placeholder="搜索姓名或编号" />
            <button class="btn small" @click="queueSearch=queueSearch">搜索</button>
          </div>
          <button class="queue-add" :disabled="busy" @click="openNewVisitModal"><Icon name="plus" />新增接诊</button>
        </div>
        <div class="queue-cards">
          <button v-for="patient in queuePatients" :key="patient.id" class="patient-tile" :class="{ selected: patient.id === selectedPatientId }" @click="selectPatient(patient.id)">
            <span class="avatar" :class="avatarClass(patient)">{{ patient.name[0] }}</span>
            <span>
              <span class="patient-name"><b>{{ patient.name }}</b><span>{{ patient.gender }} · {{ patient.age ?? '—' }} 岁</span></span>
              <span class="patient-meta">患者编号 <strong>{{ patient.patient_no || '—' }}</strong></span>
              <span class="patient-visit-no" :title="patientVisit(patient.id)?.visit_no || '暂无接诊记录'">接诊 {{ patientVisitNo(patient.id) }}</span>
            </span>
            <span class="tile-status" :class="statusClass(patientVisit(patient.id))">
              {{ statusText(patientVisit(patient.id)) }}
            </span>
          </button>
          <div v-if="!queuePatients.length" class="small-muted">暂无匹配患者</div>
        </div>
        <div class="queue-load-more">{{ queuePatients.length > 6 ? '向下滚动查看更多' : '' }}</div>
      </section>

      <section class="patient-summary">
        <div class="patient-identity">
          <span class="avatar big" :class="avatarClass(currentPatient as Patient)">{{ currentPatient?.name[0] || '—' }}</span>
          <div class="patient-info">
            <h2>{{ currentPatient?.name || '未选择患者' }}<small>{{ currentPatient?.gender || '' }} / {{ currentPatient?.age ?? '—' }} 岁</small>
              <span class="badge" :class="`status-${statusClass(currentVisit)}`"><i v-if="active" class="dot green"></i>{{ statusText(currentVisit) }}</span>
            </h2>
            <div class="patient-details">
              <span><Icon name="id" />身份证号 <b>{{ currentPatient?.id_no_masked || '—' }}</b></span>
              <span><Icon name="phone" /><b>{{ currentPatient?.phone_masked || '—' }}</b></span>
              <span><Icon name="calendar" />接诊日期 <b>{{ visitDate }}</b></span>
            </div>
          </div>
        </div>
        <div class="patient-actions">
          <button v-if="waiting" class="btn primary" :disabled="busy" @click="startVisit"><Icon name="play" />开始接诊</button>
          <template v-else-if="!closed">
            <button class="btn" :disabled="!exported || busy" title="当前版本确认并完成导出后可结束接诊" @click="modal='finish'"><Icon name="stop" />结束接诊</button>
            <button class="btn" :disabled="busy" @click="modal='cancel'">取消接诊</button>
          </template>
          <span v-else class="small-muted">该患者接诊已完成，不支持重复接诊</span>
        </div>
      </section>

      <nav class="steps" aria-label="接诊流程">
        <button v-for="(label, index) in ['患者 / 接诊', '录音上传', '转写结果', '病历生成', '医生确认', '病历导出']" :key="label"
                class="step" :class="{ done: index < stage || exported, current: index === stage }"
                :disabled="completed && index === 0"
                @click="navigate((['workbench','audio','transcript','record','confirm','export'] as MainView[])[index])">
          <span class="step-number"><Icon v-if="index < stage || exported" name="check" /><template v-else>{{ String(index + 1).padStart(2, '0') }}</template></span>
          <span class="step-label">{{ label }}<small>{{ ['CONSULTATION','UPLOAD','TRANSCRIPTION','MEDICAL RECORD','CONFIRMATION','EXPORT'][index] }}</small></span>
        </button>
      </nav>

      <div v-if="sourceDirty && !closed && !confirmed" class="info-banner"><Icon name="info" /><span>录音或转写已更新，请重新生成病历后再确认。</span></div>

      <div v-if="view === 'workbench'" class="consultation-start-view">
        <section class="card consultation-start-card" :class="{ 'is-active': active, 'is-closed': closed }">
          <div class="consultation-step-chip"><span class="step-chip-number">01</span><span>患者 / 接诊</span><small>CONSULTATION</small></div>
          <div class="consultation-start-content">
            <div class="consultation-start-icon"><Icon :name="waiting ? 'play' : closed ? 'check' : 'mic'" /></div>
            <span class="consultation-state-kicker">{{ waiting ? 'READY TO START' : closed ? 'VISIT CLOSED' : 'VISIT IN PROGRESS' }}</span>
            <h2>{{ waiting ? '准备开始本次接诊' : closed ? `本次接诊${statusText(currentVisit)}` : '本次接诊进行中' }}</h2>
            <p>{{ waiting ? '确认患者身份后开始接诊，下一步即可上传录音或使用网页录音。' : closed ? '本次接诊记录已保留。已完成接诊的患者不支持重复接诊。' : '接诊已开始，前往录音上传即可使用文件上传或网页录音。' }}</p>
            <button v-if="waiting" class="btn primary start-consultation-btn" :disabled="busy || !currentPatient" @click="startVisit"><Icon name="play" />开始接诊</button>
            <button v-else-if="active" class="btn primary start-consultation-btn" :disabled="busy" @click="navigate('audio')"><Icon name="mic" />进入录音上传</button>
            <span v-else class="small-muted">该患者接诊已完成，不支持重复接诊</span>
          </div>
          <div class="consultation-start-meta">
            <span><Icon name="user" />当前患者 <b>{{ currentPatient?.name || '未选择患者' }}</b></span>
            <span><Icon name="id" />患者编号 <b>{{ currentPatient?.patient_no || '—' }}</b></span>
            <span><Icon name="shield" />接诊记录 <b>{{ currentVisit ? `${statusText(currentVisit)} · ${patientVisitNo(selectedPatientId)}` : '尚未创建' }}</b></span>
          </div>
        </section>
      </div>

      <div v-else-if="view === 'workbench' && false" class="workspace">
        <div class="source-column">
          <section class="card">
            <div class="card-head"><h2><Icon name="mic" />问诊录音</h2><span class="small-muted">{{ String(recordings.length).padStart(2, '0') }} 段录音</span></div>
            <div class="card-body">
              <div v-if="waiting" class="empty-state"><div class="empty-icon"><Icon name="mic" /></div><h3>开始本次接诊</h3><p>开始接诊后，即可上传完整录音。</p><button class="btn primary" :disabled="busy" @click="startVisit"><Icon name="play" />开始接诊</button></div>
              <div v-else-if="closed" class="small-muted">本次接诊{{ statusText(currentVisit) }}，录音已归档。</div>
              <div v-else-if="confirmed" class="info-banner"><Icon name="lock" />病历已确认。如需追加录音，请先点击「修改病历」。</div>
              <template v-else>
                <div class="dropzone" role="button" tabindex="0" :class="{ dragging }" @click="triggerUpload" @keydown.enter.prevent="triggerUpload" @keydown.space.prevent="triggerUpload" @dragover.prevent="dragging=true" @dragleave="dragging=false" @drop.prevent="dragging=false; uploadFiles($event.dataTransfer?.files)">
                  <div class="upload-icon"><Icon name="upload" /></div>
                  <p><strong>点击上传</strong> 或拖拽录音文件到此处</p><small>支持 MP3、WAV、M4A · 时长与大小不限</small>
                  <input ref="fileInput" type="file" accept=".mp3,.wav,.m4a,.webm,audio/mpeg,audio/wav,audio/mp4,audio/webm" hidden @change="uploadFiles(($event.target as HTMLInputElement).files)" />
                </div>
                <div class="record-controls">
                  <button class="btn small" :disabled="locked" @click="startRecording">开始录音</button>
                  <button class="btn small" :disabled="locked || !recorder" @click="pauseRecording">暂停/继续</button>
                  <button class="btn small" :disabled="locked || recordingState === 'idle'" @click="stopRecording">结束录音</button>
                </div>
                <div class="upload-footer"><span>完整录音上传后，开始转写</span></div>
              </template>
              <div v-for="item in recordings" :key="item.id" class="audio-item">
                <div class="audio-info">
                  <div class="file-icon"><Icon name="file" /></div>
                  <div><div class="audio-name" :title="item.file_name || ''">{{ item.file_name }}</div><div class="audio-meta">{{ formatBytes(item.size_bytes) }} · {{ formatDuration(item.duration_ms) }}</div></div>
                  <span class="badge" :class="{ teal: item.status === 'DONE', red: item.status === 'FAILED' }">{{ ({ UPLOADED:'待转写', PROCESSING:'转写中', DONE:'已转写', FAILED:'转写失败' })[item.status] || item.status }}</span>
                </div>
                <p v-if="item.status === 'FAILED' && item.error_message" class="issue-hint">{{ item.error_message }}</p>
                <div v-if="item.status !== 'PROCESSING'" class="audio-player">
                  <button class="play-btn" :aria-label="isPlaying(item) ? '暂停' : '播放'" @click="playRecording(item)"><Icon :name="isPlaying(item) ? 'pause' : 'play'" /></button>
                  <div class="waveform" :class="{ playing: isPlaying(item) }"><i v-for="n in 64" :key="n" :style="{ height: `${4 + (Math.sin(n * 1.7) ** 2) * 18}px` }"></i></div>
                  <span class="audio-time">{{ formatDuration(item.duration_ms) }}</span>
                </div>
              </div>
              <div v-if="recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" style="margin-top:13px">
                <button class="btn soft" :disabled="locked || !recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" @click="startTranscription"><Icon name="sparkle" />{{ recordings.some(item => item.status === 'FAILED') ? '重试转写' : '开始转写' }}</button>
              </div>
            </div>
          </section>

          <section class="card">
            <div class="card-head"><h2><Icon name="text" />转写结果 <span v-if="allTranscribed" class="badge teal">已完成</span></h2>
              <button class="text-btn" :disabled="!transcriptDraft" aria-label="复制转写文本" @click="copyTranscript"><Icon name="copy" /></button>
            </div>
            <template v-if="transcriptDraft">
              <div class="transcript-tabs">
                <button class="tab" :class="{ active: transcriptTab === 'dialogue' }" @click="transcriptTab='dialogue'">医患对话</button>
                <button class="tab" :class="{ active: transcriptTab === 'edit' }" @click="transcriptTab='edit'">全文编辑</button>
                <button class="tab" :class="{ active: transcriptTab === 'facts' }" @click="transcriptTab='facts'">信息提取</button>
              </div>
              <div v-if="transcriptTab === 'dialogue'" class="transcript-body">
                <div class="transcript-note"><Icon name="info" />真实 ASR 转写结果 · 请核对后采用</div>
                <div v-for="(item, index) in segments" :key="index" class="dialogue" :class="{ patient: item.role === '患者' }">
                  <span class="speaker">{{ item.role === '医生' ? '医' : item.role === '患者' ? '患' : '其' }}</span>
                  <div><div class="dialogue-meta">{{ item.role }}<time>{{ item.time }}</time></div>
                    <p><template v-for="(piece, pieceIndex) in highlightText(item.text)" :key="pieceIndex"><mark v-if="piece.mark">{{ piece.text }}</mark><template v-else>{{ piece.text }}</template></template></p>
                  </div>
                </div>
              </div>
              <div v-else-if="transcriptTab === 'edit'" class="card-body">
                <label for="transcript-edit" class="transcript-note">核对转写文字；修改后需重新生成并确认病历</label>
                <textarea id="transcript-edit" v-model="transcriptDraft" class="transcript-editor" :disabled="locked"></textarea>
              </div>
              <div v-else class="transcript-body">
                <div class="transcript-note"><Icon name="info" />仅整理患者陈述，不输出自动诊断或医嘱。</div>
                <div v-for="[label, value] in facts" :key="label" class="source-fact"><b>{{ label }}</b>{{ value }}</div>
              </div>
              <div class="transcript-actions">
                <span class="small-muted">{{ transcriptDraft.length }} 字 · {{ recordings.filter(item => item.status === 'DONE').length }} 段录音</span>
                <button v-if="transcriptTab === 'edit'" class="btn small soft" :disabled="locked" @click="saveTranscript"><Icon name="save" />保存转写</button>
                <button v-else class="btn small soft" :disabled="locked || !allTranscribed" @click="generateRecord"><Icon name="sparkle" />生成病历</button>
              </div>
            </template>
            <div v-else class="empty-state"><div class="empty-icon"><Icon name="text" /></div><h3>等待录音转写</h3><p>上传完整录音后，医患对话会显示在这里。</p></div>
          </section>
        </div>

        <section class="card record-card">
          <div class="card-head">
            <h2><Icon name="file" />门诊病历 <span v-if="record?.record_id" class="badge" :class="confirmed ? 'teal' : 'amber'">{{ confirmed ? '医生已确认' : '待医生确认' }}</span></h2>
            <div class="record-header-actions">
              <span v-if="record?.record_id" class="record-version">v{{ record.version_no }}.0</span>
              <button v-if="record?.record_id && confirmed && !closed" class="btn small" @click="editRecord"><Icon name="edit" />修改病历</button>
              <button v-else-if="record?.record_id && !closed" class="btn small" :disabled="busy || !allTranscribed" @click="modal='regenerate'"><Icon name="refresh" />重新生成</button>
            </div>
          </div>
          <div class="record-subbar"><span class="template-label"><Icon name="list" />标准门诊病历模板</span><span>{{ doctor.department_name }} · v0.1</span></div>
          <div v-if="!record?.record_id" class="empty-state">
            <div class="empty-icon"><Icon name="file" /></div><h3>让对话成为清晰的病历</h3>
            <p>{{ waiting ? '开始接诊并上传录音后，即可生成结构化病历草稿。' : '完成全部录音转写后，按标准模板生成一份病历草稿。' }}</p>
            <button class="btn primary" :disabled="!allTranscribed || busy || closed" @click="generateRecord"><Icon name="sparkle" />生成病历草稿</button>
          </div>
          <template v-else>
            <form class="record-form" autocomplete="off" @submit.prevent="saveDraft">
              <section class="form-section">
                <div class="section-label"><h3>基本信息</h3><span><span class="required">*</span> 为必填项</span></div>
                <div class="basic-grid">
                  <div v-for="field in recordFields.slice(0, 4)" :key="field.key" class="field">
                    <label :for="`field-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
                    <input :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :type="field.key === 'age' ? 'number' : field.key === 'phone' ? 'tel' : 'text'" :readonly="locked" @input="updateField(field.key, $event)" />
                  </div>
                </div>
              </section>
              <section class="form-section">
                <div class="section-label"><h3>就诊内容</h3><span>根据医患对话整理</span></div>
                <div class="fields-stack">
                  <div v-for="field in recordFields.slice(4, 7)" :key="field.key" class="field">
                    <label :for="`field-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
                    <textarea :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :rows="field.key === 'present' ? 3 : 2" :placeholder="field.required ? '请填写患者主诉' : '未提及，可由医生补充'" :disabled="locked" @input="updateField(field.key, $event)"></textarea>
                  </div>
                </div>
              </section>
              <section class="form-section">
                <div class="section-label"><h3>诊疗记录</h3><span>由医生核对及补充</span></div>
                <div class="diagnosis-grid">
                  <div class="wide" v-for="field in recordFields.slice(7, 10)" :key="field.key">
                    <div class="field"><label :for="`field-${field.key}`">{{ field.label }}</label><textarea :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" rows="1" :placeholder="'未提及，可由医生补充'" :disabled="locked" @input="updateField(field.key, $event)"></textarea></div>
                  </div>
                  <div v-for="field in recordFields.slice(10)" :key="field.key">
                    <div class="field">
                      <label :for="`field-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span><span class="auto-tag">自动填入</span></label>
                      <input :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :type="field.key === 'date' ? 'date' : 'text'" readonly :disabled="locked" @input="updateField(field.key, $event)" />
                    </div>
                  </div>
                </div>
              </section>
              <p class="auto-note"><Icon name="info" />非必填项未提及则留空。内容仅为病历草稿，最终以医生确认版本为准。</p>
            </form>
            <div class="record-footer">
              <span><Icon name="shield" />{{ confirmed ? `${record?.confirmed_by_name || doctor.display_name}医生已确认 · v${record?.version_no}.0` : '记录已保存到后端' }}</span>
              <span>{{ confirmed ? formatDateTime(record?.confirmed_at) : '草稿可自动保存' }}</span>
            </div>
          </template>
        </section>
      </div>

      <div v-else-if="view === 'audio'" class="full-view">
        <section class="card">
          <div class="card-head"><h2><Icon name="mic" />问诊录音</h2><span class="small-muted">{{ String(recordings.length).padStart(2, '0') }} 段录音</span></div>
          <div class="card-body">
            <div v-if="waiting" class="step-guard">
              <div class="empty-icon"><Icon name="mic" /></div>
              <span class="step-guard-kicker">步骤 01 尚未开始</span>
              <h3>先开始本次接诊</h3>
              <p>开始接诊后，才能上传录音或使用网页录音。</p>
              <button class="btn primary" :disabled="busy || !currentPatient" @click="startVisit"><Icon name="play" />开始接诊</button>
            </div>
            <template v-else-if="active">
              <div class="audio-source-grid">
                <div class="upload-method">
                  <div class="method-heading"><span class="method-icon"><Icon name="upload" /></span><div><h3>上传录音文件</h3><p>支持 MP3、WAV、M4A、WEBM</p></div></div>
                  <div class="dropzone compact-dropzone" role="button" tabindex="0" :class="{ dragging }" @click="triggerUpload" @keydown.enter.prevent="triggerUpload" @keydown.space.prevent="triggerUpload" @dragover.prevent="dragging=true" @dragleave="dragging=false" @drop.prevent="dragging=false; uploadFiles($event.dataTransfer?.files)">
                    <div class="upload-icon"><Icon name="upload" /></div><p><strong>点击上传</strong> 或拖拽到此处</p><small>单个文件最大 200MB</small>
                    <input ref="fileInput" type="file" accept=".mp3,.wav,.m4a,.webm,audio/mpeg,audio/wav,audio/mp4,audio/webm" hidden @change="uploadFiles(($event.target as HTMLInputElement).files)" />
                  </div>
                </div>
                <div class="recorder-card" :class="`is-${recordingState}`">
                  <div class="recorder-card-head"><div class="method-heading"><span class="method-icon recorder-method-icon"><Icon name="mic" /></span><div><h3>网页录音</h3><p>直接使用当前设备麦克风</p></div></div><span class="recorder-state-badge" :class="`state-${recordingState}`"><i></i>{{ actionBusy === 'upload' ? '正在保存' : recordingStateLabel }}</span></div>
                  <div class="recorder-live" :class="`state-${recordingState}`">
                    <div class="recorder-timer">{{ recordingTimeLabel }}</div>
                    <div class="live-waveform" :aria-label="recordingStateLabel" role="img"><i v-for="n in 36" :key="n" :style="{ '--wave-delay': `${(n % 9) * 0.08}s`, '--wave-height': `${12 + ((n * 17) % 28)}px` }"></i></div>
                    <p>{{ recordingState === 'recording' ? '正在采集麦克风声音…' : recordingState === 'paused' ? '录音已暂停，点击继续录音' : recordingElapsedMs ? '录音已结束，可重新录制' : '点击开始录音，浏览器会请求麦克风权限' }}</p>
                  </div>
                  <div class="record-controls recorder-controls">
                    <button class="btn recorder-main-btn" :class="{ primary: recordingState === 'idle', soft: recordingState === 'paused' }" :disabled="locked || actionBusy === 'upload'" @click="recordingState === 'idle' ? startRecording() : resumeRecording()"><Icon :name="recordingState === 'recording' ? 'pause' : recordingState === 'paused' ? 'play' : 'mic'" />{{ recordingState === 'recording' ? '暂停录音' : recordingState === 'paused' ? '继续录音' : '开始录音' }}</button>
                    <button class="btn recorder-stop-btn" :disabled="locked || recordingState === 'idle' || !recorder" @click="stopRecording"><Icon name="stop" />结束录音</button>
                  </div>
                </div>
              </div>
              <div class="upload-footer"><span>完整录音上传后，点击“开始转写”</span></div>
              <div v-for="item in recordings" :key="item.id" class="audio-item">
                <div class="audio-info">
                  <div class="file-icon"><Icon name="file" /></div>
                  <div><div class="audio-name">{{ item.file_name }}</div><div class="audio-meta">{{ formatBytes(item.size_bytes) }} · {{ formatDuration(item.duration_ms) }}</div></div>
                  <span class="badge" :class="{ teal: item.status === 'DONE', red: item.status === 'FAILED' }">{{ ({ UPLOADED:'待转写', PROCESSING:'转写中', DONE:'已转写', FAILED:'转写失败' })[item.status] || item.status }}</span>
                </div>
                <p v-if="item.status === 'FAILED' && item.error_message" class="issue-hint">{{ item.error_message }}</p>
                <div v-if="item.status !== 'PROCESSING'" class="audio-player">
                  <button class="play-btn" :aria-label="isPlaying(item) ? '暂停' : '播放'" @click="playRecording(item)"><Icon :name="isPlaying(item) ? 'pause' : 'play'" /></button>
                  <div class="waveform" :class="{ playing: isPlaying(item) }"><i v-for="n in 64" :key="n" :style="{ height: `${4 + (Math.sin(n * 1.7) ** 2) * 18}px` }"></i></div>
                  <span class="audio-time">{{ formatDuration(item.duration_ms) }}</span>
                </div>
              </div>
              <div v-if="recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" class="transcribe-action">
                <button class="btn soft" :disabled="locked || !recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" @click="startTranscription"><Icon name="sparkle" />{{ recordings.some(item => item.status === 'FAILED') ? '重试转写' : '开始转写' }}</button>
              </div>
            </template>
            <template v-else-if="completed">
              <div class="info-banner"><Icon name="lock" />本次接诊已完成，以下录音仅供查看和播放，不能再上传或转写。</div>
              <div v-if="!recordings.length" class="empty-state"><div class="empty-icon"><Icon name="mic" /></div><h3>没有保留的录音</h3><p>该已完成接诊未找到录音记录。</p></div>
              <div v-for="item in recordings" :key="item.id" class="audio-item">
                <div class="audio-info">
                  <div class="file-icon"><Icon name="file" /></div>
                  <div><div class="audio-name">{{ item.file_name }}</div><div class="audio-meta">{{ formatBytes(item.size_bytes) }} · {{ formatDuration(item.duration_ms) }}</div></div>
                  <span class="badge" :class="{ teal: item.status === 'DONE', red: item.status === 'FAILED' }">{{ ({ UPLOADED:'待转写', PROCESSING:'转写中', DONE:'已转写', FAILED:'转写失败' })[item.status] || item.status }}</span>
                </div>
                <p v-if="item.status === 'FAILED' && item.error_message" class="issue-hint">{{ item.error_message }}</p>
                <div v-if="item.status !== 'PROCESSING'" class="audio-player">
                  <button class="play-btn" :aria-label="isPlaying(item) ? '暂停' : '播放'" @click="playRecording(item)"><Icon :name="isPlaying(item) ? 'pause' : 'play'" /></button>
                  <div class="waveform" :class="{ playing: isPlaying(item) }"><i v-for="n in 64" :key="n" :style="{ height: `${4 + (Math.sin(n * 1.7) ** 2) * 18}px` }"></i></div>
                  <span class="audio-time">{{ formatDuration(item.duration_ms) }}</span>
                </div>
              </div>
            </template>
            <div v-else class="step-guard archived">
              <div class="empty-icon"><Icon name="lock" /></div>
              <span class="step-guard-kicker">接诊记录已归档</span>
              <h3>本次接诊{{ statusText(currentVisit) }}</h3>
              <p>已完成接诊不再支持追加录音，也不支持重复接诊。</p>
              <span class="small-muted">该患者接诊已完成，不支持重复接诊</span>
            </div>
          </div>
        </section>
      </div>

      <div v-else-if="view === 'transcript'" class="full-view">
        <section class="card">
          <div class="card-head"><h2><Icon name="text" />转写结果 <span v-if="allTranscribed" class="badge teal">已完成</span></h2><button class="text-btn" :disabled="!transcriptDraft" @click="copyTranscript"><Icon name="copy" /></button></div>
          <div class="transcript-tabs">
            <button class="tab" :class="{ active: transcriptTab === 'dialogue' }" @click="transcriptTab='dialogue'">医患对话</button>
            <button class="tab" :class="{ active: transcriptTab === 'edit' }" @click="transcriptTab='edit'">全文编辑</button>
            <button class="tab" :class="{ active: transcriptTab === 'facts' }" @click="transcriptTab='facts'">信息提取</button>
          </div>
          <div v-if="transcriptTab === 'dialogue'" class="transcript-body">
            <div class="transcript-note"><Icon name="info" />真实 ASR 转写结果 · 请核对后采用</div>
            <div v-for="(item, index) in segments" :key="index" class="dialogue" :class="{ patient: item.role === '患者' }">
              <span class="speaker">{{ item.role === '医生' ? '医' : item.role === '患者' ? '患' : '其' }}</span>
              <div><div class="dialogue-meta">{{ item.role }}<time>{{ item.time }}</time></div>
                <p><template v-for="(piece, pieceIndex) in highlightText(item.text)" :key="pieceIndex"><mark v-if="piece.mark">{{ piece.text }}</mark><template v-else>{{ piece.text }}</template></template></p>
              </div>
            </div>
          </div>
          <div v-else-if="transcriptTab === 'edit'" class="card-body">
            <label for="transcript-edit" class="transcript-note">核对转写文字；修改后需重新生成并确认病历</label>
            <textarea id="transcript-edit" v-model="transcriptDraft" class="transcript-editor" :disabled="locked"></textarea>
          </div>
          <div v-else class="transcript-body"><div class="transcript-note"><Icon name="info" />仅整理患者陈述，不输出自动诊断或医嘱。</div><div v-for="[label, value] in facts" :key="label" class="source-fact"><b>{{ label }}</b>{{ value }}</div></div>
          <div class="transcript-actions"><span class="small-muted">{{ transcriptDraft.length }} 字 · {{ recordings.filter(item => item.status === 'DONE').length }} 段录音</span>
            <button v-if="transcriptTab === 'edit'" class="btn small soft" :disabled="locked" @click="saveTranscript"><Icon name="save" />保存转写</button>
            <button v-else class="btn small soft" :disabled="locked || !allTranscribed" @click="generateRecord"><Icon name="sparkle" />生成病历</button>
          </div>
        </section>
      </div>

      <div v-else-if="view === 'record'" class="full-view">
        <section class="card record-card">
          <div class="card-head"><h2><Icon name="file" />门诊病历 <span v-if="record?.record_id" class="badge" :class="confirmed ? 'teal' : 'amber'">{{ confirmed ? '医生已确认' : '待医生确认' }}</span></h2>
            <div class="record-header-actions"><span v-if="record?.record_id" class="record-version">v{{ record.version_no }}.0</span>
              <button v-if="record?.record_id && confirmed && !closed" class="btn small" @click="editRecord"><Icon name="edit" />修改病历</button>
              <button v-else-if="record?.record_id && !closed" class="btn small" :disabled="busy || !allTranscribed" @click="modal='regenerate'"><Icon name="refresh" />重新生成</button>
            </div>
          </div>
          <div class="record-subbar"><span class="template-label"><Icon name="list" />标准门诊病历模板</span><span>{{ doctor.department_name }} · v0.1</span></div>
          <div v-if="!record?.record_id" class="empty-state"><div class="empty-icon"><Icon name="file" /></div><h3>让对话成为清晰的病历</h3><p>完成全部录音转写后，按标准模板生成一份病历草稿。</p><button class="btn primary" :disabled="!allTranscribed || busy || closed" @click="generateRecord"><Icon name="sparkle" />生成病历草稿</button></div>
          <form v-else class="record-form" autocomplete="off" @submit.prevent="saveDraft">
            <section class="form-section"><div class="section-label"><h3>基本信息</h3><span><span class="required">*</span> 为必填项</span></div>
              <div class="basic-grid">
                <div v-for="field in recordFields.slice(0, 4)" :key="field.key" class="field">
                  <label :for="`field-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
                  <input :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :type="field.key === 'age' ? 'number' : 'text'" :readonly="locked" @input="updateField(field.key, $event)" />
                </div>
              </div>
            </section>
            <section class="form-section"><div class="section-label"><h3>就诊内容</h3><span>根据医患对话整理</span></div>
              <div class="fields-stack">
                <div v-for="field in recordFields.slice(4, 7)" :key="field.key" class="field">
                  <label :for="`field-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
                  <textarea :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :rows="field.key === 'present' ? 3 : 2" :disabled="locked" @input="updateField(field.key, $event)"></textarea>
                </div>
              </div>
            </section>
            <section class="form-section"><div class="section-label"><h3>诊疗记录</h3><span>由医生核对及补充</span></div>
              <div class="diagnosis-grid">
                <div class="wide" v-for="field in recordFields.slice(7, 10)" :key="field.key"><div class="field"><label :for="`field-${field.key}`">{{ field.label }}</label><textarea :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" rows="1" :disabled="locked" @input="updateField(field.key, $event)"></textarea></div></div>
                <div v-for="field in recordFields.slice(10)" :key="field.key"><div class="field"><label :for="`field-${field.key}`">{{ field.label }}<span class="auto-tag">自动填入</span></label><input :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :type="field.key === 'date' ? 'date' : 'text'" readonly :disabled="locked" @input="updateField(field.key, $event)" /></div></div>
              </div>
            </section>
            <p class="auto-note"><Icon name="info" />非必填项未提及则留空。内容仅为病历草稿，最终以医生确认版本为准。</p>
          </form>
          <div v-if="record?.record_id" class="record-footer">
            <span><Icon name="shield" />{{ confirmed ? `${record?.confirmed_by_name || doctor.display_name}医生已确认 · v${record?.version_no}.0` : '记录已保存到后端' }}</span>
            <span>{{ confirmed ? formatDateTime(record?.confirmed_at) : '草稿可自动保存' }}</span>
          </div>
        </section>
      </div>

      <div v-else-if="view === 'confirm'" class="full-view">
        <section class="card review-card">
          <div class="review-icon"><Icon :name="confirmed ? 'shield' : 'check'" /></div>
          <h2>{{ confirmed ? '当前病历已确认' : '请完成本次病历核对' }}</h2>
          <p>{{ confirmed ? '当前确认版本可以导出。再次修改后，需要重新确认。' : '请核对患者陈述、病历内容与必填信息。即使未修改，也需医生主动确认。' }}</p>
          <div class="review-meta"><span>接诊编号 {{ currentVisit?.visit_no || '—' }}</span><span>接诊医生 {{ record?.content?.doctor || doctor.display_name }}</span><span>病历版本 {{ record?.record_id ? `v${record.version_no}.0` : '尚未生成' }}</span></div>
          <div class="checklist">
            <div :class="{ missing: !record?.record_id }"><Icon :name="record?.record_id ? 'check' : 'info'" />模板固定字段已完整保留</div>
            <div :class="{ missing: missingFields.length }"><Icon :name="!missingFields.length ? 'check' : 'info'" />患者及接诊必填信息已填写</div>
            <div :class="{ missing: !allTranscribed }"><Icon :name="allTranscribed ? 'check' : 'info'" />全部录音已转写并采用</div>
            <div :class="{ missing: !confirmed }"><Icon :name="confirmed ? 'check' : 'info'" />当前病历版本已由医生确认</div>
          </div>
          <p v-if="missingFields.length" class="issue-hint">待完善：{{ missingFields.join('、') }}</p>
          <button v-if="confirmed" class="btn primary" @click="navigate('export')"><Icon name="download" />前往导出</button>
          <button v-else class="btn primary" :disabled="!record?.record_id || busy || closed" @click="requestConfirm"><Icon name="shield" />核对并确认病历</button>
        </section>

        <section class="card record-card">
          <div class="card-head"><h2><Icon name="file" />门诊病历 <span v-if="record?.record_id" class="badge" :class="confirmed ? 'teal' : 'amber'">{{ confirmed ? '医生已确认' : '待医生确认' }}</span></h2>
            <div class="record-header-actions"><span v-if="record?.record_id" class="record-version">v{{ record.version_no }}.0</span>
              <button v-if="record?.record_id && confirmed && !closed" class="btn small" @click="editRecord"><Icon name="edit" />修改病历</button>
              <button v-else-if="record?.record_id && !closed" class="btn small" :disabled="busy || !allTranscribed" @click="modal='regenerate'"><Icon name="refresh" />重新生成</button>
            </div>
          </div>
          <div class="record-subbar"><span class="template-label"><Icon name="list" />标准门诊病历模板</span><span>{{ doctor.department_name }} · v0.1</span></div>
          <div v-if="!record?.record_id" class="empty-state"><div class="empty-icon"><Icon name="file" /></div><h3>让对话成为清晰的病历</h3><p>完成全部录音转写后，按标准模板生成一份病历草稿。</p><button class="btn primary" :disabled="!allTranscribed || busy || closed" @click="generateRecord"><Icon name="sparkle" />生成病历草稿</button></div>
          <form v-else class="record-form" autocomplete="off" @submit.prevent="saveDraft">
            <section class="form-section"><div class="section-label"><h3>基本信息</h3><span><span class="required">*</span> 为必填项</span></div>
              <div class="basic-grid">
                <div v-for="field in recordFields.slice(0, 4)" :key="field.key" class="field">
                  <label :for="`field-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
                  <input :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :type="field.key === 'age' ? 'number' : 'text'" :readonly="locked" @input="updateField(field.key, $event)" />
                </div>
              </div>
            </section>
            <section class="form-section"><div class="section-label"><h3>就诊内容</h3><span>根据医患对话整理</span></div>
              <div class="fields-stack">
                <div v-for="field in recordFields.slice(4, 7)" :key="field.key" class="field">
                  <label :for="`field-${field.key}`">{{ field.label }}<span v-if="field.required" class="required">*</span></label>
                  <textarea :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :rows="field.key === 'present' ? 3 : 2" :disabled="locked" @input="updateField(field.key, $event)"></textarea>
                </div>
              </div>
            </section>
            <section class="form-section"><div class="section-label"><h3>诊疗记录</h3><span>由医生核对及补充</span></div>
              <div class="diagnosis-grid">
                <div class="wide" v-for="field in recordFields.slice(7, 10)" :key="field.key"><div class="field"><label :for="`field-${field.key}`">{{ field.label }}</label><textarea :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" rows="1" :disabled="locked" @input="updateField(field.key, $event)"></textarea></div></div>
                <div v-for="field in recordFields.slice(10)" :key="field.key"><div class="field"><label :for="`field-${field.key}`">{{ field.label }}<span class="auto-tag">自动填入</span></label><input :id="`field-${field.key}`" :value="recordForm?.[field.key] ?? ''" :type="field.key === 'date' ? 'date' : 'text'" readonly :disabled="locked" @input="updateField(field.key, $event)" /></div></div>
              </div>
            </section>
            <p class="auto-note"><Icon name="info" />非必填项未提及则留空。内容仅为病历草稿，最终以医生确认版本为准。</p>
          </form>
          <div v-if="record?.record_id" class="record-footer">
            <span><Icon name="shield" />{{ confirmed ? `${record?.confirmed_by_name || doctor.display_name}医生已确认 · v${record?.version_no}.0` : '记录已保存到后端' }}</span>
            <span>{{ confirmed ? formatDateTime(record?.confirmed_at) : '草稿可自动保存' }}</span>
          </div>
        </section>

        <section v-if="confirmations.length" class="card">
          <div class="card-head"><h2><Icon name="clock" />确认记录</h2><span class="small-muted">当前接诊 {{ currentVisit?.visit_no || '—' }}</span></div>
          <div class="table-wrap"><table class="log-table"><thead><tr><th>病历版本</th><th>确认医生</th><th>操作时间</th></tr></thead><tbody>
            <tr v-for="item in confirmations.slice().reverse()" :key="item.id"><td>v{{ item.version_no }}.0</td><td>{{ item.doctor_name }}</td><td>{{ formatDateTime(item.confirmed_at) }}</td></tr>
          </tbody></table></div>
        </section>
      </div>

      <div v-else-if="view === 'export'" class="full-view">
        <div class="info-banner"><Icon :name="confirmed ? 'shield' : 'lock'" />
          <span>{{ confirmed ? `当前可导出版本：v${record?.version_no}.0 · 确认医生：${record?.confirmed_by_name || doctor.display_name} · 确认时间：${formatDateTime(record?.confirmed_at)}` : '请先完成医生确认，确认后的版本才可以导出。' }}</span>
        </div>
        <section class="card">
          <div class="card-head"><h2><Icon name="download" />选择导出格式</h2><span class="small-muted">{{ record?.record_id ? `病历编号 MR-${currentVisit?.visit_no}` : '等待生成病历' }}</span></div>
          <div class="export-options">
            <div class="export-option"><Icon name="file" /><h3>Word 文档</h3><p>保留标准模板字段与确认信息。<br>下载 .docx 文件，便于存档及后续查阅。</p><button class="btn primary" :disabled="!confirmed || busy" @click="exportWord"><Icon name="download" />导出 Word</button></div>
            <div class="export-option"><Icon name="file" /><h3>PDF 文档</h3><p>由后端生成标准 A4 PDF 文件。<br>生成完成后自动下载，便于打印和存档。</p><button class="btn" :disabled="!confirmed || busy" @click="exportPdf"><Icon name="download" />导出 PDF</button></div>
          </div>
          <div class="readonly-note">导出任务完成后才允许下载；只有当前确认版本可以导出。</div>
        </section>

        <section class="card">
          <div class="card-head"><h2><Icon name="clock" />导出记录</h2><span class="small-muted">当前接诊 {{ currentVisit?.visit_no || '—' }}</span></div>
          <div class="table-wrap"><table class="log-table"><thead><tr><th>病历版本</th><th>格式 / 状态</th><th>操作时间</th></tr></thead><tbody>
            <tr v-if="!exports.length"><td colspan="3">暂无导出记录</td></tr>
            <tr v-for="item in exports.slice().reverse()" :key="item.id"><td>v{{ item.version_no }}.0</td><td>{{ item.format }} · {{ exportStatusLabel(item.status) }}</td><td>{{ formatDateTime(item.created_at) }}</td></tr>
          </tbody></table></div>
        </section>
      </div>

      <div v-else-if="view === 'audit'" class="full-view">
        <section class="card"><div class="card-head"><h2><Icon name="shield" />确认记录</h2><span class="small-muted">当前接诊 {{ currentVisit?.visit_no || '—' }}</span></div>
          <div class="table-wrap"><table class="log-table"><thead><tr><th>病历版本</th><th>确认医生</th><th>操作时间</th></tr></thead><tbody>
            <tr v-if="!confirmations.length"><td colspan="3">暂无确认记录</td></tr>
            <tr v-for="item in confirmations" :key="item.id"><td>v{{ item.version_no }}</td><td>{{ item.doctor_name }}</td><td>{{ formatDateTime(item.confirmed_at) }}</td></tr>
          </tbody></table></div>
        </section>
        <section class="card"><div class="card-head"><h2><Icon name="download" />导出记录</h2><span class="small-muted">当前接诊 {{ currentVisit?.visit_no || '—' }}</span></div>
          <div class="table-wrap"><table class="log-table"><thead><tr><th>病历版本</th><th>格式 / 状态</th><th>操作时间</th></tr></thead><tbody>
            <tr v-if="!exports.length"><td colspan="3">暂无导出记录</td></tr>
            <tr v-for="item in exports" :key="item.id"><td>v{{ item.version_no }}</td><td>{{ item.format }} · {{ exportStatusLabel(item.status) }}</td><td>{{ formatDateTime(item.created_at) }}</td></tr>
          </tbody></table></div>
        </section>
      </div>

      <div v-else-if="view === 'config'" class="full-view">
        <section class="card"><div class="card-head"><h2><Icon name="gear" />接入状态</h2><span class="small-muted">生产环境配置由后端注入</span></div>
          <div class="card-body">
            <div class="info-banner"><Icon name="check" />后端 API、录音对象存储与 DashScope ASR 已接入；病历生成服务仍待替换为真实模型。</div>
            <div class="info-banner"><Icon name="info" />病历导出由后端异步生成并保存，生成完成后通过受保护的下载接口获取文件。</div>
          </div>
        </section>
      </div>
    </main>

    <footer class="bottom-bar">
      <div class="bottom-status">
        <span class="status-emblem"><Icon :name="confirmed ? 'shield' : 'save'" /></span>
        <div>
          <div>{{ closed ? `本次接诊${statusText(currentVisit)}` : confirmed ? `病历 v${record?.version_no}.0 已由${record?.confirmed_by_name || doctor.display_name}医生确认` : record?.record_id ? '病历草稿已就绪，请医生核对' : waiting ? '患者待接诊' : '正在准备本次接诊的病历' }}</div>
          <small>{{ confirmed ? `确认时间：${formatDateTime(record?.confirmed_at)}` : '系统仅整理问诊内容，不生成自动诊断或自动医嘱。' }}</small>
        </div>
      </div>
      <div class="bottom-actions">
        <button v-if="record?.record_id && !closed && !confirmed" class="btn" :disabled="busy" @click="saveDraft"><Icon name="save" />保存草稿</button>
        <button v-if="!closed" class="btn" :class="{ primary: confirmed }" :disabled="busy || (!confirmed && !record?.record_id)" @click="confirmed ? editRecord() : requestConfirm()"><Icon name="check" />{{ confirmed ? '修改病历' : '医生确认' }}</button>
        <span class="separator"></span>
        <button class="btn" :class="{ primary: confirmed }" :disabled="!confirmed || busy" @click="exportFromBottom"><Icon name="download" />导出病历</button>
      </div>
    </footer>

    <div class="toast" :class="{ show: toastVisible }" role="status" aria-live="polite">{{ toastText }}</div>

    <div v-if="modal" class="modal-backdrop" role="presentation" @click.self="modal=null" @keydown.esc="modal=null">
      <div ref="modalDialog" class="modal-dialog" role="dialog" aria-modal="true" :aria-labelledby="`modal-title-${modal}`" tabindex="-1" @keydown.esc.stop="modal=null">
        <div class="modal-head">
          <h2 :id="`modal-title-${modal}`">{{ ({ 'new-visit':'新增接诊','cancel':'取消本次接诊','finish':'结束本次接诊','regenerate':'重新生成当前病历','confirm':'确认本次病历','help':'使用帮助','activity':'当前接诊动态' })[modal] }}</h2>
          <button class="icon-btn" aria-label="关闭对话框" @click="modal=null"><Icon name="x" /></button>
        </div>
        <div class="modal-body">
          <template v-if="modal === 'new-visit'">
            <div class="modal-switch" role="tablist" aria-label="患者来源">
              <button type="button" class="modal-switch-btn" :class="{ active: newPatientMode === 'existing' }" @click="newPatientMode='existing'; modalError=''">选择已有患者</button>
              <button type="button" class="modal-switch-btn" :class="{ active: newPatientMode === 'manual' }" @click="newPatientMode='manual'; modalError=''">手动录入患者</button>
            </div>
            <template v-if="newPatientMode === 'existing'">
              <div class="modal-field"><label for="new-patient">选择患者</label>
                <select id="new-patient" v-model="newPatientId" :disabled="!patients.length">
                  <option v-if="!patients.length" value="">暂无已有患者</option>
                  <option v-for="patient in patients" :key="patient.id" :value="patient.id" :disabled="!canCreateVisit(patient.id)">{{ patient.name }} · {{ patient.gender }} · {{ patient.age ?? '—' }} 岁 · {{ patient.patient_no }}{{ canCreateVisit(patient.id) ? '' : ' · 已有接诊' }}</option>
                </select>
              </div>
               <div class="modal-note">系统将分配新的唯一接诊编号；已完成接诊的患者不支持重复接诊，取消的接诊可重新开始。</div>
            </template>
            <template v-else>
              <div class="manual-patient-grid">
                <div class="modal-field"><label for="manual-patient-name">患者姓名 <span class="required">*</span></label><input id="manual-patient-name" v-model="newPatientForm.name" maxlength="128" placeholder="请输入真实姓名" /></div>
                <div class="modal-field"><label for="manual-patient-gender">性别 <span class="required">*</span></label><select id="manual-patient-gender" v-model="newPatientForm.gender"><option value="">请选择</option><option value="男">男</option><option value="女">女</option><option value="其他">其他</option><option value="未知">未知</option></select></div>
                <div class="modal-field"><label for="manual-patient-age">年龄</label><input id="manual-patient-age" v-model.number="newPatientForm.age" type="number" min="0" max="150" step="1" placeholder="选填" /></div>
                <div class="modal-field"><label for="manual-patient-phone">联系方式</label><input id="manual-patient-phone" v-model="newPatientForm.phone" type="tel" maxlength="64" placeholder="选填，保存后脱敏显示" /></div>
                <div class="modal-field manual-patient-wide"><label for="manual-patient-id">证件号</label><input id="manual-patient-id" v-model="newPatientForm.idNo" maxlength="64" placeholder="选填，保存后脱敏显示" /></div>
              </div>
              <div class="modal-note">手动录入的患者会保存到本地患者库，并自动生成患者编号；联系方式和证件号在列表中以脱敏形式展示。</div>
              <div v-if="modalError" class="modal-error">{{ modalError }}</div>
            </template>
          </template>
          <template v-else-if="modal === 'cancel'"><p>确定取消 <b>{{ currentPatient?.name }} · 接诊 {{ currentVisit?.visit_no }}</b> 的本次接诊？</p><p>取消后将清空本次接诊的录音、转写、病历和导出文件；该患者之后可以重新开始接诊。</p></template>
          <template v-else-if="modal === 'finish'"><p>{{ currentPatient?.name }} 的病历 <b>v{{ record?.version_no }}.0</b> 已确认并导出。</p><p>结束后本次接诊将归档。</p></template>
          <template v-else-if="modal === 'regenerate'"><p>将使用当前已采用的转写文本，覆盖 <b>接诊 {{ currentVisit?.visit_no }}</b> 的当前草稿内容，并创建新版本。</p><p>医生手动编辑的内容也会被替换，请确认已保存所需内容。</p></template>
          <template v-else-if="modal === 'confirm'">
            <p>患者 <b>{{ recordForm?.name }}</b> · 接诊 <b>{{ currentVisit?.visit_no }}</b> · 病历 <b>v{{ record?.version_no }}.0</b></p>
            <p>确认后将记录确认医生、时间及版本，并开放病历导出。</p>
            <label class="confirm-check"><input v-model="confirmChecked" type="checkbox" /><span>我已核对病历内容，确认当前记录准确反映本次接诊情况。</span></label>
            <div class="modal-error">{{ modalError }}</div>
          </template>
          <template v-else-if="modal === 'help'"><p><b>完整流程</b><br>开始接诊 → 上传录音 → 开始转写 → 生成病历 → 核对编辑 → 医生确认 → 后端生成并下载 Word / PDF → 结束接诊。</p><p>非必填字段未提及则留空；诊疗记录仅供医生补充。</p></template>
          <template v-else><p v-if="!activity.length">接诊开始后将在这里记录业务操作。</p><p v-for="item in activity.slice(0, 12)" :key="item.time"><span class="small-muted">{{ item.time }}</span><br>{{ item.text }}</p></template>
        </div>
        <div class="modal-actions">
          <template v-if="modal === 'new-visit'"><button class="btn" @click="modal=null">取消</button><button class="btn primary" :disabled="busy || (newPatientMode === 'existing' && !newPatientId)" @click="createNewVisit"><Icon name="plus" />创建接诊</button></template>
          <template v-else-if="modal === 'cancel'"><button class="btn" @click="modal=null">返回</button><button class="btn danger" :disabled="busy" @click="doCancel">确认取消接诊</button></template>
          <template v-else-if="modal === 'finish'"><button class="btn" @click="modal=null">返回</button><button class="btn primary" :disabled="busy" @click="doFinish"><Icon name="check" />完成本次接诊</button></template>
          <template v-else-if="modal === 'regenerate'"><button class="btn" @click="modal=null">返回</button><button class="btn primary" :disabled="busy" @click="modal=null;generateRecord()"><Icon name="refresh" />重新生成</button></template>
          <template v-else-if="modal === 'confirm'"><button class="btn" @click="modal=null">返回核对</button><button class="btn primary" :disabled="busy" @click="doConfirm"><Icon name="shield" />确认病历</button></template>
          <template v-else><button class="btn primary" @click="modal=null"><Icon name="check" />开始使用</button></template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import '../routeSelection.css'
import Icon from './Icon.vue'
import { formatDateTime } from '../dateTime'
import { roleLabel } from '../asrRoles'
import { showMessage, type MessageType } from '../message'
import type { AsrProvider, AuditAction, AuditLog, AuditOperator, ClinicalExtraction, Confirmation, Doctor, LlmProvider, MedicalRecord, MedicalRecordContent, Patient, RecordExport, Recording, Transcript, Utterance, Visit } from '../types'

const props = defineProps<{ doctor: Doctor }>()
const emit = defineEmits<{ (event: 'logout'): void }>()
// Keep this in sync with MedicalRecordMapper.CURRENT_EXPORT_TEMPLATE_VERSION.
// The backend currently generates template v3; using v2 here makes a reused
// successful export look like it was never created.
const CURRENT_EXPORT_TEMPLATE_VERSION = 3

type MainView = 'workbench' | 'audio' | 'transcript' | 'record' | 'export' | 'audit'
type WorkflowView = 'workbench' | 'audio' | 'transcript' | 'record' | 'export'
type ModalKind = 'new-patient' | 'cancel' | 'finish' | 'regenerate' | 'confirm' | 'help' | 'activity' | null
type EditableUtteranceRole = 'DOCTOR' | 'PATIENT' | 'OTHER'
type ManualPatientForm = { name: string; gender: string; age: number | '' | null; phone: string; idNo: string }
type ManualPatientField = keyof ManualPatientForm

const PATIENT_PHONE_PATTERN = /^1[3-9]\d{9}$/
const PATIENT_ID_PATTERN = /^\d{17}[\dX]$/
const PATIENT_ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
const PATIENT_ID_CHECK_CODES = '10X98765432'

const patients = ref<Patient[]>([])
const visits = ref<Visit[]>([])
const selectedPatientId = ref('')
const view = ref<MainView>('workbench')
const queueSearch = ref('')
const patientSearchResults = ref<Patient[] | null>(null)
const searchBusy = ref(false)
const busy = ref(false)
const actionBusy = ref<'' | 'upload' | 'transcribe' | 'generate' | 'extract' | 'confirm-extraction' | 'save' | 'role' | 'reclassify-roles' | 'confirm'>('')
const asrProvider = ref<AsrProvider>('DASHSCOPE')
const activeAsrProvider = ref<AsrProvider | null>(null)
const recordings = ref<Recording[]>([])
const transcript = ref<Transcript | null>(null)
const extraction = ref<ClinicalExtraction | null>(null)
const record = ref<MedicalRecord | null>(null)
const recordForm = ref<MedicalRecordContent | null>(null)
const confirmations = ref<Confirmation[]>([])
const exports = ref<RecordExport[]>([])
const auditLogs = ref<AuditLog[]>([])
const auditOperators = ref<AuditOperator[]>([])
const auditFrom = ref(dateInputValue(daysBefore(30)))
const auditTo = ref(dateInputValue(new Date()))
const auditDoctorId = ref('')
const auditAction = ref<AuditAction | ''>('')
const auditPage = ref(1)
const auditTotal = ref(0)
const auditBusy = ref(false)
const transcriptTab = ref<'dialogue' | 'edit' | 'facts'>('dialogue')
const transcriptDraft = ref('')
// 混合来源只能在当前快照上由医生一次性选择；快照变动后旧选择不能复用。
const selectedLlmRoute = ref<LlmProvider | null>(null)
const modal = ref<ModalKind>(null)
const modalError = ref('')
const confirmChecked = ref(false)
const newPatientForm = ref<ManualPatientForm>({ name: '', gender: '', age: null, phone: '', idNo: '' })
const newPatientErrors = ref<Partial<Record<ManualPatientField, string>>>({})
const dragging = ref(false)
const playingId = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const modalDialog = ref<HTMLElement | null>(null)
const activity = ref<{ time: string; text: string }[]>([])
const recordingElapsedMs = ref(0)
const roleReviewElements = new Map<string, HTMLElement>()
let recordingTimer: number | undefined
let recordingStartedAt = 0
let audioPlayer: HTMLAudioElement | null = null
let recorder: MediaRecorder | null = null
let recorderStream: MediaStream | null = null
let recordChunks: Blob[] = []
let audioObjectUrl: string | null = null
let audioLoadAbort: AbortController | null = null
let visitStateRevision = 0
let patientSearchRevision = 0
const recordingState = ref<'idle' | 'recording' | 'paused'>('idle')


watch(modal, async value => {
  document.body.classList.toggle('modal-open', !!value)
  if (value === 'new-patient') {
    modalError.value = ''
    newPatientForm.value = { name: '', gender: '', age: null, phone: '', idNo: '' }
    newPatientErrors.value = {}
  }
  if (value) {
    await nextTick()
    modalDialog.value?.focus()
  }
})

watch(() => transcript.value?.snapshot_id, () => {
  selectedLlmRoute.value = null
})

const titles: Record<MainView, [string, string]> = {
  workbench: ['接诊工作台', '从医患对话到结构化病历，让每一次接诊更从容。'],
  audio: ['录音上传', '完整上传问诊录音，支持一次接诊关联多段录音。'],
  transcript: ['转写结果', '回看医患对话，核对并编辑本次接诊采用的转写文本。'],
  record: ['病历审核与签署', '核对并完善病历草稿，确认后锁定当前版本。'],
  export: ['病历导出', '将已确认的病历由后端生成 Word 或 PDF 文件。'],
  audit: ['日志审计', '查看全系统医生的关键操作记录。']
}

function dateInputValue(value: Date) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function daysBefore(days: number) {
  const value = new Date()
  value.setDate(value.getDate() - days)
  return value
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

/**
 * 选择队列中代表患者状态的接诊记录。
 *
 * <p>优先选择进行中接诊，其次是待接诊，最后选择最近的历史接诊，确保多次接诊患者的状态正确。
 */
function patientVisit(patientId: string): Visit | null {
  if (!patientId) return null
  const list = visits.value
    // 后端会删除已取消接诊；此处忽略它们，以兼容仍可能保留取消记录的历史数据。
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

function recentVisitStatus(visit: Visit) {
  return ({
    WAITING: '待接诊',
    ACTIVE: '接诊中',
    COMPLETED: '已完成',
    ARCHIVED: '已归档',
    CANCELLED: '已取消'
  } as Record<string, string>)[visit.status] || visit.status
}

function auditActionLabel(action: string) {
  return ({
    LOGIN: '登录系统',
    RECORDING_UPLOADED: '上传录音',
    MEDICAL_RECORD_CONFIRMED: '确认病历',
    MEDICAL_RECORD_EXPORT: '病历导出'
  } as Record<string, string>)[action] || action
}

function auditResultLabel(result: string) {
  return result === 'SUCCESS' ? '成功' : result === 'FAILED' ? '失败' : result
}

function doctorRoleLabel(role: string) {
  return ({ DOCTOR: '医生', DEPARTMENT_HEAD: '科室长', ADMIN: '管理员' } as Record<string, string>)[role] || '医生'
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
const canViewAudit = computed(() => ['DEPARTMENT_HEAD', 'ADMIN'].includes(props.doctor.role))
const showLegacyWorkspace = false
const readOnlyCurrentPatient = computed(() => {
  if (!currentPatient.value || !canViewAudit.value) return false
  return currentPatient.value.created_by !== props.doctor.id
})
const canCreateVisit = (patientId: string) => !patientVisit(patientId)
const currentVisit = computed(() => {
  return patientVisit(selectedPatientId.value)
})
const queuePatients = computed(() => patientSearchResults.value ?? patients.value)
const recentVisits = computed(() => {
  const patientById = new Map(patients.value.map(patient => [patient.id, patient]))
  const seenPatientIds = new Set<string>()
  const items: { patient: Patient; visit: Visit }[] = []
  const orderedVisits = visits.value
    .filter(visit => visit.status !== 'CANCELLED')
    .slice()
    .sort((left, right) => {
      const leftTime = String(left.last_activity_at || left.created_at || '')
      const rightTime = String(right.last_activity_at || right.created_at || '')
      return rightTime.localeCompare(leftTime)
    })

  for (const visit of orderedVisits) {
    if (seenPatientIds.has(visit.patient_id)) continue
    const patient = patientById.get(visit.patient_id)
    if (!patient) continue
    seenPatientIds.add(visit.patient_id)
    items.push({ patient, visit })
    if (items.length === 3) break
  }
  return items
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
const llmRouting = computed(() => extraction.value || transcript.value)
const routeSelectionRequired = computed(() => false)
const sourceRouteLabel = computed(() => {
  const route = llmRouting.value?.source_route
  return route === 'DASHSCOPE' ? '公网 LLM（DashScope）'
    : route === 'LOCAL' ? '内网转写来源 · 分析走公网'
    : route === 'MIXED' ? '混合转写来源 · 分析走公网'
    : ''
})
const routeSelectionReady = computed(() => !routeSelectionRequired.value || !!selectedLlmRoute.value)
const workflowView = computed<WorkflowView>(() => {
  if (waiting.value) return 'workbench'
  if (!recordings.value.length || !allTranscribed.value) return 'audio'
  if (sourceDirty.value) return 'transcript'
  if (!record.value?.record_id) return 'transcript'
  if (!confirmed.value) return 'record'
  return 'export'
})
const stage = computed(() => {
  const stages: WorkflowView[] = ['workbench', 'audio', 'transcript', 'record', 'export']
  // 已完成接诊不可变更，但保留产物仍可查看；应高亮医生正在查看的页面，
  // 而不是始终将进度指示固定在最后一步。
  if ((completed.value || view.value === 'record') && view.value !== 'workbench' && stages.includes(view.value as WorkflowView)) {
    return stages.indexOf(view.value as WorkflowView)
  }
  return stages.indexOf(workflowView.value)
})
const activeCount = computed(() => patients.value.filter(patient => patientVisit(patient.id)?.status === 'ACTIVE').length)
const waitingCount = computed(() => patients.value.filter(patient => {
  const visit = patientVisit(patient.id)
  return !visit || visit.status === 'WAITING'
}).length)
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
const turnsMatchTranscript = computed(() => {
  const source = transcript.value
  if (!source?.turns.length) return false
  return source.transcript === source.turns.map(turn => `${roleLabel(turn)}：${turn.text}`).join('\n\n')
})
const segments = computed(() => {
  const source = transcript.value
  if (!source) return []
  if (source.turns.length && (!source.edited || turnsMatchTranscript.value)) {
    return source.turns.map(item => ({ role: roleLabel(item), roleCode: item.role, time: formatTime(item.start_ms), text: item.text, turn: item }))
  }
  return source.transcript.split(/\n+/).filter(Boolean).map((line, index) => {
    const match = line.match(/^(医生|患者|其他人|未识别角色|说话人 ?[0-9]+)[：:]\s*(.*)$/)
    const role = match ? match[1] : '未识别角色'
    return { role, roleCode: role === '医生' ? 'DOCTOR' : role === '患者' ? 'PATIENT' : 'OTHER', time: source.turns[index] ? formatTime(source.turns[index].start_ms) : '00:00', text: match ? match[2] : line, turn: undefined }
  })
})
const roleReviewCount = computed(() => transcript.value?.turns.filter(turn => turn.role_review_required).length || 0)
const unclassifiedRoleCount = computed(() => transcript.value?.turns.filter(turn =>
  turn.role_source === 'AUTO' || turn.role_source === 'UNKNOWN').length || 0)
const extractionFieldLabels: Record<string, string> = {
  chief_complaint: '主诉', onset_course: '起病与病程', symptom_characteristics: '症状特征',
  associated_symptoms: '伴随症状', past_medical_history: '既往史', medication_history: '用药史',
  allergy_history: '过敏史', family_history: '家族史', social_history: '生活史',
  doctor_diagnosis: '医生明确诊断意见', doctor_medication: '医生明确用药方案', doctor_followup: '医生明确随访安排'
}
const extractionFacts = computed(() => Object.entries(extraction.value?.fields || {}).map(([key, fact]) => ({
  key, label: extractionFieldLabels[key] || key, fact
})))
const extractionConfirmed = computed(() => extraction.value?.status === 'CONFIRMED'
  && extraction.value.snapshot_id === transcript.value?.snapshot_id
  && extraction.value.snapshot_hash === transcript.value?.snapshot_hash)
const extractionStatusText = computed(() => ({
  PENDING: '待生成', GENERATED: '待整体确认', CONFIRMED: '已整体确认', FAILED: '需修正转写', STALE: '转写已更新'
})[extraction.value?.status || 'PENDING'] || extraction.value?.status || '待生成')

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
const visitDate = computed(() => currentVisit.value ? formatDateTime(currentVisit.value.created_at).slice(0, 10) : formatDateTime(new Date()).slice(0, 10))
const isPlaying = (item: Recording) => playingId.value === item.id
const avatarClass = (patient: Patient | null) => patient?.patient_no === '002' ? 'lilac' : patient?.patient_no === '003' ? 'blue' : ''
const highlightText = (text: string) => [{ text, mark: false }]
const recordingStateLabel = computed(() => ({ idle: '准备录音', recording: '正在录音', paused: '已暂停' })[recordingState.value])
const recordingTimeLabel = computed(() => formatRecordingDuration(recordingElapsedMs.value))

function toast(text: string, type: MessageType = 'info') {
  showMessage(text, type)
}

function addLog(text: string) {
  activity.value.unshift({ time: formatDateTime(new Date()), text })
}

async function refreshCore(keepSelection = true) {
  const [patientList, visitList] = await Promise.all([api.patients(), api.visits()])
  patients.value = patientList
  visits.value = visitList
  if (!keepSelection || !patientList.some(p => p.id === selectedPatientId.value)) {
    selectedPatientId.value = patientList[0]?.id || ''
  }
}

async function refreshVisitState() {
  const visit = currentVisit.value
  const revision = ++visitStateRevision
  if (!visit || readOnlyCurrentPatient.value) {
    recordings.value = []
    transcript.value = null
    extraction.value = null
    record.value = null
    recordForm.value = null
    confirmations.value = []
    exports.value = []
    transcriptDraft.value = ''
    return
  }
  const [recordingList, transcriptState, extractionState, recordState, confirmationList, exportList] = await Promise.all([
    api.recordings(visit.id),
    api.transcript(visit.id),
    api.clinicalExtraction(visit.id),
    api.medicalRecord(visit.id),
    api.confirmations(visit.id),
    api.exports(visit.id)
  ])
  if (revision !== visitStateRevision || currentVisit.value?.id !== visit.id) return
  recordings.value = recordingList
  transcript.value = transcriptState
  extraction.value = extractionState
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
    toast(error instanceof Error ? error.message : '数据加载失败', 'error')
  } finally {
    busy.value = false
  }
}

async function loadAudit(page = 1) {
  auditBusy.value = true
  try {
    const result = await api.auditLogs({
      from: auditFrom.value || undefined,
      to: auditTo.value || undefined,
      doctorId: auditDoctorId.value || undefined,
      action: auditAction.value || undefined,
      page,
      pageSize: 20
    })
    auditLogs.value = result.items
    auditTotal.value = result.total
    auditPage.value = result.page
  } catch (error) {
    toast(error instanceof Error ? error.message : '日志查询失败', 'error')
  } finally {
    auditBusy.value = false
  }
}

async function searchPatients() {
  const revision = ++patientSearchRevision
  searchBusy.value = true
  try {
    const results = await api.patients(queueSearch.value.trim())
    // 连续搜索时仅展示最后一次请求的结果，避免慢请求覆盖较新的查询。
    if (revision === patientSearchRevision) patientSearchResults.value = results
  } catch (error) {
    toast(error instanceof Error ? error.message : '患者搜索失败', 'error')
  } finally {
    if (revision === patientSearchRevision) searchBusy.value = false
  }
}

async function loadAuditOperators() {
  try {
    auditOperators.value = await api.auditOperators()
  } catch (error) {
    toast(error instanceof Error ? error.message : '操作人列表加载失败', 'error')
  }
}

async function selectPatient(patientId: string) {
  if (busy.value || actionBusy.value) return
  if (recordingState.value !== 'idle' || recorder) {
    toast('请先结束当前网页录音，再切换患者。', 'error')
    return
  }
  stopAudio()
  visitStateRevision += 1
  selectedPatientId.value = patientId
  view.value = 'workbench'
  transcriptTab.value = 'dialogue'
  await loadAll(true)
  navigate(workflowView.value)
}

function navigate(next: MainView) {
  const workflowViews: WorkflowView[] = ['workbench', 'audio', 'transcript', 'record', 'export']
  if (readOnlyCurrentPatient.value && workflowViews.includes(next as WorkflowView) && next !== 'workbench') {
    view.value = 'workbench'
    toast('当前为只读状态，该患者由其他医生负责接诊。', 'info')
    return
  }
  if (next === 'audit') {
    if (!canViewAudit.value) {
      toast('当前账号无权查看日志审计。', 'error')
      return
    }
    view.value = 'audit'
    void Promise.all([loadAudit(1), loadAuditOperators()])
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  if (!workflowViews.includes(next as WorkflowView)) {
    view.value = next
  } else {
    const requested = next as WorkflowView
    const current = workflowView.value
    if (completed.value && requested !== 'workbench') {
      view.value = requested
    } else if (completed.value) {
      view.value = workflowView.value
      toast('已完成接诊仅支持查看步骤 02 至 05。')
    } else {
    const sameStep = requested === current
    const reviewingSignedRecord = requested === 'record' && current === 'export' && !!record.value?.record_id
    if (sameStep || reviewingSignedRecord) view.value = requested
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
    toast(error instanceof Error ? error.message : '创建接诊失败', 'error')
  } finally {
    busy.value = false
  }
}

async function startVisit() {
  if (!currentPatient.value) return
  if (readOnlyCurrentPatient.value) {
    toast('当前为只读状态，该患者由其他医生负责接诊。', 'info')
    return
  }
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
    toast(error instanceof Error ? error.message : '开始接诊失败', 'error')
  } finally {
    busy.value = false
  }
}

async function doCancel() {
  if (!currentVisit.value) return
  if (actionBusy.value) {
    toast('当前操作正在处理中，请等待完成后再取消接诊。', 'error')
    return
  }
  if (recordingState.value !== 'idle' || recorder) {
    toast('请先结束当前网页录音，再取消接诊。', 'error')
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
    toast(error instanceof Error ? error.message : '取消接诊失败', 'error')
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
    toast(error instanceof Error ? error.message : '结束接诊失败', 'error')
  } finally {
    busy.value = false
  }
}

async function createNewPatient() {
  modalError.value = ''
  newPatientErrors.value = {}
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
  const phoneValid = validateNewPatientField('phone')
  const idValid = validateNewPatientField('idNo')
  if (!phoneValid || !idValid) {
    modalError.value = '请先修正联系方式或证件号的格式错误。'
    return
  }
  busy.value = true
  try {
    const patient = await api.createPatient({ name, gender, age, phone: form.phone, idNo: form.idNo.trim().toUpperCase() })
    patients.value = [patient, ...patients.value.filter(item => item.id !== patient.id)]
    selectedPatientId.value = patient.id
    modal.value = null
    await refreshVisitState()
    toast(`患者 ${patient.name} 已新增，可在准备就绪后开始接诊。`, 'success')
  } catch (error) {
    toast(error instanceof Error ? error.message : '创建患者失败', 'error')
  } finally {
    busy.value = false
  }
}

function openNewPatientModal() {
  modalError.value = ''
  newPatientForm.value = { name: '', gender: '', age: null, phone: '', idNo: '' }
  newPatientErrors.value = {}
  modal.value = 'new-patient'
}

function patientPhoneError(value: string) {
  return value.trim() && !PATIENT_PHONE_PATTERN.test(value.trim()) ? '请输入正确的11位手机号' : ''
}

function patientIdNumberError(value: string) {
  const idNo = value.trim().toUpperCase()
  if (!idNo) return ''
  if (!PATIENT_ID_PATTERN.test(idNo)) return '请输入18位身份证号'
  const year = Number(idNo.slice(6, 10))
  const month = Number(idNo.slice(10, 12))
  const day = Number(idNo.slice(12, 14))
  const birth = new Date(Date.UTC(year, month - 1, day))
  const now = new Date()
  if (year < 1900 || month < 1 || month > 12 || birth.getUTCFullYear() !== year
    || birth.getUTCMonth() !== month - 1 || birth.getUTCDate() !== day || birth > now) {
    return '身份证中的出生日期无效'
  }
  const checksum = idNo.split('').slice(0, 17).reduce((sum, digit, index) =>
    sum + Number(digit) * PATIENT_ID_WEIGHTS[index], 0)
  return PATIENT_ID_CHECK_CODES[checksum % 11] === idNo[17] ? '' : '身份证校验码不正确'
}

function validateNewPatientField(field: 'phone' | 'idNo') {
  const value = newPatientForm.value[field]
  const error = field === 'phone' ? patientPhoneError(value) : patientIdNumberError(value)
  if (error) newPatientErrors.value[field] = error
  else delete newPatientErrors.value[field]
  return !error
}

function clearNewPatientError(field: 'phone' | 'idNo') {
  delete newPatientErrors.value[field]
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
      toast('仅支持 MP3、WAV、M4A 或 WEBM 格式。', 'error')
      continue
    }
    const duration = await audioDuration(file, fallbackDuration)
    if (duration <= 0) {
      toast(`无法读取「${file.name}」，请选用有效音频。`, 'error')
      continue
    }
    actionBusy.value = 'upload'
    try {
      await api.uploadRecording(currentVisit.value.id, file, duration)
      addLog(`上传录音：${file.name}`)
    } catch (error) {
      toast(error instanceof Error ? error.message : '上传失败', 'error')
    }
  }
  actionBusy.value = ''
  await loadAll(true)
  if (view.value === 'workbench') toast('录音已上传，可开始转写。')
}

watch(() => currentVisit.value?.id, () => {
  asrProvider.value = 'DASHSCOPE'
  activeAsrProvider.value = null
})

async function startTranscription() {
  if (!currentVisit.value || locked.value) return
  actionBusy.value = 'transcribe'
  try {
    const visitId = currentVisit.value.id
    const job = await api.transcribe(visitId, asrProvider.value)
    if (currentVisit.value?.id !== visitId) return
    activeAsrProvider.value = job.provider_route
    await loadAll(true)
    addLog(`已提交${job.provider_route === 'LOCAL' ? '本地' : '公网'} ASR 转写任务`)
    for (let attempt = 0; attempt < 240; attempt++) {
      await new Promise(resolve => window.setTimeout(resolve, 3000))
      if (currentVisit.value?.id !== visitId) return
      const state = await api.transcribeStatus(visitId, job.job_id)
      if (currentVisit.value?.id !== visitId) return
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
  // 服务器可能在拒绝本次提交前恢复了过期 PROCESSING 记录（例如缺少存储凭据时），
  // 重新加载可让卡片立即恢复为可重试状态。
    await loadAll(true)
    toast(error instanceof Error ? error.message : '转写失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

async function saveTranscript() {
  if (!currentVisit.value || locked.value) return
  if (!transcriptDraft.value.trim()) {
    toast('采用的转写文本不能为空。', 'error')
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
    toast(error instanceof Error ? error.message : '保存转写失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

function roleStatus(turn: Utterance | undefined) {
  if (!turn) return ''
  if (turn.role_source === 'MANUAL') return '人工确认'
  if (turn.role_source === 'FALLBACK') return 'AI 未完成'
  if (turn.role_source === 'AUTO' || turn.role_source === 'UNKNOWN') return 'AI 未分析'
  if (turn.role_review_required) return '待人工核对'
  if (turn.role_source === 'LLM' && turn.role_confidence != null) return `AI ${turn.role_confidence}%`
  return '待人工核对'
}

async function updateUtteranceRole(turn: Utterance | undefined, event: Event) {
  const role = editableUtteranceRole((event.target as HTMLSelectElement).value)
  if (!role) return
  await saveUtteranceRole(turn, role)
}

function editableUtteranceRole(value: string): EditableUtteranceRole | null {
  return ['DOCTOR', 'PATIENT', 'OTHER'].includes(value) ? value as EditableUtteranceRole : null
}

async function saveUtteranceRole(turn: Utterance | undefined, role: EditableUtteranceRole) {
  if (!currentVisit.value || !turn?.id || locked.value || actionBusy.value) return
  actionBusy.value = 'role'
  try {
    const updated = await api.updateUtteranceRole(currentVisit.value.id, turn.id, role)
    transcript.value = updated
    transcriptDraft.value = updated.transcript
    addLog('人工修订句段说话角色')
    toast('角色已人工确认，请重新生成并确认病历。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '角色更新失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

async function confirmCurrentUtteranceRole(turn: Utterance | undefined) {
  if (!turn) return
  const role = editableUtteranceRole(turn.role)
  if (role) await saveUtteranceRole(turn, role)
}

async function reclassifyTranscriptRoles() {
  if (!currentVisit.value || !roleReviewCount.value || !turnsMatchTranscript.value || locked.value) return
  actionBusy.value = 'reclassify-roles'
  try {
    const updated = selectedLlmRoute.value
      ? await api.reclassifyTranscriptRoles(currentVisit.value.id, selectedLlmRoute.value)
      : await api.reclassifyTranscriptRoles(currentVisit.value.id)
    transcript.value = updated
    transcriptDraft.value = updated.transcript
    addLog('使用 AI 重新判断未人工确认的句段角色')
    toast('AI 角色判断已更新，请继续核对待人工确认的句段。')
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 角色判断失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

function setRoleReviewElement(utteranceId: string | undefined, element: unknown) {
  if (!utteranceId) return
  if (element && typeof (element as HTMLElement).scrollIntoView === 'function') {
    roleReviewElements.set(utteranceId, element as HTMLElement)
  } else {
    roleReviewElements.delete(utteranceId)
  }
}

async function openRoleReview() {
  transcriptTab.value = 'dialogue'
  await nextTick()
  const firstReviewId = transcript.value?.turns.find(turn => turn.role_review_required)?.id
  if (firstReviewId) roleReviewElements.get(firstReviewId)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
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
    toast('当前浏览器不支持录音功能。', 'error')
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
    toast('无法访问麦克风，请检查浏览器权限。', 'error')
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

async function generateClinicalExtraction() {
  if (!currentVisit.value || locked.value) return
  actionBusy.value = 'extract'
  try {
    extraction.value = await api.generateClinicalExtraction(currentVisit.value.id, 'DASHSCOPE')
    transcriptTab.value = 'facts'
    await loadAll(true)
    if (extraction.value?.status === 'GENERATED') {
      addLog('生成可追溯信息提取结果')
      toast('信息提取已生成，请核对原文证据后整体确认。')
    } else {
      toast(extraction.value?.quality_issues[0] || '请先在全文编辑中修正转写。', 'error')
    }
  } catch (error) {
    toast(error instanceof Error ? error.message : '信息提取失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

async function confirmClinicalExtraction() {
  if (!currentVisit.value || locked.value || extraction.value?.status !== 'GENERATED') return
  actionBusy.value = 'confirm-extraction'
  try {
    extraction.value = await api.confirmClinicalExtraction(currentVisit.value.id)
    addLog('整体确认信息提取结果')
    await loadAll(true)
    toast('信息提取已确认，可生成病历草稿。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '信息提取确认失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

async function generateRecord() {
  if (!currentVisit.value || locked.value) return
  if (!extractionConfirmed.value) {
    transcriptTab.value = 'facts'
    navigate('transcript')
    toast('请先生成并整体确认当前转写的信息提取结果。', 'error')
    return
  }
  actionBusy.value = 'generate'
  try {
    record.value = await api.generateMedicalRecord(currentVisit.value.id)
    recordForm.value = record.value.content ? JSON.parse(JSON.stringify(record.value.content)) : null
    addLog(`生成病历草稿 v${record.value.version_no}`)
    await loadAll(true)
    navigate('record')
    toast('病历草稿已生成，请医生核对。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '病历生成失败', 'error')
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
    toast(error instanceof Error ? error.message : '保存病历失败', 'error')
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
    toast('已创建病历修订版，请完成核对并重新签署。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '进入修改失败', 'error')
  } finally {
    busy.value = false
  }
}

async function requestConfirm() {
  if (view.value !== 'record') {
    navigate('record')
    return
  }
  if (!currentVisit.value || !recordForm.value || locked.value) return
  if (sourceDirty.value) {
    navigate('transcript')
    toast('录音或转写已更新，请重新生成病历后再签署。', 'error')
    return
  }
  if (missingFields.value.length) {
    navigate('record')
    toast('请完善：' + missingFields.value.join('、'), 'error')
    return
  }
  actionBusy.value = 'save'
  try {
    record.value = await api.saveMedicalRecord(currentVisit.value.id, recordForm.value)
    recordForm.value = record.value.content ? JSON.parse(JSON.stringify(record.value.content)) : null
    modalError.value = ''
    confirmChecked.value = false
    modal.value = 'confirm'
  } catch (error) {
    toast(error instanceof Error ? error.message : '保存病历失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

async function doConfirm() {
  if (!currentVisit.value || !confirmChecked.value) {
    modalError.value = '请勾选核对声明后确认。'
    return
  }
  actionBusy.value = 'confirm'
  try {
    record.value = await api.confirmMedicalRecord(currentVisit.value.id, true)
    addLog(`${props.doctor.display_name}医生签署病历 v${record.value.version_no}`)
    modal.value = null
    await loadAll(true)
    navigate('record')
    toast('病历已签署，当前版本现可导出。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '确认失败', 'error')
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
  let item = exports.value.find(e => e.format === format && e.version_no === versionNo
    && e.template_version === CURRENT_EXPORT_TEMPLATE_VERSION)
  // 复用已完成的导出任务（尤其是已完成接诊），避免用户每次打开导出页面都创建重复任务。
  if (item?.status === 'SUCCEEDED') return item.id
  if (!item || item.status === 'FAILED') {
    await recordExportLog(format)
    item = exports.value.find(e => e.format === format && e.version_no === versionNo
      && e.template_version === CURRENT_EXPORT_TEMPLATE_VERSION)
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

function isMissingExportFile(error: unknown) {
  return typeof error === 'object' && error !== null && (error as { status?: unknown }).status === 404
}

async function exportRecord(format: 'DOCX' | 'PDF', label: string) {
  if (busy.value || !record.value?.content || !confirmed.value) return
  busy.value = true
  try {
    // 旧成功记录可能没有物理文件。下载接口会将其回退为 FAILED；刷新后本次点击自动重新提交一次。
    for (let recoveryAttempt = 0; recoveryAttempt < 2; recoveryAttempt++) {
      const exportId = await waitForExport(format)
      if (!exportId) throw new Error(`${label} 导出任务未创建`)
      try {
        await downloadExport(exportId, format)
        await loadAll(true)
        toast(`${label} 病历已生成并下载。`)
        return
      } catch (error) {
        if (recoveryAttempt === 0 && isMissingExportFile(error)) {
          await loadAll(true)
          continue
        }
        throw error
      }
    }
  } catch (error) { toast(error instanceof Error ? error.message : `${label} 导出失败`, 'error') }
  finally { busy.value = false }
}

async function exportWord() { await exportRecord('DOCX', 'Word') }

async function exportPdf() {
  await exportRecord('PDF', 'PDF')
}

async function exportFromBottom() {
  if (!confirmed.value || busy.value) return
  if (view.value !== 'export') {
    navigate('export')
    return
  }
  // 底部操作在每个工作流页面均可用；已处于导出页时默认导出 Word 文档，
  // PDF 仍可通过上方明确的格式卡片导出。
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
  // 音频端点需要鉴权。先获取字节可附加 Bearer Token，原生 Audio(src) 请求无法做到。
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
      toast('音频无法播放，请检查录音格式或后端音频接口。', 'error')
    }
    await player.play()
  } catch (error) {
    if (controller.signal.aborted) return
    console.warn('[audio] audio request failed', { recordingId: item.id, error })
    if (playingId.value === item.id) {
      stopAudio()
      toast(error instanceof Error ? error.message : '音频无法播放。', 'error')
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
        <button v-if="canViewAudit" class="nav-item" :class="{ active: view === 'audit' }" @click="navigate('audit')"><Icon name="list" />日志审计</button>
      </nav>
      <div class="recent">
        <div class="nav-label">最近接诊</div>
        <template v-if="recentVisits.length">
          <button v-for="item in recentVisits" :key="item.visit.id" class="recent-item" :class="{ selected: item.patient.id === selectedPatientId }"
                  :title="`${item.patient.name} · ${recentVisitStatus(item.visit)} · ${formatDateTime(item.visit.last_activity_at || item.visit.created_at)}`"
                  @click="selectPatient(item.patient.id)">
            <span class="avatar" :class="avatarClass(item.patient)">{{ item.patient.name[0] }}</span>
            <span class="recent-copy"><span class="recent-name">{{ item.patient.name }}</span><span class="recent-meta">{{ recentVisitStatus(item.visit) }} · {{ formatDateTime(item.visit.last_activity_at || item.visit.created_at) }}</span></span>
            <span class="dot" :class="{ green: item.visit.status === 'ACTIVE' }"></span>
          </button>
        </template>
        <div v-else class="small-muted recent-empty">暂无接诊记录</div>
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
          <div><b>{{ doctor.display_name }} {{ doctorRoleLabel(doctor.role) }}</b><span>{{ doctor.department_name }} · {{ doctorRoleLabel(doctor.role) }}</span></div>
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

      <template v-if="view !== 'audit'">
      <section class="card queue">
        <div class="queue-head">
          <div class="queue-title"><Icon name="users" />今日接诊 <span class="badge">{{ patients.length }} 位</span>
            <div class="queue-stats"><span>待接诊 <b>{{ String(waitingCount).padStart(2, '0') }}</b></span><i></i><span>接诊中 <b>{{ String(activeCount).padStart(2, '0') }}</b></span><i></i><span>已完成 <b>{{ String(completedCount).padStart(2, '0') }}</b></span></div>
          </div>
          <div class="queue-search-wrap">
            <input v-model="queueSearch" class="queue-search" placeholder="搜索姓名或编号" @keydown.enter.prevent="searchPatients" />
            <button type="button" class="btn small" :disabled="searchBusy" @click="searchPatients">{{ searchBusy ? '搜索中' : '搜索' }}</button>
          </div>
          <button class="queue-add" :disabled="busy" @click="openNewPatientModal"><Icon name="plus" />新增患者</button>
        </div>
        <div class="queue-cards">
          <button v-for="patient in queuePatients" :key="patient.id" class="patient-tile" :class="{ selected: patient.id === selectedPatientId }" @click="selectPatient(patient.id)">
            <span class="avatar" :class="avatarClass(patient)">{{ patient.name[0] }}</span>
            <span>
              <span class="patient-name"><b>{{ patient.name }}</b><span>{{ patient.gender }} · {{ patient.age ?? '—' }} 岁</span></span>
              <span class="patient-meta">患者编号 <strong>{{ patient.patient_no || '—' }}</strong></span>
              <span class="patient-visit-no" :title="patientVisit(patient.id)?.visit_no || '暂无接诊记录'">接诊 {{ patientVisitNo(patient.id) }}</span>
              <template v-if="canViewAudit">
                <span class="patient-owner">创建：{{ patient.created_by_name || '历史数据未标记' }}</span>
                <span v-if="patientVisit(patient.id)" class="patient-owner">接诊：{{ patientVisit(patient.id)?.doctor_name || '—' }} · {{ formatDateTime(patientVisit(patient.id)?.last_activity_at || patientVisit(patient.id)?.created_at) }}</span>
              </template>
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
              <span><Icon name="user" />患者创建人 <b>{{ currentPatient?.created_by_name || '历史数据未标记' }}</b></span>
              <span v-if="currentVisit"><Icon name="user" />接诊医生 <b>{{ currentVisit.doctor_name || '—' }}</b></span>
              <span><Icon name="calendar" />接诊日期 <b>{{ visitDate }}</b></span>
            </div>
          </div>
        </div>
        <div v-if="readOnlyCurrentPatient" class="patient-actions read-only-actions">
          <span class="read-only-note"><Icon name="lock" />当前为只读状态，该患者由其他医生负责接诊</span>
        </div>
        <div v-else class="patient-actions">
          <button v-if="waiting" class="btn primary" :disabled="busy" @click="startVisit"><Icon name="play" />开始接诊</button>
          <template v-else-if="!closed">
            <button class="btn" :disabled="!exported || busy" title="当前版本确认并完成导出后可结束接诊" @click="modal='finish'"><Icon name="stop" />结束接诊</button>
            <button class="btn" :disabled="busy || !!actionBusy" @click="modalError=''; modal='cancel'">取消接诊</button>
          </template>
          <span v-else class="small-muted">该患者接诊已完成，不支持重复接诊</span>
        </div>
      </section>

      <nav v-if="!readOnlyCurrentPatient" class="steps" aria-label="接诊流程">
        <button v-for="(label, index) in ['患者 / 接诊', '录音上传', '转写结果', '病历审核与签署', '病历导出']" :key="label"
                class="step" :class="{ done: index < stage || exported, current: index === stage }"
                :disabled="completed && index === 0"
                @click="navigate((['workbench','audio','transcript','record','export'] as MainView[])[index])">
          <span class="step-number"><Icon v-if="index < stage || exported" name="check" /><template v-else>{{ String(index + 1).padStart(2, '0') }}</template></span>
          <span class="step-label">{{ label }}<small>{{ ['CONSULTATION','UPLOAD','TRANSCRIPTION','REVIEW & SIGN','EXPORT'][index] }}</small></span>
        </button>
      </nav>

      <div v-if="sourceDirty && !closed && !confirmed" class="info-banner"><Icon name="info" /><span>录音或转写已更新，请重新生成病历后再确认。</span></div>
      <div v-if="readOnlyCurrentPatient" class="info-banner read-only-banner"><Icon name="lock" /><span>当前为只读状态，该患者由其他医生负责接诊，仅展示患者信息和接诊状态。</span></div>
      </template>

      <div v-if="view === 'workbench'" class="consultation-start-view">
        <section class="card consultation-start-card" :class="{ 'is-active': active, 'is-closed': closed }">
          <div class="consultation-step-chip"><span class="step-chip-number">01</span><span>患者 / 接诊</span><small>CONSULTATION</small></div>
          <div class="consultation-start-content">
            <div class="consultation-start-icon"><Icon :name="waiting ? 'play' : closed ? 'check' : 'mic'" /></div>
            <span class="consultation-state-kicker">{{ readOnlyCurrentPatient ? 'READ ONLY' : waiting ? 'READY TO START' : closed ? 'VISIT CLOSED' : 'VISIT IN PROGRESS' }}</span>
            <h2>{{ readOnlyCurrentPatient ? `接诊状态：${statusText(currentVisit)}` : waiting ? '准备开始本次接诊' : closed ? `本次接诊${statusText(currentVisit)}` : '本次接诊进行中' }}</h2>
            <p>{{ readOnlyCurrentPatient ? '当前患者由其他医生负责接诊，科室长仅可查看患者信息、接诊医生和接诊状态。' : waiting ? '确认患者身份后开始接诊，下一步即可上传录音或使用网页录音。' : closed ? '本次接诊记录已保留。已完成接诊的患者不支持重复接诊。' : '接诊已开始，前往录音上传即可使用文件上传或网页录音。' }}</p>
            <button v-if="!readOnlyCurrentPatient && waiting" class="btn primary start-consultation-btn" :disabled="busy || !currentPatient" @click="startVisit"><Icon name="play" />开始接诊</button>
            <button v-else-if="!readOnlyCurrentPatient && active" class="btn primary start-consultation-btn" :disabled="busy" @click="navigate('audio')"><Icon name="mic" />进入录音上传</button>
            <span v-else-if="readOnlyCurrentPatient" class="small-muted">仅可查看状态</span>
            <span v-else class="small-muted">该患者接诊已完成，不支持重复接诊</span>
          </div>
          <div class="consultation-start-meta">
            <span><Icon name="user" />当前患者 <b>{{ currentPatient?.name || '未选择患者' }}</b></span>
            <span><Icon name="id" />患者编号 <b>{{ currentPatient?.patient_no || '—' }}</b></span>
            <span><Icon name="shield" />接诊记录 <b>{{ currentVisit ? `${statusText(currentVisit)} · ${patientVisitNo(selectedPatientId)}` : '尚未创建' }}</b></span>
          </div>
        </section>
      </div>

      <div v-else-if="showLegacyWorkspace" class="workspace">
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
              <div v-if="recordings.length" class="asr-controls">
                <fieldset :disabled="locked" class="asr-selector">
                  <legend>转写模型</legend>
                  <label><input v-model="asrProvider" type="radio" value="DASHSCOPE" name="asr-provider" />公网 ASR</label>
                  <label><input v-model="asrProvider" type="radio" value="LOCAL" name="asr-provider" />本地 ASR</label>
                </fieldset>
                <p class="small-muted">{{ asrProvider === 'LOCAL' ? '使用本地模型转写录音' : '使用公网模型转写录音' }}；失败后可切换模型重试。</p>
                <p v-if="activeAsrProvider" class="small-muted">最近提交任务：{{ activeAsrProvider === 'LOCAL' ? '本地 ASR' : '公网 ASR' }}</p>
              </div>
              <div v-if="recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" style="margin-top:13px">
                <button class="btn soft" :disabled="locked || !recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" @click="startTranscription"><Icon name="sparkle" />{{ recordings.some(item => item.status === 'FAILED') ? '重试转写' : '开始转写' }}</button>
              </div>
            </div>
          </section>

          <section class="card">
            <div class="card-head"><h2><Icon name="text" />转写结果 <span v-if="allTranscribed" class="badge teal">已完成</span></h2>
              <div class="record-header-actions"><button class="text-btn" :disabled="!transcriptDraft" aria-label="复制转写文本" @click="copyTranscript"><Icon name="copy" /></button></div>
            </div>
            <template v-if="transcriptDraft">
              <aside v-if="roleReviewCount" class="role-attention" :class="{ 'is-analysis': unclassifiedRoleCount }" aria-live="polite">
                <div class="role-attention-icon"><Icon :name="unclassifiedRoleCount ? 'sparkle' : 'warning'" /></div>
                <div class="role-attention-copy"><div class="role-attention-title"><h3>{{ unclassifiedRoleCount ? '完成角色识别后再生成病历' : '请核对角色判断' }}</h3><span>{{ unclassifiedRoleCount || roleReviewCount }} 条{{ unclassifiedRoleCount ? '待分析' : '待核对' }}</span></div>
                  <p v-if="unclassifiedRoleCount">本次转写的医生、患者角色尚未经过 AI 分析。请先完成分析，再重点核对不确定的句段。</p><p v-else>AI 已完成初步判断，其中部分角色置信度不足或无法确认。请逐句确认后再生成病历。</p>
                </div>
                <div class="role-attention-actions"><span v-if="sourceRouteLabel" class="small-muted">{{ sourceRouteLabel }}</span><div v-if="routeSelectionRequired" class="route-selector" role="group" aria-label="选择 AI 处理路由"><button v-for="route in transcript?.available_routes" :key="route" type="button" class="route-option" :class="{ active: selectedLlmRoute === route }" @click="selectedLlmRoute = route">{{ route === 'DASHSCOPE' ? '公网' : '内网' }}</button></div><button v-if="unclassifiedRoleCount" class="btn primary role-attention-action" :disabled="locked || !turnsMatchTranscript || !routeSelectionReady" @click="reclassifyTranscriptRoles"><Icon name="refresh" />{{ actionBusy === 'reclassify-roles' ? '正在 AI 判断' : '开始 AI 判断' }}</button><button v-else class="btn primary role-attention-action" :disabled="locked" @click="openRoleReview"><Icon name="list" />查看待核对句段</button></div>
              </aside>
           <div class="transcript-tabs">
                <button class="tab" :class="{ active: transcriptTab === 'dialogue' }" @click="transcriptTab='dialogue'">医患对话</button>
                <button class="tab" :class="{ active: transcriptTab === 'edit' }" @click="transcriptTab='edit'">全文编辑</button>
                <button class="tab" :class="{ active: transcriptTab === 'facts' }" @click="transcriptTab='facts'">信息提取</button>
              </div>
              <div v-if="transcriptTab === 'dialogue'" class="transcript-body">
                <div class="transcript-note"><Icon name="info" />真实 ASR 转写结果 · 请核对后采用</div>
                <div v-for="(item, index) in segments" :key="item.turn?.id || index" :ref="element => setRoleReviewElement(item.turn?.id, element)" class="dialogue" :class="{ patient: item.roleCode === 'PATIENT', 'needs-review': item.turn?.role_review_required }">
                  <span class="speaker">{{ item.role === '医生' ? '医' : item.role === '患者' ? '患' : '其' }}</span>
                  <div><div class="dialogue-meta"><span>{{ item.role }}</span><span v-if="roleStatus(item.turn)" class="role-status" :class="{ review: item.turn?.role_review_required, manual: item.turn?.role_source === 'MANUAL' }">{{ roleStatus(item.turn) }}</span><time>{{ item.time }}</time></div>
                    <p><template v-for="(piece, pieceIndex) in highlightText(item.text)" :key="pieceIndex"><mark v-if="piece.mark">{{ piece.text }}</mark><template v-else>{{ piece.text }}</template></template></p>
                    <div v-if="item.turn?.id && turnsMatchTranscript" class="role-control"><label><span>角色</span><select :value="item.turn.role" :disabled="locked || actionBusy === 'role'" @change="updateUtteranceRole(item.turn, $event)"><option value="DOCTOR">医生</option><option value="PATIENT">患者</option><option value="OTHER">其他人</option></select></label><button v-if="item.turn.role_review_required" class="role-confirm" type="button" :disabled="locked || actionBusy === 'role'" @click="confirmCurrentUtteranceRole(item.turn)">确认当前角色</button></div>
                  </div>
                </div>
              </div>
              <div v-else-if="transcriptTab === 'edit'" class="card-body">
                <label for="transcript-edit" class="transcript-note">核对转写文字；修改后需重新生成并确认病历</label>
                <textarea id="transcript-edit" v-model="transcriptDraft" class="transcript-editor" :disabled="locked"></textarea>
              </div>
              <div v-else class="transcript-body extraction-body">
                <div class="transcript-note"><Icon name="shield" />仅展示可追溯的原文事实；未确认内容不能生成病历。</div>
                <div class="extraction-status"><span class="badge" :class="{ teal: extractionConfirmed, amber: !extractionConfirmed }">{{ extractionStatusText }}</span><span v-if="extraction?.version_no" class="small-muted">提取版本 v{{ extraction.version_no }}</span></div>
                <div v-if="extraction?.quality_issues.length" class="extraction-issues"><b>需要处理</b><span v-for="issue in extraction.quality_issues" :key="issue">{{ issue }}</span></div>
                <template v-else-if="extractionFacts.length">
                  <div v-for="item in extractionFacts" :key="item.key" class="source-fact extraction-fact">
                    <div class="fact-head"><b>{{ item.label }}</b><span v-if="item.fact.value && item.fact.confidence != null">置信度 {{ item.fact.confidence }}%</span></div>
                    <p>{{ item.fact.value || '未从当前对话提取到明确事实' }}</p>
                    <small v-for="evidence in item.fact.evidence" :key="`${evidence.turn_index}-${evidence.quote}`">{{ evidence.role === 'DOCTOR' ? '医生' : '患者' }} · {{ formatTime(evidence.start_ms) }} · “{{ evidence.quote }}”</small>
                  </div>
                </template>
                <div v-else class="empty-extraction">当前快照尚未生成结构化提取结果。</div>
                <div v-if="!locked" class="extraction-actions">
                  <span v-if="sourceRouteLabel" class="small-muted">{{ sourceRouteLabel }}</span><div v-if="routeSelectionRequired" class="route-selector" role="group" aria-label="选择 AI 处理路由"><button v-for="route in transcript?.available_routes" :key="route" type="button" class="route-option" :class="{ active: selectedLlmRoute === route }" @click="selectedLlmRoute = route">{{ route === 'DASHSCOPE' ? '公网' : '内网' }}</button></div>
                  <button v-if="extraction?.status !== 'GENERATED' && !extractionConfirmed" class="btn small soft" :disabled="actionBusy === 'extract' || !allTranscribed || !routeSelectionReady" @click="generateClinicalExtraction"><Icon name="sparkle" />{{ actionBusy === 'extract' ? '正在提取' : '生成信息提取' }}</button>
                  <button v-else-if="extraction?.status === 'GENERATED'" class="btn small primary" :disabled="actionBusy === 'confirm-extraction'" @click="confirmClinicalExtraction"><Icon name="check" />{{ actionBusy === 'confirm-extraction' ? '正在确认' : '整体确认提取结果' }}</button>
                </div>
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
              <div v-if="recordings.length" class="asr-controls">
                <fieldset :disabled="locked" class="asr-selector">
                  <legend>转写模型</legend>
                  <label><input v-model="asrProvider" type="radio" value="DASHSCOPE" name="asr-provider" />公网转写</label>
                  <label><input v-model="asrProvider" type="radio" value="LOCAL" name="asr-provider" />本地转写</label>
                </fieldset>
                <p class="small-muted">{{ asrProvider === 'LOCAL' ? '使用本地模型转写录音' : '使用公网模型转写录音' }}；失败后可切换模型重试。</p>
                <p v-if="activeAsrProvider" class="small-muted">最近提交任务：{{ activeAsrProvider === 'LOCAL' ? '本地 ASR' : '公网 ASR' }}</p>
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
          <div class="card-head"><h2><Icon name="text" />转写结果 <span v-if="allTranscribed" class="badge teal">已完成</span></h2><div class="record-header-actions"><button class="text-btn" :disabled="!transcriptDraft" @click="copyTranscript"><Icon name="copy" /></button></div></div>
          <aside v-if="roleReviewCount" class="role-attention" :class="{ 'is-analysis': unclassifiedRoleCount }" aria-live="polite">
            <div class="role-attention-icon"><Icon :name="unclassifiedRoleCount ? 'sparkle' : 'warning'" /></div>
            <div class="role-attention-copy"><div class="role-attention-title"><h3>{{ unclassifiedRoleCount ? '完成角色识别后再生成病历' : '请核对角色判断' }}</h3><span>{{ unclassifiedRoleCount || roleReviewCount }} 条{{ unclassifiedRoleCount ? '待分析' : '待核对' }}</span></div>
              <p v-if="unclassifiedRoleCount">本次转写的医生、患者角色尚未经过 AI 分析。请先完成分析，再重点核对不确定的句段。</p><p v-else>AI 已完成初步判断，其中部分角色置信度不足或无法确认。请逐句确认后再生成病历。</p>
            </div>
            <div class="role-attention-actions"><span v-if="sourceRouteLabel" class="small-muted">{{ sourceRouteLabel }}</span><div v-if="routeSelectionRequired" class="route-selector" role="group" aria-label="选择 AI 处理路由"><button v-for="route in transcript?.available_routes" :key="route" type="button" class="route-option" :class="{ active: selectedLlmRoute === route }" @click="selectedLlmRoute = route">{{ route === 'DASHSCOPE' ? '公网' : '内网' }}</button></div><button v-if="unclassifiedRoleCount" class="btn primary role-attention-action" :disabled="locked || !turnsMatchTranscript || !routeSelectionReady" @click="reclassifyTranscriptRoles"><Icon name="refresh" />{{ actionBusy === 'reclassify-roles' ? '正在 AI 判断' : '开始 AI 判断' }}</button><button v-else class="btn primary role-attention-action" :disabled="locked" @click="openRoleReview"><Icon name="list" />查看待核对句段</button></div>
          </aside>
          <div class="transcript-tabs">
            <button class="tab" :class="{ active: transcriptTab === 'dialogue' }" @click="transcriptTab='dialogue'">医患对话</button>
            <button class="tab" :class="{ active: transcriptTab === 'edit' }" @click="transcriptTab='edit'">全文编辑</button>
            <button class="tab" :class="{ active: transcriptTab === 'facts' }" @click="transcriptTab='facts'">信息提取</button>
          </div>
          <div v-if="transcriptTab === 'dialogue'" class="transcript-body">
            <div class="transcript-note"><Icon name="info" />真实 ASR 转写结果 · 请核对后采用</div>
            <div v-for="(item, index) in segments" :key="item.turn?.id || index" :ref="element => setRoleReviewElement(item.turn?.id, element)" class="dialogue" :class="{ patient: item.roleCode === 'PATIENT', 'needs-review': item.turn?.role_review_required }">
              <span class="speaker">{{ item.role === '医生' ? '医' : item.role === '患者' ? '患' : '其' }}</span>
              <div><div class="dialogue-meta"><span>{{ item.role }}</span><span v-if="roleStatus(item.turn)" class="role-status" :class="{ review: item.turn?.role_review_required, manual: item.turn?.role_source === 'MANUAL' }">{{ roleStatus(item.turn) }}</span><time>{{ item.time }}</time></div>
                <p><template v-for="(piece, pieceIndex) in highlightText(item.text)" :key="pieceIndex"><mark v-if="piece.mark">{{ piece.text }}</mark><template v-else>{{ piece.text }}</template></template></p>
                <div v-if="item.turn?.id && turnsMatchTranscript" class="role-control"><label><span>角色</span><select :value="item.turn.role" :disabled="locked || actionBusy === 'role'" @change="updateUtteranceRole(item.turn, $event)"><option value="DOCTOR">医生</option><option value="PATIENT">患者</option><option value="OTHER">其他人</option></select></label><button v-if="item.turn.role_review_required" class="role-confirm" type="button" :disabled="locked || actionBusy === 'role'" @click="confirmCurrentUtteranceRole(item.turn)">确认当前角色</button></div>
              </div>
            </div>
          </div>
          <div v-else-if="transcriptTab === 'edit'" class="card-body">
            <label for="transcript-edit" class="transcript-note">核对转写文字；修改后需重新生成并确认病历</label>
            <textarea id="transcript-edit" v-model="transcriptDraft" class="transcript-editor" :disabled="locked"></textarea>
          </div>
          <div v-else class="transcript-body extraction-body">
            <div class="transcript-note"><Icon name="shield" />仅展示可追溯的原文事实；未确认内容不能生成病历。</div>
            <div class="extraction-status"><span class="badge" :class="{ teal: extractionConfirmed, amber: !extractionConfirmed }">{{ extractionStatusText }}</span><span v-if="extraction?.version_no" class="small-muted">提取版本 v{{ extraction.version_no }}</span></div>
            <div v-if="extraction?.quality_issues.length" class="extraction-issues"><b>需要处理</b><span v-for="issue in extraction.quality_issues" :key="issue">{{ issue }}</span></div>
            <template v-else-if="extractionFacts.length">
              <div v-for="item in extractionFacts" :key="item.key" class="source-fact extraction-fact"><div class="fact-head"><b>{{ item.label }}</b><span v-if="item.fact.value && item.fact.confidence != null">置信度 {{ item.fact.confidence }}%</span></div><p>{{ item.fact.value || '未从当前对话提取到明确事实' }}</p><small v-for="evidence in item.fact.evidence" :key="`${evidence.turn_index}-${evidence.quote}`">{{ evidence.role === 'DOCTOR' ? '医生' : '患者' }} · {{ formatTime(evidence.start_ms) }} · “{{ evidence.quote }}”</small></div>
            </template>
            <div v-else class="empty-extraction">当前快照尚未生成结构化提取结果。</div>
            <div v-if="!locked" class="extraction-actions"><span v-if="sourceRouteLabel" class="small-muted">{{ sourceRouteLabel }}</span><div v-if="routeSelectionRequired" class="route-selector" role="group" aria-label="选择 AI 处理路由"><button v-for="route in transcript?.available_routes" :key="route" type="button" class="route-option" :class="{ active: selectedLlmRoute === route }" @click="selectedLlmRoute = route">{{ route === 'DASHSCOPE' ? '公网' : '内网' }}</button></div><button v-if="extraction?.status !== 'GENERATED' && !extractionConfirmed" class="btn small soft" :disabled="actionBusy === 'extract' || !allTranscribed || !routeSelectionReady" @click="generateClinicalExtraction"><Icon name="sparkle" />{{ actionBusy === 'extract' ? '正在提取' : '生成信息提取' }}</button><button v-else-if="extraction?.status === 'GENERATED'" class="btn small primary" :disabled="actionBusy === 'confirm-extraction'" @click="confirmClinicalExtraction"><Icon name="check" />{{ actionBusy === 'confirm-extraction' ? '正在确认' : '整体确认提取结果' }}</button></div>
          </div>
          <div class="transcript-actions"><span class="small-muted">{{ transcriptDraft.length }} 字 · {{ recordings.filter(item => item.status === 'DONE').length }} 段录音</span>
            <button v-if="transcriptTab === 'edit'" class="btn small soft" :disabled="locked" @click="saveTranscript"><Icon name="save" />保存转写</button>
            <button v-else class="btn small soft" :disabled="locked || !allTranscribed" @click="generateRecord"><Icon name="sparkle" />生成病历</button>
          </div>
        </section>
      </div>

      <div v-else-if="view === 'record'" class="full-view">
        <section class="card record-card">
          <div class="card-head"><h2><Icon name="file" />门诊病历 <span v-if="record?.record_id" class="badge" :class="confirmed ? 'teal' : 'amber'">{{ confirmed ? '已签署' : '待签署' }}</span></h2>
            <div class="record-header-actions"><span v-if="record?.record_id" class="record-version">v{{ record.version_no }}.0</span>
              <button v-if="record?.record_id && !confirmed && !closed" class="btn small" :disabled="busy || !allTranscribed" @click="modal='regenerate'"><Icon name="refresh" />重新生成</button>
            </div>
          </div>
          <div class="record-subbar"><span class="template-label"><Icon name="list" />标准门诊病历模板</span><span>{{ doctor.department_name }} · v0.1</span></div>
          <div v-if="!record?.record_id" class="empty-state"><div class="empty-icon"><Icon name="file" /></div><h3>让对话成为清晰的病历</h3><p>完成全部录音转写后，按标准模板生成一份病历草稿。</p><button class="btn primary" :disabled="!allTranscribed || busy || closed" @click="generateRecord"><Icon name="sparkle" />生成病历草稿</button></div>
          <template v-else>
            <div class="record-signing-status" :class="{ signed: confirmed }">
              <Icon :name="confirmed ? 'shield' : 'check'" />
              <div>
                <strong>{{ confirmed ? `已签署 v${record?.version_no}.0` : `待医生签署 · v${record?.version_no}.0` }}</strong>
                <span>{{ confirmed ? `${record?.confirmed_by_name || doctor.display_name}医生 · ${formatDateTime(record?.confirmed_at)}` : missingFields.length ? `待完善：${missingFields.join('、')}` : '请核对内容后确认并签署当前版本。' }}</span>
              </div>
            </div>
          <form class="record-form" autocomplete="off" @submit.prevent="saveDraft">
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
            <p class="auto-note"><Icon name="info" />非必填项未提及则留空。签署后将锁定当前病历版本，后续修改会创建新修订版。</p>
          </form>
          </template>
        </section>
      </div>

      <div v-else-if="view === 'export'" class="full-view">
        <div class="info-banner"><Icon :name="confirmed ? 'shield' : 'lock'" />
          <span>{{ confirmed ? `当前可导出版本：v${record?.version_no}.0 · 签署医生：${record?.confirmed_by_name || doctor.display_name} · 签署时间：${formatDateTime(record?.confirmed_at)}` : '请先完成医生签署，签署后的版本才可以导出。' }}</span>
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

      <div v-else-if="view === 'audit'" class="full-view audit-view">
        <section class="card audit-filter-card">
          <div class="card-head"><h2><Icon name="shield" />筛选日志</h2><span class="small-muted">默认显示近 30 天记录</span></div>
          <div class="audit-filters">
            <label>开始日期<input v-model="auditFrom" type="date" :max="auditTo || undefined"></label>
            <label>结束日期<input v-model="auditTo" type="date" :min="auditFrom || undefined"></label>
            <label>操作医生<select v-model="auditDoctorId"><option value="">全部医生</option><option v-for="operator in auditOperators" :key="operator.id" :value="operator.id">{{ operator.display_name }}</option></select></label>
            <label>操作类型<select v-model="auditAction"><option value="">全部类型</option><option value="LOGIN">登录系统</option><option value="RECORDING_UPLOADED">上传录音</option><option value="MEDICAL_RECORD_CONFIRMED">确认病历</option><option value="MEDICAL_RECORD_EXPORT">病历导出</option></select></label>
            <button class="btn primary audit-search" :disabled="auditBusy" @click="loadAudit(1)"><Icon name="search" />查询</button>
          </div>
        </section>
        <section class="card audit-table-card">
          <div class="card-head"><h2><Icon name="list" />操作记录</h2><span class="small-muted">共 {{ auditTotal }} 条</span></div>
          <div class="table-wrap"><table class="log-table audit-log-table"><thead><tr><th>时间</th><th>操作人</th><th>操作类型</th><th>患者 / 接诊</th><th>操作详情</th><th>结果</th><th>IP</th></tr></thead><tbody>
            <tr v-if="auditBusy"><td colspan="7">正在加载日志…</td></tr>
            <tr v-else-if="!auditLogs.length"><td colspan="7">暂无符合条件的日志记录</td></tr>
            <tr v-for="item in auditLogs" :key="item.id"><td>{{ formatDateTime(item.created_at) }}</td><td>{{ item.operator_name || '系统' }}</td><td>{{ auditActionLabel(item.action) }}</td><td><template v-if="item.visit_no || item.patient_name">{{ item.patient_name || '未记录患者' }}<br><span class="small-muted">{{ item.visit_no || '—' }}</span></template><template v-else>—</template></td><td>{{ item.detail || '—' }}</td><td><span class="badge" :class="item.result === 'SUCCESS' ? 'teal' : 'red'">{{ auditResultLabel(item.result) }}</span></td><td>{{ item.client_ip || '—' }}</td></tr>
          </tbody></table></div>
          <div class="audit-pagination"><span>第 {{ auditPage }} 页</span><div><button class="btn small" :disabled="auditBusy || auditPage <= 1" @click="loadAudit(auditPage - 1)">上一页</button><button class="btn small" :disabled="auditBusy || auditPage * 20 >= auditTotal" @click="loadAudit(auditPage + 1)">下一页</button></div></div>
        </section>
      </div>
    </main>

    <footer v-if="view !== 'audit'" class="bottom-bar">
      <div class="bottom-status">
        <span class="status-emblem"><Icon :name="confirmed ? 'shield' : 'save'" /></span>
        <div>
          <div>{{ closed ? `本次接诊${statusText(currentVisit)}` : confirmed ? `病历 v${record?.version_no}.0 已由${record?.confirmed_by_name || doctor.display_name}医生签署` : record?.record_id ? '病历草稿已就绪，请审核并签署' : waiting ? '患者待接诊' : '正在准备本次接诊的病历' }}</div>
          <small>{{ confirmed ? `签署时间：${formatDateTime(record?.confirmed_at)}` : '系统仅整理问诊内容，不生成自动诊断或自动医嘱。' }}</small>
        </div>
      </div>
      <div class="bottom-actions">
        <button v-if="record?.record_id && !closed && !confirmed" class="btn" :disabled="busy || !!actionBusy" @click="saveDraft"><Icon name="save" />保存草稿</button>
        <button v-if="record?.record_id && !closed && confirmed" class="btn" :disabled="busy || !!actionBusy" @click="editRecord"><Icon name="edit" />创建修订版</button>
        <button v-else-if="record?.record_id && !closed" class="btn primary" :disabled="busy || !!actionBusy" @click="requestConfirm"><Icon name="shield" />确认并签署 v{{ record.version_no }}.0</button>
        <button v-if="confirmed" class="btn primary" :disabled="busy" @click="exportFromBottom"><Icon name="download" />前往导出</button>
      </div>
    </footer>

    <div v-if="modal" class="modal-backdrop" role="presentation" @click.self="modal=null" @keydown.esc="modal=null">
      <div ref="modalDialog" class="modal-dialog" role="dialog" aria-modal="true" :aria-labelledby="`modal-title-${modal}`" tabindex="-1" @keydown.esc.stop="modal=null">
        <div class="modal-head">
          <h2 :id="`modal-title-${modal}`">{{ ({ 'new-patient':'新增患者','cancel':'取消本次接诊','finish':'结束本次接诊','regenerate':'重新生成当前病历','confirm':'确认并签署病历','help':'使用帮助','activity':'当前接诊动态' })[modal] }}</h2>
          <button class="icon-btn" aria-label="关闭对话框" @click="modal=null"><Icon name="x" /></button>
        </div>
        <div class="modal-body">
          <template v-if="modal === 'new-patient'">
            <div class="manual-patient-grid">
              <div class="modal-field"><label for="manual-patient-name">患者姓名 <span class="required">*</span></label><input id="manual-patient-name" v-model="newPatientForm.name" maxlength="128" placeholder="请输入真实姓名" /></div>
              <div class="modal-field"><label for="manual-patient-gender">性别 <span class="required">*</span></label><select id="manual-patient-gender" v-model="newPatientForm.gender"><option value="">请选择</option><option value="男">男</option><option value="女">女</option><option value="其他">其他</option><option value="未知">未知</option></select></div>
              <div class="modal-field"><label for="manual-patient-age">年龄</label><input id="manual-patient-age" v-model.number="newPatientForm.age" type="number" min="0" max="150" step="1" placeholder="选填" /></div>
              <div class="modal-field" :class="{ invalid: newPatientErrors.phone }">
                <label for="manual-patient-phone">联系方式</label>
                <input id="manual-patient-phone" v-model="newPatientForm.phone" type="tel" inputmode="numeric" maxlength="11"
                  placeholder="选填，保存后脱敏显示" @input="newPatientForm.phone = newPatientForm.phone.replace(/\D/g, '').slice(0, 11); clearNewPatientError('phone')" @blur="validateNewPatientField('phone')" />
                <p v-if="newPatientErrors.phone" class="field-error">{{ newPatientErrors.phone }}</p>
              </div>
              <div class="modal-field manual-patient-wide" :class="{ invalid: newPatientErrors.idNo }">
                <label for="manual-patient-id">证件号</label>
                <input id="manual-patient-id" v-model="newPatientForm.idNo" type="text" maxlength="18"
                  placeholder="选填，保存后脱敏显示" @input="newPatientForm.idNo = newPatientForm.idNo.toUpperCase(); clearNewPatientError('idNo')" @blur="validateNewPatientField('idNo')" />
                <p v-if="newPatientErrors.idNo" class="field-error">{{ newPatientErrors.idNo }}</p>
              </div>
            </div>
            <div class="modal-note">仅新增患者资料，不会创建或开始接诊。患者会保存到本地患者库并自动生成患者编号；联系方式和证件号在列表中以脱敏形式展示。</div>
            <div v-if="modalError" class="modal-error">{{ modalError }}</div>
          </template>
          <template v-else-if="modal === 'cancel'"><p>确定取消 <b>{{ currentPatient?.name }} · 接诊 {{ currentVisit?.visit_no }}</b> 的本次接诊？</p><p>取消后将清空本次接诊的录音、转写、病历和导出文件；该患者之后可以重新开始接诊。</p><div v-if="modalError" class="modal-error">{{ modalError }}</div></template>
          <template v-else-if="modal === 'finish'"><p>{{ currentPatient?.name }} 的病历 <b>v{{ record?.version_no }}.0</b> 已签署并导出。</p><p>结束后本次接诊将归档。</p></template>
          <template v-else-if="modal === 'regenerate'"><p>将使用当前已采用的转写文本，覆盖 <b>接诊 {{ currentVisit?.visit_no }}</b> 的当前草稿内容，并创建新版本。</p><p>医生手动编辑的内容也会被替换，请确认已保存所需内容。</p></template>
          <template v-else-if="modal === 'confirm'">
            <p>患者 <b>{{ recordForm?.name }}</b> · 接诊 <b>{{ currentVisit?.visit_no }}</b> · 病历 <b>v{{ record?.version_no }}.0</b></p>
            <p>签署后将记录签署医生、时间及版本，并锁定当前病历；后续修改会创建新修订版并需重新签署。</p>
            <label class="confirm-check"><input v-model="confirmChecked" type="checkbox" /><span>我已核对病历内容，确认当前记录准确反映本次接诊情况。</span></label>
            <div class="modal-error">{{ modalError }}</div>
          </template>
          <template v-else-if="modal === 'help'"><p><b>完整流程</b><br>开始接诊 → 上传录音 → 开始转写 → 生成病历 → 审核并签署病历 → 后端生成并下载 Word / PDF → 结束接诊。</p><p>非必填字段未提及则留空；诊疗记录仅供医生补充。</p></template>
          <template v-else><p v-if="!activity.length">接诊开始后将在这里记录业务操作。</p><p v-for="item in activity.slice(0, 12)" :key="item.time"><span class="small-muted">{{ item.time }}</span><br>{{ item.text }}</p></template>
        </div>
        <div class="modal-actions">
          <template v-if="modal === 'new-patient'"><button class="btn" @click="modal=null">取消</button><button class="btn primary" :disabled="busy" @click="createNewPatient"><Icon name="save" />保存患者</button></template>
          <template v-else-if="modal === 'cancel'"><button class="btn" @click="modal=null">返回</button><button class="btn danger" :disabled="busy" @click="doCancel">确认取消接诊</button></template>
          <template v-else-if="modal === 'finish'"><button class="btn" @click="modal=null">返回</button><button class="btn primary" :disabled="busy" @click="doFinish"><Icon name="check" />完成本次接诊</button></template>
          <template v-else-if="modal === 'regenerate'"><button class="btn" @click="modal=null">返回</button><button class="btn primary" :disabled="busy" @click="modal=null;generateRecord()"><Icon name="refresh" />重新生成</button></template>
          <template v-else-if="modal === 'confirm'"><button class="btn" @click="modal=null">返回核对</button><button class="btn primary" :disabled="busy || actionBusy === 'confirm'" @click="doConfirm"><Icon name="shield" />确认签署</button></template>
          <template v-else><button class="btn primary" @click="modal=null"><Icon name="check" />开始使用</button></template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import '../routeSelection.css'
import Icon from './Icon.vue'
import TemplateManagement from './TemplateManagement.vue'
import RecordingAudioPlayer from './RecordingAudioPlayer.vue'
import AsrPreviewCard from './AsrPreviewCard.vue'
import ClinicalSummaryCard from './ClinicalSummaryCard.vue'
import { formatDateTime } from '../dateTime'
import { roleLabel } from '../asrRoles'
import { showMessage, type MessageType } from '../message'
import type { AiRouteSettings, AsrJob, AsrPreviewRecording, AsrProvider, AuditAction, AuditLog, AuditOperator, ClinicalExtraction, Confirmation, Doctor, ExportTemplate, LlmProvider, MedicalRecord, MedicalRecordContent, Patient, RecordExport, Recording, Transcript, Utterance, Visit } from '../types'

const props = defineProps<{ doctor: Doctor }>()
const emit = defineEmits<{ (event: 'logout'): void }>()
const exportTemplates = ref<ExportTemplate[]>([])
const selectedTemplateRevisionId = ref('')
const selectedTemplate = computed(() => exportTemplates.value.find(template => template.current_revision_id === selectedTemplateRevisionId.value)
  || exportTemplates.value.find(template => template.default_template) || exportTemplates.value[0] || null)
const previewOpen = ref(false)
const previewUrl = ref('')
const previewBusy = ref(false)
const previewFormat = ref<'PDF' | 'DOCX'>('PDF')

type MainView = 'workbench' | 'audio' | 'transcript' | 'record' | 'export' | 'audit' | 'templates' | 'settings'
type WorkflowView = 'workbench' | 'audio' | 'transcript' | 'record' | 'export'
type ModalKind = 'new-patient' | 'cancel' | 'finish' | 'regenerate' | 'confirm' | 'supplemental-transcription' | 'retranscribe-recording' | 'help' | 'activity' | 'delete-recording' | 'delete-patient' | null
type EditableUtteranceRole = 'DOCTOR' | 'PATIENT' | 'OTHER'
type ManualPatientForm = { name: string; gender: string; age: number | '' | null; phone: string; idNo: string }
type ManualPatientField = keyof ManualPatientForm

const PATIENT_PHONE_PATTERN = /^1[3-9]\d{9}$/
const PATIENT_ID_PATTERN = /^\d{17}[\dX]$/
const PATIENT_ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
const PATIENT_ID_CHECK_CODES = '10X98765432'
const MAX_RECORDING_BYTES = 200 * 1024 * 1024
const RECORDING_EXTENSION_PATTERN = /\.(wav|mp3|m4a|webm)$/i
const AVATAR_TONES = ['', 'lilac', 'blue', 'rose', 'amber', 'cyan']

const patients = ref<Patient[]>([])
const visits = ref<Visit[]>([])
const selectedPatientId = ref('')
const selectedVisitId = ref('')
const view = ref<MainView>('workbench')
const aiSettings = ref<AiRouteSettings | null>(null)
const aiSettingsBusy = ref(false)
const aiSettingsSaving = ref(false)
const aiSettingsDraft = ref<LlmProvider>('DASHSCOPE')
const queueSearch = ref('')
const patientSearchResults = ref<Patient[] | null>(null)
const searchBusy = ref(false)
const queueDoctorFilter = ref('')
const queueStatusFilter = ref<'OPEN' | 'ALL' | 'WAITING' | 'ACTIVE' | 'COMPLETED'>('OPEN')
const collapsedDoctorGroups = ref(new Set<string>())
const queueCardsElement = ref<HTMLElement | null>(null)
const queueHasMore = ref(false)
const queueHiddenCount = ref(0)
const queueScrollAcknowledged = ref(false)
const busy = ref(false)
const actionBusy = ref<'' | 'upload' | 'delete' | 'delete-patient' | 'transcribe' | 'generate' | 'extract' | 'confirm-extraction' | 'save' | 'role' | 'reclassify-roles' | 'confirm'>('')
const asrProvider = ref<AsrProvider>('DASHSCOPE')
// 角色识别 LLM 路由：提交转写/重新转写时作为 role_provider 下发；null 表示跟随后端默认配置。
const roleProvider = ref<LlmProvider | null>(null)
const activeAsrProvider = ref<AsrProvider | null>(null)
const recordings = ref<Recording[]>([])
// 接诊级批量转写入口只显示一次，放在最后一段待处理录音的卡片内。
const transcriptionActionRecordingId = computed(() => recordings.value
  .filter(item => item.status === 'UPLOADED' || item.status === 'FAILED').at(-1)?.id)
const asrPreviews = ref<AsrPreviewRecording[]>([])
const asrPreviewError = ref('')
const recordingToDelete = ref<Recording | null>(null)
const recordingToRetranscribe = ref<Recording | null>(null)
const transcript = ref<Transcript | null>(null)
const extraction = ref<ClinicalExtraction | null>(null)
const extractionRequestBusy = ref(false)
const extractionError = ref('')
let extractionPollTimer: number | undefined
let extractionPollRevision = 0
let extractionRequestRevision = 0
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
const modal = ref<ModalKind>(null)
const modalError = ref('')
const confirmChecked = ref(false)
const deletePatientChecked = ref(false)
const pendingPatientDeletionId = ref('')
let patientDeletionPollTimer: number | undefined
let patientDeletionPollBusy = false
const newPatientForm = ref<ManualPatientForm>({ name: '', gender: '', age: null, phone: '', idNo: '' })
const newPatientErrors = ref<Partial<Record<ManualPatientField, string>>>({})
const dragging = ref(false)
const playingId = ref('')
const audioLoadingId = ref('')
const audioIsPlaying = ref(false)
const audioEnded = ref(false)
const audioCurrentTime = ref(0)
const audioMediaDuration = ref(0)
const audioVolume = ref(1)
const audioPlaybackRate = ref(1)
const audioPlaybackError = ref('')
const audioAutoplayBlocked = ref(false)
const audioPlaybackUrl = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const audioElement = ref<HTMLAudioElement | null>(null)
const modalDialog = ref<HTMLElement | null>(null)
const activity = ref<{ time: string; text: string }[]>([])
const recordingElapsedMs = ref(0)
const roleReviewElements = new Map<string, HTMLElement>()
const transcriptBody = ref<HTMLElement | null>(null)
const recordingLevel = ref(0)
const recordingSignalDetected = ref(false)
const recordingSignalAvailable = ref(true)
const audioInputDevices = ref<MediaDeviceInfo[]>([])
const selectedAudioInputDeviceId = ref('')
const recordingInputLabel = ref('')
const audioInputHint = ref('')
const audioInputRefreshBusy = ref(false)
const avatarToneByPatientId = new Map<string, string>()
let availableAvatarTones: string[] = []
let lastAvatarTone = ''
let recordingTimer: number | undefined
let recordingStartedAt = 0
let audioPlayer: HTMLAudioElement | null = null
let recorder: MediaRecorder | null = null
let recorderStream: MediaStream | null = null
let microphoneAudioContext: AudioContext | null = null
let microphoneAnalyser: AnalyserNode | null = null
let microphoneMeterFrame = 0
let recordingPeakRms = 0
let recordChunks: Blob[] = []
let audioObjectUrl: string | null = null
let audioLoadAbort: AbortController | null = null
let visitStateRevision = 0
let asrPreviewPollTimer: number | undefined
let asrPreviewPollBusy = false
let asrPreviewPollVisitId = ''
let asrPreviewPollRevision = 0
let asrPreviewPollCycle = 0
let activeAsrJob: AsrJob | null = null
let patientSearchRevision = 0
let queueResizeObserver: ResizeObserver | undefined
let audioDeviceChangeTarget: MediaDevices | null = null
const recordingState = ref<'idle' | 'recording' | 'paused'>('idle')
const recordingSignalLabel = computed(() => {
  if (recordingState.value === 'recording' || recordingState.value === 'paused') {
    if (!recordingSignalAvailable.value) return '浏览器未提供麦克风电平检测；录音仍在进行。'
    return recordingSignalDetected.value
      ? '已检测到麦克风声音'
      : '尚未检测到声音，请检查麦克风是否静音或选错输入设备。'
  }
  if (recordingElapsedMs.value && recordingSignalAvailable.value && !recordingSignalDetected.value) {
    return '录音文件已生成，但未检测到麦克风声音；请检查输入设备后试听。'
  }
  return recordingElapsedMs.value ? '录音数据已生成，可以试听。' : '点击开始录音，浏览器会请求麦克风权限'
})

function recordingWaveHeight(index: number) {
  return `${Math.max(4, Math.round(recordingLevel.value * (8 + (index * 17) % 28)))}px`
}

function setAudioElement(recordingId: string, element: HTMLAudioElement | null) {
  if (recordingId !== playingId.value) return
  audioElement.value = element
}


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

/**
 * 后端下发的默认路由优先；没有默认值时，只有一个可用路由就用它，否则返回 null（跟随后端默认）。
 */
function preferredLlmRoute(route: LlmProvider | undefined | null, available: LlmProvider[] | undefined): LlmProvider | null {
  return route ?? (available?.length === 1 ? available[0] : null)
}

watch(() => transcript.value?.snapshot_id, () => {
  roleProvider.value = currentAiRoute.value
})

watch(view, (next, previous) => {
  if (next !== previous) stopAudio()
  if (next === 'audio') void refreshAudioInputDevices()
})

function updateQueueScrollState() {
  const element = queueCardsElement.value
  if (!element) {
    queueHasMore.value = false
    queueHiddenCount.value = 0
    return
  }
  const hasMore = element.scrollHeight - element.clientHeight > 2
  const bounds = element.getBoundingClientRect()
  const groups = Array.from(element.querySelectorAll<HTMLElement>('.doctor-queue-group'))
  const nestedLists = groups.flatMap(group => Array.from(group.querySelectorAll<HTMLElement>('.doctor-group-items')))
  const nestedOverflow = nestedLists.some(list => list.scrollHeight - list.clientHeight > 2)
  const hiddenGroups = new Set(groups.filter(group => {
    const header = group.querySelector<HTMLElement>('.doctor-group-head')
    return !!header && header.getBoundingClientRect().bottom > bounds.bottom + 1
  }))
  const hiddenCount = hasMore ? groups.reduce((count, group) => hiddenGroups.has(group)
    ? count + Number(group.dataset.visitCount || 0) : count, 0)
    + Array.from(element.querySelectorAll<HTMLElement>('.patient-tile')).filter(tile =>
      !hiddenGroups.has(tile.closest<HTMLElement>('.doctor-queue-group') as HTMLElement)
      && tile.getBoundingClientRect().bottom > bounds.bottom + 1).length : 0
  const nestedHiddenCount = nestedLists.reduce((count, list) => {
    if (list.scrollHeight - list.clientHeight <= 2) return count
    return count + Array.from(list.querySelectorAll<HTMLElement>('.patient-tile'))
      .filter(tile => tile.getBoundingClientRect().bottom > list.getBoundingClientRect().bottom + 1).length
  }, 0)
  queueHiddenCount.value = hiddenCount + nestedHiddenCount
  queueHasMore.value = (hasMore && hiddenCount > 0) || (nestedOverflow && nestedHiddenCount > 0)
}

function onQueueScroll() {
  updateQueueScrollState()
  queueScrollAcknowledged.value = true
}

function scrollQueueFurther() {
  const element = queueCardsElement.value
  if (!element) return
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const visibleNestedList = Array.from(element.querySelectorAll<HTMLElement>('.doctor-group-items'))
    .find(list => list.scrollHeight - list.clientHeight > 2
      && list.getBoundingClientRect().bottom > element.getBoundingClientRect().top
      && list.getBoundingClientRect().top < element.getBoundingClientRect().bottom)
  if (visibleNestedList) {
    visibleNestedList.scrollBy({ top: Math.max(150, visibleNestedList.clientHeight * 0.72), behavior: reduceMotion ? 'auto' : 'smooth' })
    return
  }
  element.scrollBy({ top: Math.max(180, element.clientHeight * 0.72), behavior: reduceMotion ? 'auto' : 'smooth' })
}

watch(queueCardsElement, async element => {
  queueResizeObserver?.disconnect()
  queueResizeObserver = undefined
  if (!element) return
  if (typeof ResizeObserver !== 'undefined') {
    queueResizeObserver = new ResizeObserver(updateQueueScrollState)
    queueResizeObserver.observe(element)
  }
  await nextTick()
  updateQueueScrollState()
})
watch(() => [patients.value, visits.value, patientSearchResults.value, view.value, queueDoctorFilter.value, queueStatusFilter.value, collapsedDoctorGroups.value], async () => {
  queueScrollAcknowledged.value = false
  await nextTick()
  updateQueueScrollState()
}, { flush: 'post' })

const titles: Record<MainView, [string, string]> = {
  workbench: ['接诊工作台', '从医患对话到结构化病历，让每一次接诊更从容。'],
  audio: ['问诊过程/音频', '完整上传问诊录音，支持上传和实时录音。'],
  transcript: ['转写结果', '回看医患对话，核对并编辑本次接诊采用的转写文本。'],
  record: ['病历审核与签署', '核对并完善病历草稿，确认后锁定当前版本。'],
  export: ['病历导出', '将已确认的病历由后端生成 Word 或 PDF 文件。'],
  audit: ['日志审计', '查看全系统医生的关键操作记录。'],
  templates: ['模板管理', '维护全院通用病历导出模板及其版本。'],
  settings: ['AI 服务设置', '统一管理转写、角色识别和临床信息提取的处理环境。']
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

const departmentHead = computed(() => props.doctor.role === 'DEPARTMENT_HEAD')

function patientStatus(patientId: string) {
  return patientVisit(patientId)?.status || 'UNSCHEDULED'
}

function statusClass(visit: Visit | null) {
  const status = visit?.status || 'WAITING'
  return ({ WAITING: 'waiting', ACTIVE: 'active', COMPLETED: 'completed', CANCELLED: 'cancelled', ARCHIVED: 'archived' } as Record<string, string>)[status] || 'waiting'
}

function visitActivityTime(visit: Visit) {
  return String(visit.last_activity_at || visit.created_at || '')
}

function visitStatusPriority(status: string) {
  return status === 'ACTIVE' ? 0 : status === 'WAITING' ? 1 : status === 'COMPLETED' ? 2 : 3
}

function toggleDoctorGroup(doctorId: string) {
  const next = new Set(collapsedDoctorGroups.value)
  if (next.has(doctorId)) next.delete(doctorId)
  else next.add(doctorId)
  collapsedDoctorGroups.value = next
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
    VISIT_DETAIL_VIEWED: '查看接诊详情',
    RECORDING_UPLOADED: '上传录音',
    RECORDING_DELETED: '删除录音',
    MEDICAL_RECORD_CONFIRMED: '确认病历',
    MEDICAL_RECORD_EXPORT: '病历导出',
    PATIENT_DELETED: '删除患者'
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

const currentPatientVisitCount = computed(() => visits.value.filter(visit => visit.patient_id === selectedPatientId.value).length)
const patientDeletionPending = computed(() => !!pendingPatientDeletionId.value)

const currentPatient = computed(() => patients.value.find(p => p.id === selectedPatientId.value) || null)
const canViewAudit = computed(() => ['DEPARTMENT_HEAD', 'ADMIN'].includes(props.doctor.role))
const canManageTemplates = computed(() => props.doctor.role === 'DEPARTMENT_HEAD')
const canManageAiSettings = computed(() => props.doctor.role === 'DEPARTMENT_HEAD')
const showLegacyWorkspace = false
const readOnlyCurrentPatient = computed(() => {
  return departmentHead.value && !!currentVisit.value
})
const canCreateVisit = (patientId: string) => !patientVisit(patientId)
const currentVisit = computed(() => {
  if (departmentHead.value && selectedVisitId.value) {
    return visits.value.find(visit => visit.id === selectedVisitId.value) || null
  }
  return patientVisit(selectedPatientId.value)
})
const historicalAiRoute = computed<LlmProvider | null>(() => {
  const source = transcript.value?.source_route
  return source === 'LOCAL' || source === 'DASHSCOPE' ? source : null
})
const historicalMixedAiRoute = computed(() => transcript.value?.source_route === 'MIXED')
const currentAiRoute = computed<LlmProvider>(() => currentVisit.value?.ai_route || historicalAiRoute.value || aiSettings.value?.route || 'DASHSCOPE')
const currentAiRouteLabel = computed(() => historicalMixedAiRoute.value ? '历史混合' : currentAiRoute.value === 'LOCAL' ? '本地' : '公网')
const globalAiRoute = computed<LlmProvider>(() => aiSettings.value?.route || 'DASHSCOPE')
const globalAiRouteLabel = computed(() => globalAiRoute.value === 'LOCAL' ? '本地' : '公网')
const globalAiRouteWarning = computed(() => (aiSettings.value?.unavailable_capabilities?.length || 0) > 0)
const currentAiRouteDescription = computed(() => {
  if (currentVisit.value?.ai_route) return `本次接诊已固定使用：${currentAiRouteLabel.value} AI`
  if (historicalMixedAiRoute.value) return '历史接诊包含混合 AI 路由（仅兼容查看）'
  if (historicalAiRoute.value) return `历史接诊实际使用：${currentAiRouteLabel.value} AI`
  return `当前环境：${currentAiRouteLabel.value} AI（首次转写时固化）`
})
const queuePatients = computed(() => patientSearchResults.value ?? patients.value)
const queueVisitRows = computed(() => {
  const allowedPatients = new Set((patientSearchResults.value ?? patients.value).map(patient => patient.id))
  const patientById = new Map(patients.value.map(patient => [patient.id, patient]))
  return visits.value
    .filter(visit => visit.status !== 'CANCELLED' && allowedPatients.has(visit.patient_id))
    .map(visit => ({ visit, patient: patientById.get(visit.patient_id) }))
    .filter((item): item is { visit: Visit; patient: Patient } => !!item.patient)
    .sort((a, b) => visitStatusPriority(a.visit.status) - visitStatusPriority(b.visit.status)
      || visitActivityTime(b.visit).localeCompare(visitActivityTime(a.visit)))
})
const queueDoctorOptions = computed(() => {
  const doctors = new Map<string, string>()
  for (const { visit } of queueVisitRows.value) {
    doctors.set(visit.doctor_id, visit.doctor_name || '未标记医生')
  }
  return [...doctors.entries()].sort((a, b) => a[1].localeCompare(b[1], 'zh-CN'))
})
const selectedQueueVisitRows = computed(() => queueVisitRows.value.filter(({ visit }) =>
  !queueDoctorFilter.value || visit.doctor_id === queueDoctorFilter.value))
const filteredQueueVisitRows = computed(() => selectedQueueVisitRows.value.filter(({ visit }) => {
  if (queueStatusFilter.value === 'OPEN') return visit.status === 'ACTIVE' || visit.status === 'WAITING'
  if (queueStatusFilter.value === 'ALL') return true
  return visit.status === queueStatusFilter.value
}))
const queueDoctorGroups = computed(() => {
  const groups = new Map<string, { id: string; name: string; rows: typeof filteredQueueVisitRows.value; active: number; waiting: number; completed: number; latest: string }>()
  for (const row of filteredQueueVisitRows.value) {
    const id = row.visit.doctor_id || 'unknown'
    let group = groups.get(id)
    if (!group) {
      group = { id, name: row.visit.doctor_name || '未标记医生', rows: [], active: 0, waiting: 0, completed: 0, latest: '' }
      groups.set(id, group)
    }
    group.rows.push(row)
    if (row.visit.status === 'ACTIVE') group.active += 1
    if (row.visit.status === 'WAITING') group.waiting += 1
    if (row.visit.status === 'COMPLETED') group.completed += 1
    group.latest = group.latest > visitActivityTime(row.visit) ? group.latest : visitActivityTime(row.visit)
  }
  return [...groups.values()].map(group => ({
    ...group,
    rows: group.rows.sort((a, b) => visitStatusPriority(a.visit.status) - visitStatusPriority(b.visit.status)
      || visitActivityTime(b.visit).localeCompare(visitActivityTime(a.visit)))
  })).sort((a, b) => (b.active + b.waiting) - (a.active + a.waiting) || b.latest.localeCompare(a.latest)
    || a.name.localeCompare(b.name, 'zh-CN'))
})
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
const hasAsrPreviewContent = computed(() => asrPreviews.value.some(preview => Array.isArray(preview.segments) && preview.segments.length > 0))
const confirmed = computed(() => !!record.value?.confirmed)
const exported = computed(() => !!record.value && exports.value.some(item =>
  item.version_no === record.value?.version_no && item.status === 'SUCCEEDED'))
const sourceDirty = computed(() => !!record.value?.source_dirty)
const locked = computed(() => readOnlyCurrentPatient.value || closed.value || confirmed.value || !!actionBusy.value)
const llmRouting = computed(() => extraction.value || transcript.value)
// 角色识别路由由后端下发的 transcript.available_routes / route_selection_required 决定。
const sourceRouteLabel = computed(() => {
  // 只描述 ASR 转写来源；角色识别 / 信息提取的实际路由由各自的选择器表达。
  const route = llmRouting.value?.source_route
  return route === 'DASHSCOPE' ? '公网转写来源'
    : route === 'LOCAL' ? '内网转写来源'
    : route === 'MIXED' ? '混合转写来源'
    : ''
})
const workflowView = computed<WorkflowView>(() => {
  if (waiting.value) return 'workbench'
  if (!recordings.value.length || !allTranscribed.value) return 'audio'
  if (sourceDirty.value) return 'transcript'
  if (!record.value?.record_id) return 'transcript'
  if (!confirmed.value) return 'record'
  return 'export'
})
const canReturnToAudio = computed(() => active.value && !locked.value
  && view.value === 'transcript' && workflowView.value === 'transcript')
const needsSupplementalTranscriptionConfirmation = computed(() => {
  const hasPendingRecording = recordings.value.some(item => ['UPLOADED', 'FAILED'].includes(item.status))
  const hasUnsavedTranscriptEdit = !!transcript.value
    && transcriptDraft.value !== transcript.value.transcript
  return !!transcript.value?.snapshot_id && hasPendingRecording
    && (!!transcript.value.edited || hasUnsavedTranscriptEdit || !!record.value?.record_id)
})
const derivedDataInvalidated = computed(() => !!transcript.value?.source_dirty
  || !!record.value?.source_dirty || extraction.value?.status === 'STALE')
const stage = computed(() => {
  const stages: WorkflowView[] = ['workbench', 'audio', 'transcript', 'record', 'export']
  // 已完成接诊不可变更，但保留产物仍可查看；应高亮医生正在查看的页面，
  // 而不是始终将进度指示固定在最后一步。
  if ((completed.value || view.value === 'record') && view.value !== 'workbench' && stages.includes(view.value as WorkflowView)) {
    return stages.indexOf(view.value as WorkflowView)
  }
  if (active.value && view.value === 'audio' && workflowView.value === 'transcript') {
    return stages.indexOf('audio')
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
const queueCount = computed(() => departmentHead.value ? queueVisitRows.value.length : patients.value.length)
const queueDisplayedCount = computed(() => departmentHead.value && queueStatusFilter.value === 'OPEN' ? queueOpenCount.value : queueCount.value)
const queueWaitingCount = computed(() => departmentHead.value ? selectedQueueVisitRows.value.filter(row => row.visit.status === 'WAITING').length : waitingCount.value)
const queueActiveCount = computed(() => departmentHead.value ? selectedQueueVisitRows.value.filter(row => row.visit.status === 'ACTIVE').length : activeCount.value)
const queueCompletedCount = computed(() => departmentHead.value ? selectedQueueVisitRows.value.filter(row => row.visit.status === 'COMPLETED').length : completedCount.value)
const queueOpenCount = computed(() => queueWaitingCount.value + queueActiveCount.value)
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
  turn.role_source === 'AUTO' || turn.role_source === 'UNKNOWN' || turn.role_source === 'FALLBACK').length || 0)
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
  PENDING: '待生成', QUEUED: '等待提取', RUNNING: '正在提取', GENERATED: '待整体确认', CONFIRMED: '已整体确认', FAILED: '提取失败', STALE: '转写已更新'
})[extraction.value?.status || 'PENDING'] || extraction.value?.status || '待生成')
const extractionRunning = computed(() => extractionRequestBusy.value || ['QUEUED', 'RUNNING'].includes(extraction.value?.status || ''))

function clearExtractionPolling() {
  extractionPollRevision += 1
  if (extractionPollTimer !== undefined) window.clearTimeout(extractionPollTimer)
  extractionPollTimer = undefined
}

watch(() => [currentVisit.value?.id, transcript.value?.snapshot_id, transcript.value?.snapshot_hash,
  extraction.value?.status, allTranscribed.value] as const, () => {
  clearExtractionPolling()
  const visitId = currentVisit.value?.id
  const snapshotId = transcript.value?.snapshot_id
  const snapshotHash = transcript.value?.snapshot_hash
  if (!visitId || !snapshotId || !allTranscribed.value || !['QUEUED', 'RUNNING'].includes(extraction.value?.status || '')) return
  const revision = extractionPollRevision
  const poll = async () => {
    try {
      const state = await api.clinicalExtraction(visitId)
      if (revision !== extractionPollRevision || currentVisit.value?.id !== visitId
        || transcript.value?.snapshot_id !== snapshotId || transcript.value?.snapshot_hash !== snapshotHash) return
      extractionError.value = ''
      if (state.snapshot_id === snapshotId && state.snapshot_hash === snapshotHash) extraction.value = state
    } catch {
      if (revision === extractionPollRevision) extractionError.value = '提取进度暂时无法获取，正在重新连接。'
    }
    if (revision === extractionPollRevision && ['QUEUED', 'RUNNING'].includes(extraction.value?.status || '')) {
      extractionPollTimer = window.setTimeout(poll, 2000)
    }
  }
  extractionPollTimer = window.setTimeout(poll, 1500)
})

async function refreshExtractionForTranscript(visitId: string) {
  const snapshotId = transcript.value?.snapshot_id
  const revision = ++extractionRequestRevision
  extractionRequestBusy.value = false
  extraction.value = null
  extractionError.value = ''
  try {
    const state = await api.clinicalExtraction(visitId)
    if (revision === extractionRequestRevision && currentVisit.value?.id === visitId
      && transcript.value?.snapshot_id === snapshotId) extraction.value = state
  } catch {
    if (revision === extractionRequestRevision && currentVisit.value?.id === visitId) extractionError.value = '提取状态加载失败，请重试。'
  }
}

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
const isAudioLoading = (item: Recording) => audioLoadingId.value === item.id
function nextAvatarTone() {
  if (!availableAvatarTones.length) {
    availableAvatarTones = [...AVATAR_TONES]
    for (let index = availableAvatarTones.length - 1; index > 0; index -= 1) {
      const randomIndex = Math.floor(Math.random() * (index + 1))
      ;[availableAvatarTones[index], availableAvatarTones[randomIndex]] = [availableAvatarTones[randomIndex], availableAvatarTones[index]]
    }
    if (availableAvatarTones.length > 1 && availableAvatarTones[availableAvatarTones.length - 1] === lastAvatarTone) {
      const lastIndex = availableAvatarTones.length - 1
      ;[availableAvatarTones[lastIndex], availableAvatarTones[lastIndex - 1]] = [availableAvatarTones[lastIndex - 1], availableAvatarTones[lastIndex]]
    }
  }
  const tone = availableAvatarTones.pop() || ''
  lastAvatarTone = tone
  return tone
}

const avatarClass = (patient: Patient | null) => {
  if (!patient) return ''
  if (!avatarToneByPatientId.has(patient.id)) avatarToneByPatientId.set(patient.id, nextAvatarTone())
  return avatarToneByPatientId.get(patient.id) || ''
}
const highlightText = (text: string) => [{ text, mark: false }]
const recordingStateLabel = computed(() => ({ idle: '准备录音', recording: '正在录音', paused: '已暂停' })[recordingState.value])
const recordingTimeLabel = computed(() => formatRecordingDuration(recordingElapsedMs.value))

function toast(text: string, type: MessageType = 'info') {
  showMessage(text, type)
}

/**
 * 导航被处理中的转写任务阻塞时，说明当前真实阶段。
 * ASR 原文预览会在角色 LLM 判断开始前先落库，因此“处理中 + 已有预览句段”
 * 可以和“仍在等待 ASR 原文”区分开来。
 */
function transcriptionProgressMessage() {
  const processingPreviews = asrPreviews.value.filter(preview => preview.status === 'PROCESSING')
  const roleInferenceInProgress = processingPreviews.some(preview => Array.isArray(preview.segments) && preview.segments.length > 0)
  if (roleInferenceInProgress) {
    return '音频转写已完成，AI 正在识别医患角色，完成后即可进入下一步。'
  }

  const recordingProcessing = recordings.value.some(recording => recording.status === 'PROCESSING')
  const activeJob = activeAsrJob?.status === 'PENDING' || activeAsrJob?.status === 'RUNNING'
  if (actionBusy.value === 'transcribe' || recordingProcessing || processingPreviews.length > 0 || activeJob) {
    return '正在转写音频，请稍候。'
  }
  return null
}

function recordingStatusLabel(recording: Recording) {
  if (recording.status === 'PROCESSING') {
    const preview = asrPreviews.value.find(item => item.recording_id === recording.id)
    if (preview?.status === 'PROCESSING' && Array.isArray(preview.segments) && preview.segments.length > 0) return '角色判断中'
  }
  return ({ UPLOADED: '待转写', PROCESSING: '转写中', DONE: '已转写', FAILED: '转写失败' })[recording.status]
    || recording.status
}

function addLog(text: string) {
  activity.value.unshift({ time: formatDateTime(new Date()), text })
}

async function refreshCore(keepSelection = true) {
  const [patientList, visitList] = await Promise.all([api.patients(), api.visits()])
  patients.value = patientList
  visits.value = visitList
  if (departmentHead.value) {
    const selectedVisit = keepSelection ? visitList.find(visit => visit.id === selectedVisitId.value) : null
    const prioritizedVisits = visitList.filter(visit => visit.status !== 'CANCELLED').slice().sort((a, b) =>
      visitStatusPriority(a.status) - visitStatusPriority(b.status) || visitActivityTime(b).localeCompare(visitActivityTime(a)))
    const nextVisit = selectedVisit || prioritizedVisits[0] || visitList[0]
    selectedVisitId.value = nextVisit?.id || ''
    selectedPatientId.value = nextVisit?.patient_id || ''
  } else if (!keepSelection || !patientList.some(p => p.id === selectedPatientId.value)) {
    selectedPatientId.value = patientList[0]?.id || ''
    selectedVisitId.value = patientVisit(selectedPatientId.value)?.id || ''
  }
}

async function refreshVisitState() {
  const visit = currentVisit.value
  const revision = ++visitStateRevision
  if (!visit) {
    clearVisitArtifacts()
    return
  }
  const previewRequest = typeof api.asrPreviews === 'function'
    ? api.asrPreviews(visit.id)
      .then(previews => ({ previews, error: '' }))
      .catch(error => ({ previews: null, error: error instanceof Error ? error.message : 'ASR 原文预览加载失败' }))
    : Promise.resolve({ previews: [] as AsrPreviewRecording[], error: '' })
  const activeJobRequest = typeof api.activeTranscription === 'function'
    ? api.activeTranscription(visit.id).then(job => job || null).catch(() => null)
    : Promise.resolve(null)
  const [[recordingList, transcriptState, extractionState, recordState, confirmationList, exportList], previewResult, activeJob] = await Promise.all([
    Promise.all([
    api.recordings(visit.id),
    api.transcript(visit.id),
    api.clinicalExtraction(visit.id),
    api.medicalRecord(visit.id),
    api.confirmations(visit.id),
    api.exports(visit.id)
    ]),
    previewRequest,
    activeJobRequest
  ])
  if (revision !== visitStateRevision || currentVisit.value?.id !== visit.id) return
  recordings.value = recordingList
  if (previewResult.previews) asrPreviews.value = previewResult.previews
  asrPreviewError.value = previewResult.error
  transcript.value = transcriptState
  extraction.value = extractionState
  record.value = recordState
  recordForm.value = recordState.content ? JSON.parse(JSON.stringify(recordState.content)) : null
  confirmations.value = confirmationList
  exports.value = exportList
  transcriptDraft.value = transcriptState.transcript
  activeAsrJob = activeJob
  syncAsrPreviewPolling(visit.id, revision, recordingList, activeJob)
}

function clearAsrPreviewPolling() {
  asrPreviewPollCycle += 1
  if (asrPreviewPollTimer !== undefined) window.clearInterval(asrPreviewPollTimer)
  asrPreviewPollTimer = undefined
  asrPreviewPollBusy = false
  asrPreviewPollVisitId = ''
  asrPreviewPollRevision = 0
  activeAsrJob = null
}

function syncAsrPreviewPolling(visitId: string, revision: number, recordingList: Recording[], job: AsrJob | null = activeAsrJob) {
  // 提交按钮已有 jobId 状态轮询，并且每轮会刷新原文；避免同时启动第二套定时器。
  if (actionBusy.value === 'transcribe') {
    clearAsrPreviewPolling()
    return
  }
  const hasProcessingRecording = recordingList.some(item => item.status === 'PROCESSING')
  const hasActiveJob = job?.status === 'PENDING' || job?.status === 'RUNNING'
  if (!hasProcessingRecording && !hasActiveJob) {
    clearAsrPreviewPolling()
    return
  }
  if (asrPreviewPollTimer !== undefined && asrPreviewPollVisitId !== visitId) clearAsrPreviewPolling()
  asrPreviewPollVisitId = visitId
  asrPreviewPollRevision = revision
  if (asrPreviewPollTimer !== undefined) return
  const pollCycle = asrPreviewPollCycle
  asrPreviewPollTimer = window.setInterval(async () => {
    const requestRevision = asrPreviewPollRevision
    if (pollCycle !== asrPreviewPollCycle || asrPreviewPollBusy
        || requestRevision !== visitStateRevision || currentVisit.value?.id !== visitId) return
    asrPreviewPollBusy = true
    try {
      const [recordingResult, previewResult, activeJobResult] = await Promise.allSettled([
        api.recordings(visitId),
        typeof api.asrPreviews === 'function' ? api.asrPreviews(visitId) : Promise.resolve([]),
        typeof api.activeTranscription === 'function' ? api.activeTranscription(visitId) : Promise.resolve(null)
      ])
      if (pollCycle !== asrPreviewPollCycle || requestRevision !== visitStateRevision || asrPreviewPollRevision !== requestRevision
          || currentVisit.value?.id !== visitId) return
      let latestRecordings: Recording[] | null = null
      if (recordingResult.status === 'fulfilled') {
        recordings.value = recordingResult.value
        latestRecordings = recordingResult.value
      }
      if (previewResult.status === 'fulfilled') {
        asrPreviews.value = previewResult.value
        asrPreviewError.value = ''
      } else {
        const error = previewResult.reason
        asrPreviewError.value = error instanceof Error ? error.message : 'ASR 原文预览刷新失败'
      }
      if (activeJobResult.status === 'fulfilled') activeAsrJob = activeJobResult.value || null
      // A failed preview request must not end tracking. Stop only after both the
      // task is terminal and no recording remains PROCESSING.
      const taskStillActive = activeJobResult.status === 'fulfilled'
        ? activeJobResult.value?.status === 'PENDING' || activeJobResult.value?.status === 'RUNNING'
        : activeAsrJob?.status === 'PENDING' || activeAsrJob?.status === 'RUNNING'
      const previewSucceeded = previewResult.status === 'fulfilled'
      if (latestRecordings && previewSucceeded
          && !latestRecordings.some(item => item.status === 'PROCESSING') && !taskStillActive) {
        clearAsrPreviewPolling()
      }
    } finally {
      if (pollCycle === asrPreviewPollCycle) asrPreviewPollBusy = false
    }
  }, 3000)
}

function clearVisitArtifacts() {
  visitStateRevision += 1
  clearAsrPreviewPolling()
  clearExtractionPolling()
  extractionRequestRevision += 1
  extractionRequestBusy.value = false
  extractionError.value = ''
  recordings.value = []
  asrPreviews.value = []
  asrPreviewError.value = ''
  transcript.value = null
  extraction.value = null
  record.value = null
  recordForm.value = null
  confirmations.value = []
  exports.value = []
  transcriptDraft.value = ''
}

async function deleteSelectedPatient() {
  const patientToDelete = currentPatient.value
  if (!departmentHead.value || !patientToDelete || actionBusy.value) return
  if (!deletePatientChecked.value) {
    modalError.value = '请再次确认将删除该患者及其所有接诊业务数据。'
    return
  }
  actionBusy.value = 'delete-patient'
  try {
    const result = await api.deletePatient(patientToDelete.id)
    modal.value = null
    modalError.value = ''
    deletePatientChecked.value = false
    selectedPatientId.value = ''
    selectedVisitId.value = ''
    patientSearchResults.value = null
    clearVisitArtifacts()
    if (result.cleanup_status === 'PENDING') {
      pendingPatientDeletionId.value = result.deletion_id
      startPatientDeletionPolling(result.deletion_id)
      toast('业务数据已删除，文件清理中。')
    } else {
      toast('患者及其业务数据已删除，关联日志已保留。', 'success')
    }
    await loadAll(false)
    if (queueSearch.value.trim()) await searchPatients()
  } catch (error) {
    modalError.value = error instanceof Error ? error.message : '删除患者失败'
  } finally {
    actionBusy.value = ''
  }
}

function startPatientDeletionPolling(deletionId: string) {
  if (patientDeletionPollTimer !== undefined) window.clearInterval(patientDeletionPollTimer)
  patientDeletionPollBusy = false
  patientDeletionPollTimer = window.setInterval(async () => {
    if (patientDeletionPollBusy || pendingPatientDeletionId.value !== deletionId) return
    patientDeletionPollBusy = true
    try {
      const result = await api.patientDeletionStatus(deletionId)
      if (result.cleanup_status === 'COMPLETED') {
        pendingPatientDeletionId.value = ''
        if (patientDeletionPollTimer !== undefined) window.clearInterval(patientDeletionPollTimer)
        patientDeletionPollTimer = undefined
        toast('患者相关文件清理已完成。')
      }
    } catch {
      // 后端仍会持续重试；临时状态查询失败不影响已完成的业务数据删除。
    } finally {
      patientDeletionPollBusy = false
    }
  }, 5000)
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

async function loadAiRouteSettings() {
  aiSettingsBusy.value = true
  try {
    const settings = await api.aiRouteSettings()
    aiSettings.value = settings
    aiSettingsDraft.value = settings.route
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 服务设置加载失败', 'error')
  } finally {
    aiSettingsBusy.value = false
  }
}

async function saveAiRouteSettings() {
  if (!canManageAiSettings.value || aiSettingsSaving.value) return
  aiSettingsSaving.value = true
  try {
    aiSettings.value = await api.updateAiRouteSettings(aiSettingsDraft.value)
    const unavailable = aiSettings.value.unavailable_capabilities || []
    toast(unavailable.length ? `设置已保存，但${unavailable.join('、')}当前不可用` : 'AI 处理环境已更新', unavailable.length ? 'info' : 'success')
  } catch (error) {
    toast(error instanceof Error ? error.message : 'AI 处理环境保存失败', 'error')
  } finally {
    aiSettingsSaving.value = false
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
    if (revision === patientSearchRevision) {
      patientSearchResults.value = results
      if (departmentHead.value) queueDoctorFilter.value = ''
    }
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
  clearAsrPreviewPolling()
  asrPreviews.value = []
  asrPreviewError.value = ''
  selectedPatientId.value = patientId
  if (departmentHead.value) {
    if (!visits.value.some(visit => visit.id === selectedVisitId.value && visit.patient_id === patientId)) {
      selectedVisitId.value = visits.value.find(visit => visit.patient_id === patientId)?.id || ''
    }
  } else {
    selectedVisitId.value = patientVisit(patientId)?.id || ''
  }
  view.value = 'workbench'
  transcriptTab.value = 'dialogue'
  if (departmentHead.value && selectedVisitId.value && typeof api.visit === 'function') {
    try { await api.visit(selectedVisitId.value) } catch (error) {
      toast(error instanceof Error ? error.message : '接诊详情加载失败', 'error')
    }
  }
  await loadAll(true)
  navigate(workflowView.value)
}

async function selectVisit(visitId: string, patientId: string) {
  if (busy.value || actionBusy.value) return
  selectedVisitId.value = visitId
  selectedPatientId.value = patientId
  await selectPatient(patientId)
}

function navigate(next: MainView) {
  const workflowViews: WorkflowView[] = ['workbench', 'audio', 'transcript', 'record', 'export']
  if (next === 'templates') {
    if (!canManageTemplates.value) {
      toast('仅科室长可以维护导出模板。', 'error')
      return
    }
    view.value = 'templates'
    window.scrollTo({ top: 0, behavior: 'smooth' })
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
  if (next === 'settings') {
    if (!canManageAiSettings.value) {
      toast('仅科室长可以管理 AI 服务设置。', 'error')
      return
    }
    view.value = 'settings'
    void loadAiRouteSettings()
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  if (!workflowViews.includes(next as WorkflowView)) {
    view.value = next
  } else {
    const requested = next as WorkflowView
    if (readOnlyCurrentPatient.value) {
      view.value = requested
      window.scrollTo({ top: 0, behavior: 'smooth' })
      return
    }
    const current = workflowView.value
    if (completed.value && requested !== 'workbench') {
      view.value = requested
    } else if (completed.value) {
      view.value = workflowView.value
      toast('已完成接诊仅支持查看步骤 02 至 05。')
    } else {
    const sameStep = requested === current
    const reviewingSignedRecord = requested === 'record' && current === 'export' && !!record.value?.record_id
    const returningToAudio = requested === 'audio' && current === 'transcript'
      && view.value === 'transcript' && active.value && !locked.value
    if (sameStep || reviewingSignedRecord || returningToAudio) view.value = requested
    else {
      view.value = current
      toast(transcriptionProgressMessage() || '请按当前接诊流程完成本步骤后再继续。')
    }
    }
  }
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function openTranscriptResult() {
  transcriptTab.value = 'dialogue'
  navigate('transcript')
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
  const phone = value.trim()
  if (!phone) return '请输入联系方式'
  return PATIENT_PHONE_PATTERN.test(phone) ? '' : '请输入正确的11位手机号'
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

async function uploadFiles(files: FileList | File[] | null, fallbackDuration = 0, source: 'pick' | 'recorder' = 'pick') {
  if (!currentVisit.value || locked.value) return false
  const accepted = new Set<File>()
  for (const file of Array.from(files || [])) {
    if (!RECORDING_EXTENSION_PATTERN.test(file.name)) {
      toast('仅支持 MP3、WAV、M4A 或 WEBM 格式。', 'error')
      continue
    }
    if (file.size > MAX_RECORDING_BYTES) {
      toast(`「${file.name}」超过 200MB，请拆分后上传。`, 'error')
      continue
    }
    if (Array.from(accepted).some(picked => picked.name === file.name && picked.size === file.size)
      || (source === 'pick' && recordings.value.some(item =>
        item.file_name === file.name && item.size_bytes === file.size && item.status !== 'FAILED'))) {
      toast(`「${file.name}」已上传，请勿重复添加。`, 'error')
      continue
    }
    accepted.add(file)
  }
  if (!accepted.size) return false
  let uploaded = false
  for (const file of Array.from(files || [])) {
    if (!accepted.has(file)) continue
    const duration = await audioDuration(file, fallbackDuration)
    if (duration <= 0) {
      toast(`无法读取「${file.name}」，请选用有效音频。`, 'error')
      continue
    }
    actionBusy.value = 'upload'
    try {
      await api.uploadRecording(currentVisit.value.id, file, duration)
      uploaded = true
      addLog(`上传录音：${file.name}`)
    } catch (error) {
      toast(error instanceof Error ? error.message : '上传失败', 'error')
    }
  }
  actionBusy.value = ''
  await loadAll(true)
  if (view.value === 'workbench') toast('录音已上传，可开始转写。')
  return uploaded
}

async function handlePickedFiles(event: Event) {
  const input = event.target as HTMLInputElement
  await uploadFiles(input.files)
  input.value = ''
}

function requestDeleteRecording(item: Recording) {
  if (locked.value || !active.value) return
  recordingToDelete.value = item
  modalError.value = ''
  modal.value = 'delete-recording'
}

async function loadExportTemplates() {
  if (typeof api.exportTemplates !== 'function') return
  try {
    exportTemplates.value = await api.exportTemplates()
    const preferred = exportTemplates.value.find(template => template.default_template) || exportTemplates.value[0]
    if (preferred && !exportTemplates.value.some(template => template.current_revision_id === selectedTemplateRevisionId.value)) {
      selectedTemplateRevisionId.value = preferred.current_revision_id
    }
  } catch (error) {
    toast(error instanceof Error ? error.message : '导出模板加载失败', 'error')
  }
}

function requestRetranscribeRecording(item: Recording) {
  if (locked.value || !active.value || item.status !== 'DONE') return
  recordingToRetranscribe.value = item
  modalError.value = ''
  modal.value = 'retranscribe-recording'
}

async function deleteSelectedRecording() {
  const item = recordingToDelete.value
  if (!item || !currentVisit.value) return
  actionBusy.value = 'delete'
  try {
    await api.deleteRecording(item.visit_id, item.id)
    if (playingId.value === item.id) stopAudio()
    modal.value = null
    recordingToDelete.value = null
    addLog(`删除录音：${item.file_name}`)
    await loadAll(true)
    navigate('audio')
    toast('录音已删除；手工转写和病历草稿已失效，可重新上传并转写。', 'success')
  } catch (error) {
    modalError.value = error instanceof Error ? error.message : '删除录音失败'
  } finally {
    actionBusy.value = ''
  }
}

async function retranscribeSelectedRecording() {
  const item = recordingToRetranscribe.value
  if (!item || !currentVisit.value || locked.value) return
  actionBusy.value = 'transcribe'
  try {
    const visitId = currentVisit.value.id
    // 路由由后端按接诊固化值/全局设置解析；这里不再把页面状态作为业务决策来源。
    const job = await api.retranscribeRecording(visitId, item.id)
    activeAsrProvider.value = job.provider_route
    modal.value = null
    recordingToRetranscribe.value = null
    addLog(`重新提交${job.provider_route === 'LOCAL' ? '本地' : '公网'} ASR：${item.file_name}`)
    await loadAll(true)
    for (let attempt = 0; attempt < 240; attempt++) {
      await new Promise(resolve => window.setTimeout(resolve, 3000))
      if (currentVisit.value?.id !== visitId) return
      const state = await api.transcribeStatus(visitId, job.job_id)
      await loadAll(true)
      if (state.status === 'SUCCEEDED') {
        transcript.value = state.transcript
        transcriptDraft.value = state.transcript?.transcript || ''
        navigate('transcript')
        toast('已使用新 ASR 完成转写，请重新核对文本并生成病历。')
        return
      }
      if (state.status === 'FAILED') throw new Error(state.error_message || '转写失败')
    }
    throw new Error('转写等待超时，请稍后查看任务状态')
  } catch (error) {
    await loadAll(true)
    toast(error instanceof Error ? error.message : '重新转写失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

watch(() => currentVisit.value?.id, (visitId, previousVisitId) => {
  asrProvider.value = currentVisit.value?.ai_route || aiSettings.value?.route || 'DASHSCOPE'
  roleProvider.value = null
  activeAsrProvider.value = null
  if (previousVisitId && visitId !== previousVisitId) {
    clearExtractionPolling()
    extractionRequestRevision += 1
    extractionRequestBusy.value = false
    extractionError.value = ''
    extraction.value = null
    clearAsrPreviewPolling()
    asrPreviews.value = []
    asrPreviewError.value = ''
  }
})

function requestTranscription() {
  if (!currentVisit.value || locked.value) return
  if (needsSupplementalTranscriptionConfirmation.value) {
    modalError.value = ''
    modal.value = 'supplemental-transcription'
    return
  }
  void startTranscription()
}

async function startTranscription() {
  if (!currentVisit.value || locked.value) return
  actionBusy.value = 'transcribe'
  try {
    const visitId = currentVisit.value.id
    // 首次提交时由后端原子读取并固化全局 AI 环境，避免客户端缓存过期造成路由竞争。
    const job = await api.transcribe(visitId)
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
        toast(unclassifiedRoleCount.value
          ? '转写完成，角色判断完成后将自动开始信息提取。'
          : roleReviewCount.value
            ? `转写完成，信息提取已自动开始，另有 ${roleReviewCount.value} 条角色建议核对。`
            : '转写完成，主诉与病情信息已自动开始提取。')
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
    const updated = await api.saveTranscript(currentVisit.value.id, transcriptDraft.value.trim())
    transcript.value = updated
    transcriptDraft.value = updated.transcript
    await refreshExtractionForTranscript(currentVisit.value.id)
    addLog('保存采用的转写文本')
    navigate('transcript')
    toast('转写文本已保存，当前提取已失效，请手动重新提取。')
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
    await refreshExtractionForTranscript(currentVisit.value.id)
    addLog('人工修订句段说话角色')
    toast('角色已人工确认，当前提取已失效，请手动重新提取。')
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
    const updated = await api.reclassifyTranscriptRoles(currentVisit.value.id)
    transcript.value = updated
    transcriptDraft.value = updated.transcript
    await refreshExtractionForTranscript(currentVisit.value.id)
    addLog('使用 AI 重新判断未人工确认的句段角色')
    toast('AI 角色判断已更新，信息提取已自动开始；请继续核对待人工确认的句段。')
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
  const element = firstReviewId ? roleReviewElements.get(firstReviewId) : null
  const container = transcriptBody.value
  if (!element) return

  if (container && container.scrollHeight > container.clientHeight) {
    container.scrollTo({
      top: element.offsetTop - (container.clientHeight - element.offsetHeight) / 2,
      behavior: 'smooth'
    })
    return
  }

  element.scrollIntoView({ behavior: 'smooth', block: 'center' })
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

async function startMicrophoneMeter(stream: MediaStream) {
  stopMicrophoneMeter()
  recordingSignalAvailable.value = true
  try {
    const context = new AudioContext()
    const analyser = context.createAnalyser()
    analyser.fftSize = 512
    const source = context.createMediaStreamSource(stream)
    source.connect(analyser)
    microphoneAudioContext = context
    microphoneAnalyser = analyser
    await context.resume()
    if (context.state !== 'running') throw new Error('麦克风电平检测未启动')
    const samples = new Uint8Array(analyser.fftSize)
    const updateLevel = () => {
      if (microphoneAnalyser !== analyser || recordingState.value === 'idle') return
      if (recordingState.value === 'paused' || recorder?.state !== 'recording') {
        microphoneMeterFrame = window.requestAnimationFrame(updateLevel)
        return
      }
      analyser.getByteTimeDomainData(samples)
      let sumSquares = 0
      for (const sample of samples) {
        const normalized = (sample - 128) / 128
        sumSquares += normalized * normalized
      }
      const rms = Math.sqrt(sumSquares / samples.length)
      recordingPeakRms = Math.max(recordingPeakRms, rms)
      recordingLevel.value = Math.min(1, rms * 16)
      if (rms >= 0.003) recordingSignalDetected.value = true
      microphoneMeterFrame = window.requestAnimationFrame(updateLevel)
    }
    updateLevel()
  } catch {
    recordingSignalAvailable.value = false
    microphoneAnalyser = null
    if (microphoneAudioContext) void microphoneAudioContext.close().catch(() => undefined)
    microphoneAudioContext = null
  }
}

function stopMicrophoneMeter() {
  if (microphoneMeterFrame) window.cancelAnimationFrame(microphoneMeterFrame)
  microphoneMeterFrame = 0
  microphoneAnalyser = null
  if (microphoneAudioContext) void microphoneAudioContext.close().catch(() => undefined)
  microphoneAudioContext = null
  recordingLevel.value = 0
}

function audioInputLabel(device: MediaDeviceInfo, index: number) {
  return device.label.trim() || `麦克风 ${index + 1}`
}

async function refreshAudioInputDevices(requestPermission = false) {
  const mediaDevices = navigator.mediaDevices
  if (!mediaDevices?.enumerateDevices) {
    audioInputHint.value = '当前浏览器不支持读取麦克风列表。'
    return
  }

  audioInputRefreshBusy.value = true
  audioInputHint.value = ''
  try {
    let devices = await mediaDevices.enumerateDevices()
    // Keep the browser's "communications" alias: on Chromium it can point to a
    // different Windows input than the ordinary system default.
    let inputs = devices.filter(device => device.kind === 'audioinput'
      && device.deviceId && device.deviceId !== 'default')
    if (requestPermission && (!inputs.length || inputs.some(device => !device.label.trim()))) {
      if (!mediaDevices.getUserMedia) throw new Error('当前浏览器无法请求麦克风权限。')
      const permissionStream = await mediaDevices.getUserMedia({ audio: true })
      permissionStream.getTracks().forEach(track => track.stop())
      devices = await mediaDevices.enumerateDevices()
      inputs = devices.filter(device => device.kind === 'audioinput'
        && device.deviceId && device.deviceId !== 'default')
    }

    audioInputDevices.value = inputs
    const selectedStillAvailable = inputs.some(device => device.deviceId === selectedAudioInputDeviceId.value)
    if (selectedAudioInputDeviceId.value && !selectedStillAvailable && recordingState.value === 'idle') {
      selectedAudioInputDeviceId.value = ''
      audioInputHint.value = '之前选择的麦克风已不可用，已切回系统默认输入。'
    } else if (!inputs.length) {
      audioInputHint.value = '暂未发现可选麦克风；请连接设备并点击刷新。'
    } else if (inputs.some(device => !device.label.trim())) {
      audioInputHint.value = '部分麦克风名称被浏览器隐藏，请点击“刷新设备”并允许麦克风权限。'
    } else {
      audioInputHint.value = `已找到 ${inputs.length} 个麦克风输入设备。`
    }
  } catch (error) {
    const name = error instanceof DOMException ? error.name : ''
    audioInputHint.value = name === 'NotAllowedError'
      ? '麦克风权限未允许，请在浏览器的网站设置中开启后重试。'
      : error instanceof Error ? error.message : '读取麦克风列表失败，请重试。'
  } finally {
    audioInputRefreshBusy.value = false
  }
}

function onAudioInputDeviceChange() {
  void refreshAudioInputDevices()
}

async function startRecording() {
  if (!currentVisit.value || locked.value) return
  if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
    toast('当前浏览器不支持录音功能。', 'error')
    return
  }
  try {
    const selectedDeviceId = selectedAudioInputDeviceId.value
    recorderStream = await navigator.mediaDevices.getUserMedia({
      audio: selectedDeviceId ? { deviceId: { exact: selectedDeviceId } } : true
    })
    const inputTrack = recorderStream.getAudioTracks()[0]
    const activeDeviceId = inputTrack?.getSettings().deviceId
    recordingInputLabel.value = inputTrack?.label
      || audioInputDevices.value.find(device => device.deviceId === activeDeviceId)?.label
      || (selectedDeviceId ? '所选麦克风' : '系统默认麦克风')
    void refreshAudioInputDevices()
    recordChunks = []
    recordingSignalDetected.value = false
    recordingSignalAvailable.value = true
    recordingLevel.value = 0
    recordingPeakRms = 0
    const supportedMimeType = [
      'audio/webm;codecs=opus',
      'audio/webm',
      'audio/mp4'
    ].find(type => MediaRecorder.isTypeSupported(type))
    recorder = supportedMimeType
      ? new MediaRecorder(recorderStream, { mimeType: supportedMimeType })
      : new MediaRecorder(recorderStream)
    recorder.ondataavailable = event => { if (event.data.size) recordChunks.push(event.data) }
    recorder.onstop = async () => {
      pauseRecordingTimer()
      stopMicrophoneMeter()
      recorderStream?.getTracks().forEach(track => track.stop())
      recorderStream = null
      recordingState.value = 'idle'
      const type = recorder?.mimeType || 'audio/webm'
      const extension = type.includes('mp4') ? 'm4a' : 'webm'
      const duration = recordingElapsedMs.value
      const signalAvailable = recordingSignalAvailable.value
      const peakRms = recordingPeakRms
      const audioBlob = new Blob(recordChunks, { type })
      recordChunks = []
      const file = new File([audioBlob], `${currentPatient.value?.name || '问诊'}_浏览器录音.${extension}`, { type })
      recorder = null
      resetRecordingTimer()
      recordingElapsedMs.value = 0
      recordingSignalDetected.value = false
      recordingSignalAvailable.value = true
      recordingInputLabel.value = ''
      recordingPeakRms = 0
      if (!file.size) {
        toast('录音结束，但浏览器没有生成音频数据；请检查麦克风权限后重试。', 'error')
        return
      }
      const uploaded = await uploadFiles([file], duration, 'recorder')
      if (uploaded && signalAvailable && peakRms < 0.003) {
        toast('录音文件已上传，但采集时没有检测到麦克风声音；请检查麦克风输入或静音设置。', 'error')
      }
    }
    recorder.start()
    recordingElapsedMs.value = 0
    recordingState.value = 'recording'
    startRecordingTimer()
    void startMicrophoneMeter(recorderStream)
  } catch (error) {
    stopMicrophoneMeter()
    recorderStream?.getTracks().forEach(track => track.stop())
    recorderStream = null
    recorder = null
    resetRecordingTimer()
    const name = error instanceof DOMException ? error.name : ''
    toast(name === 'OverconstrainedError' || name === 'NotFoundError'
      ? '所选麦克风不可用，请刷新设备列表或切换到其他输入。'
      : name === 'NotReadableError'
        ? '麦克风当前无法读取，请关闭占用它的程序后重试。'
        : '无法访问麦克风，请检查浏览器权限。', 'error')
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
  if (!currentVisit.value || locked.value || extractionRunning.value) return
  const visitId = currentVisit.value.id
  const snapshotId = transcript.value?.snapshot_id
  const snapshotHash = transcript.value?.snapshot_hash
  const revision = ++extractionRequestRevision
  extractionRequestBusy.value = true
  extractionError.value = ''
  try {
    const result = await api.generateClinicalExtraction(visitId)
    if (revision !== extractionRequestRevision || currentVisit.value?.id !== visitId
      || transcript.value?.snapshot_id !== snapshotId || transcript.value?.snapshot_hash !== snapshotHash) return
    extraction.value = result
    if (extraction.value?.status === 'GENERATED') {
      addLog('生成可追溯信息提取结果')
      toast('信息提取已生成，请核对原文证据后整体确认。')
    } else if (!['QUEUED', 'RUNNING'].includes(result.status)) {
      toast(extraction.value?.quality_issues[0] || '请先在全文编辑中修正转写。', 'error')
    }
  } catch (error) {
    if (revision === extractionRequestRevision && currentVisit.value?.id === visitId) {
      extractionError.value = error instanceof Error ? error.message : '信息提取失败'
      toast(extractionError.value, 'error')
    }
  } finally {
    if (revision === extractionRequestRevision) extractionRequestBusy.value = false
  }
}

async function confirmClinicalExtraction() {
  if (!currentVisit.value || locked.value || extraction.value?.status !== 'GENERATED') return
  actionBusy.value = 'confirm-extraction'
  try {
    extraction.value = await api.confirmClinicalExtraction(currentVisit.value.id)
    addLog('整体确认信息提取结果')
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
    navigate('record')
    toast('病历已签署，当前版本现可导出。')
  } catch (error) {
    toast(error instanceof Error ? error.message : '确认失败', 'error')
  } finally {
    actionBusy.value = ''
  }
}

async function recordExportLog(format: 'DOCX' | 'PDF') {
  if (!currentVisit.value || !selectedTemplate.value) throw new Error('当前没有可用的导出模板')
  exports.value = await api.recordExport(currentVisit.value.id, format, selectedTemplate.value.current_revision_id)
  addLog(format === 'DOCX' ? '发起 Word 导出' : '发起 PDF 导出')
}

async function waitForExport(format: 'DOCX' | 'PDF'): Promise<RecordExport | null> {
  if (!currentVisit.value || !record.value) return null
  const versionNo = record.value.version_no
  const templateRevisionId = selectedTemplate.value?.current_revision_id
  if (!templateRevisionId) throw new Error('当前没有可用的导出模板')
  let item = exports.value.find(e => e.format === format && e.version_no === versionNo
    && e.template_revision_id === templateRevisionId)
  if (!item || item.status === 'FAILED') {
    await recordExportLog(format)
    item = exports.value.find(e => e.format === format && e.version_no === versionNo
      && e.template_revision_id === templateRevisionId)
  }
  if (!item) return null
  for (let attempt = 0; attempt < 45 && ['PENDING', 'RUNNING'].includes(item.status); attempt++) {
    await new Promise(resolve => window.setTimeout(resolve, 1000))
    if (!currentVisit.value) return null
    exports.value = await api.exports(currentVisit.value.id)
    item = exports.value.find(e => e.id === item?.id) || item
  }
  return item
}

function exportFileBaseName(exportedAt: string, versionNo = record.value?.version_no || 1) {
  const date = formatDateTime(exportedAt).slice(0, 10).replace(/-/g, '')
  const doctorName = (record.value?.confirmed_by_name || currentVisit.value?.doctor_name || props.doctor.display_name).replace(/[\\/:*?"<>|]/g, '').trim()
  const patientName = (currentPatient.value?.name || record.value?.content?.name || '').replace(/[\\/:*?"<>|]/g, '').trim()
  return `【${date}】${doctorName || '医生'}_${patientName || '患者'}_V${versionNo}`
}

function downloadBlob(blob: Blob, format: 'DOCX' | 'PDF', fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${fileName}.${format === 'DOCX' ? 'docx' : 'pdf'}`
  link.click()
  URL.revokeObjectURL(url)
}

async function downloadHistoricalExport(item: RecordExport) {
  const format = item.format
  if (busy.value || item.status !== 'SUCCEEDED' || (format !== 'DOCX' && format !== 'PDF')) return
  busy.value = true
  try {
    const blob = await api.exportBlob(item.id)
    downloadBlob(blob, format, exportFileBaseName(item.created_at, item.version_no))
    toast(`${format === 'DOCX' ? 'Word' : 'PDF'} 病历已下载。`)
  } catch (error) {
    toast(error instanceof Error ? error.message : '历史导出文件下载失败', 'error')
  } finally {
    busy.value = false
  }
}

async function exportRecord(format: 'DOCX' | 'PDF', label: string) {
  if (busy.value || !record.value?.content || !confirmed.value) return
  busy.value = true
  try {
    const item = await waitForExport(format)
    if (!item || !currentVisit.value) throw new Error(`${label} 导出任务未创建`)
    if (item.status !== 'SUCCEEDED') throw new Error(item.error_message || `${label} 正在由服务器生成，请稍后重试下载`)
    const blob = await api.exportBlob(item.id)
    downloadBlob(blob, format, exportFileBaseName(item.created_at))
    toast(`${label} 病历已下载。`)
  } catch (error) { toast(error instanceof Error ? error.message : `${label} 导出失败`, 'error') }
  finally { busy.value = false }
}

async function exportWord() { await exportRecord('DOCX', 'Word') }

async function exportPdf() {
  await exportRecord('PDF', 'PDF')
}

async function showPreview() {
  if (!currentVisit.value || !selectedTemplate.value || !confirmed.value || busy.value || previewBusy.value) return
  const visitId = currentVisit.value.id
  const templateRevisionId = selectedTemplate.value.current_revision_id
  previewBusy.value = true
  try {
    const blob = await api.exportPreviewBlob(visitId, 'PDF', templateRevisionId)
    if (currentVisit.value?.id !== visitId || selectedTemplate.value?.current_revision_id !== templateRevisionId) return
    previewFormat.value = 'PDF'
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value)
      previewUrl.value = ''
    }
    previewOpen.value = true
    previewUrl.value = URL.createObjectURL(blob)
  } catch (error) { toast(error instanceof Error ? error.message : '预览生成失败', 'error') }
  finally { previewBusy.value = false }
}

function closePreview() {
  previewOpen.value = false
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
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
  if (!item.audio_url || audioLoadingId.value === item.id) return
  if (playingId.value !== item.id) {
    stopAudio()
    playingId.value = item.id
    audioVolume.value = 1
    audioPlaybackRate.value = 1
  }

  audioPlaybackError.value = ''
  audioAutoplayBlocked.value = false
  if (!audioPlaybackUrl.value) {
    await loadRecordingAudio(item)
    return
  }

  const player = audioPlayer
  if (!player) return
  try {
    if (audioEnded.value || player.ended) {
      player.currentTime = 0
      audioCurrentTime.value = 0
      audioEnded.value = false
    }
    if (player.paused) await player.play()
    else player.pause()
  } catch (error) {
    handleAudioPlayError(item, error)
  }
}

async function loadRecordingAudio(item: Recording) {
  if (audioLoadAbort || !item.audio_url) return
  const controller = new AbortController()
  audioLoadAbort = controller
  audioLoadingId.value = item.id
  audioPlaybackError.value = ''
  try {
    // The protected audio endpoint needs a Bearer header, so fetch the blob before playback.
    const blob = await api.audioBlob(item.id, controller.signal)
    if (controller.signal.aborted || playingId.value !== item.id) return
    if (!blob.size) throw new Error('服务端返回了空音频文件。')
    if (blob.type && !blob.type.startsWith('audio/')) {
      throw new Error(`服务端返回的文件类型不是音频（${blob.type}）。`)
    }
    audioObjectUrl = URL.createObjectURL(blob)
    audioPlaybackUrl.value = audioObjectUrl
    await nextTick()
    if (controller.signal.aborted || playingId.value !== item.id) return
    const player = audioElement.value
    if (!player) throw new Error('音频播放器未能加载。')
    player.preload = 'auto'
    player.volume = audioVolume.value
    player.playbackRate = audioPlaybackRate.value
    audioPlayer = player
    try {
      await player.play()
    } catch (error) {
      handleAudioPlayError(item, error)
    }
  } catch (error) {
    if (controller.signal.aborted) return
    console.warn('[audio] audio request failed', { recordingId: item.id, error })
    audioPlaybackError.value = error instanceof Error ? error.message : '音频加载失败，请重试。'
  } finally {
    if (audioLoadAbort === controller) audioLoadAbort = null
    if (audioLoadingId.value === item.id) audioLoadingId.value = ''
  }
}

function handleAudioPlayError(item: Recording, error: unknown) {
  if (error instanceof DOMException && error.name === 'NotAllowedError') {
    audioAutoplayBlocked.value = true
    return
  }
  audioPlaybackError.value = error instanceof Error ? error.message : '浏览器无法播放此音频，请重试。'
  console.warn('[audio] playback failed', { recordingId: item.id, error })
}

function updateAudioTime(currentTime: number, duration: number) {
  if (Number.isFinite(currentTime)) audioCurrentTime.value = Math.max(0, currentTime)
  if (Number.isFinite(duration) && duration > 0) audioMediaDuration.value = duration
}

function seekAudio(time: number) {
  if (!audioPlayer || !Number.isFinite(time)) return
  audioPlayer.currentTime = Math.max(0, Math.min(time, audioPlayer.duration || time))
  audioCurrentTime.value = audioPlayer.currentTime
  audioEnded.value = false
}

function setAudioVolume(volume: number) {
  audioVolume.value = Math.max(0, Math.min(1, volume))
  if (audioPlayer) audioPlayer.volume = audioVolume.value
}

function setAudioPlaybackRate(rate: number) {
  const allowedRates = [1, 1.25, 1.5, 2]
  audioPlaybackRate.value = allowedRates.includes(rate) ? rate : 1
  if (audioPlayer) audioPlayer.playbackRate = audioPlaybackRate.value
}

function onAudioPlaying() {
  audioIsPlaying.value = true
  audioEnded.value = false
  audioPlaybackError.value = ''
  audioAutoplayBlocked.value = false
}

function onAudioPaused() {
  audioIsPlaying.value = false
}

function onAudioEnded() {
  audioIsPlaying.value = false
  audioEnded.value = true
  if (audioMediaDuration.value > 0) audioCurrentTime.value = audioMediaDuration.value
}

function onAudioMediaError() {
  if (!audioPlayer?.error) return
  const mediaError = audioPlayer.error
  audioPlaybackError.value = `浏览器无法解码此音频（${mediaError.message || `错误 ${mediaError.code}`}），请重试。`
  console.warn('[audio] media decode failed', {
    recordingId: playingId.value,
    code: mediaError.code,
    message: mediaError.message
  })
}

async function retryAudio(item: Recording) {
  if (!audioPlaybackUrl.value) {
    await loadRecordingAudio(item)
    return
  }
  audioPlaybackError.value = ''
  audioAutoplayBlocked.value = false
  if (!audioPlayer) return
  try {
    if (audioPlayer.error) {
      audioPlayer.pause()
      audioPlayer.currentTime = 0
      audioPlayer.load()
    } else if (audioPlayer.ended) {
      audioPlayer.currentTime = 0
    }
    await audioPlayer.play()
  } catch (error) {
    handleAudioPlayError(item, error)
  }
}

function stopAudio() {
  audioLoadAbort?.abort()
  audioLoadAbort = null
  if (audioPlayer) {
    audioPlayer.pause()
    audioPlayer.onerror = null
    audioPlayer.removeAttribute('src')
    audioPlayer.load()
  }
  if (audioObjectUrl) URL.revokeObjectURL(audioObjectUrl)
  audioObjectUrl = null
  audioPlayer = null
  audioElement.value = null
  audioLoadingId.value = ''
  audioIsPlaying.value = false
  audioEnded.value = false
  audioCurrentTime.value = 0
  audioMediaDuration.value = 0
  audioVolume.value = 1
  audioPlaybackRate.value = 1
  audioPlaybackError.value = ''
  audioAutoplayBlocked.value = false
  audioPlaybackUrl.value = ''
  playingId.value = ''
}

function stopRecorder() {
  pauseRecordingTimer()
  if (recorder && recorder.state !== 'inactive') recorder.stop()
  else {
    stopMicrophoneMeter()
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
  audioDeviceChangeTarget = navigator.mediaDevices || null
  audioDeviceChangeTarget?.addEventListener?.('devicechange', onAudioInputDeviceChange)
  await Promise.all([loadAll(false), loadExportTemplates(), loadAiRouteSettings()])
  if (departmentHead.value && selectedVisitId.value && typeof api.visit === 'function') {
    try { await api.visit(selectedVisitId.value) } catch (error) {
      toast(error instanceof Error ? error.message : '接诊详情加载失败', 'error')
    }
  }
  navigate(workflowView.value)
})

onUnmounted(() => {
  clearExtractionPolling()
  extractionRequestRevision += 1
  stopAudio()
  stopRecorder()
  resetRecordingTimer()
  clearAsrPreviewPolling()
  audioDeviceChangeTarget?.removeEventListener?.('devicechange', onAudioInputDeviceChange)
  audioDeviceChangeTarget = null
  if (patientDeletionPollTimer !== undefined) window.clearInterval(patientDeletionPollTimer)
  queueResizeObserver?.disconnect()
  document.body.classList.remove('modal-open')
})

defineExpose({ selectPatient })
</script>

<template>
  <div class="shell" :class="{ 'transcript-shell': view === 'transcript' }">
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
        <button v-if="canManageAiSettings" class="nav-item" :class="{ active: view === 'settings' }" @click="navigate('settings')"><Icon name="gear" />AI 服务设置</button>
        <button v-if="canManageTemplates" class="nav-item" :class="{ active: view === 'templates' }" @click="navigate('templates')"><Icon name="gear" />模板管理</button>
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
        <button v-if="canManageAiSettings" type="button" class="ai-route-badge" :class="{ warning: globalAiRouteWarning }" :title="globalAiRouteWarning ? '当前 AI 环境存在不可用能力' : undefined" @click="navigate('settings')">AI 处理：{{ globalAiRouteLabel }}</button>
        <span v-else class="ai-route-badge" :class="{ warning: globalAiRouteWarning }" :title="globalAiRouteWarning ? '当前 AI 环境存在不可用能力' : undefined">AI 处理：{{ globalAiRouteLabel }}</span>
        <div class="doctor-profile">
          <span class="avatar">{{ doctor.display_name.slice(0, 1) }}</span>
          <div><b>{{ doctor.display_name }} {{ doctorRoleLabel(doctor.role) }}</b><span>{{ doctor.department_name }} · {{ doctorRoleLabel(doctor.role) }}</span></div>
        </div>
        <button class="text-btn" @click="emit('logout')">退出</button>
      </div>
    </header>

    <main class="main" :class="{ 'transcript-page': view === 'transcript', 'audio-page': view === 'audio' }">
      <div class="page-heading">
        <div>
          <div class="eyebrow"><i class="dot green"></i> CLINICAL WORKSPACE</div>
          <h1>{{ titles[view][0] }}</h1>
          <p>{{ titles[view][1] }}</p>
        </div>
        <div class="heading-actions" aria-hidden="true"></div>
      </div>
      <div v-if="patientDeletionPending" class="info-banner patient-deletion-pending">
        <Icon name="info" /><span>业务数据已删除，文件清理中。清理完成后此提示会自动消失。</span>
      </div>

      <template v-if="view !== 'audit' && view !== 'templates' && view !== 'settings'">
      <section class="card queue">
        <div class="queue-head">
          <div class="queue-title"><Icon name="users" />{{ departmentHead ? '全院接诊' : '今日接诊' }} <span class="badge">{{ queueDisplayedCount }} 条</span>
            <div class="queue-stats"><span>待接诊 <b>{{ String(queueWaitingCount).padStart(2, '0') }}</b></span><i></i><span>接诊中 <b>{{ String(queueActiveCount).padStart(2, '0') }}</b></span><i></i><span>已完成 <b>{{ String(queueCompletedCount).padStart(2, '0') }}</b></span></div>
          </div>
          <div class="queue-search-wrap">
            <input v-model="queueSearch" class="queue-search" placeholder="搜索患者姓名或编号" @keydown.enter.prevent="searchPatients" />
            <button type="button" class="btn small" :disabled="searchBusy" @click="searchPatients">{{ searchBusy ? '搜索中' : '搜索' }}</button>
          </div>
          <div v-if="departmentHead" class="queue-filters" aria-label="全院接诊筛选">
            <select v-model="queueDoctorFilter" aria-label="按接诊医生筛选">
              <option value="">全部医生</option>
              <option v-for="[doctorId, doctorName] in queueDoctorOptions" :key="doctorId" :value="doctorId">{{ doctorName }}</option>
            </select>
            <select v-model="queueStatusFilter" aria-label="按接诊状态筛选">
              <option value="OPEN">待处理（{{ queueOpenCount }}）</option>
              <option value="ACTIVE">接诊中（{{ queueActiveCount }}）</option>
              <option value="WAITING">待接诊（{{ queueWaitingCount }}）</option>
              <option value="COMPLETED">已完成（{{ queueCompletedCount }}）</option>
              <option value="ALL">全部记录（{{ selectedQueueVisitRows.length }}）</option>
            </select>
          </div>
          <button v-if="!departmentHead" class="queue-add" :disabled="busy" @click="openNewPatientModal"><Icon name="plus" />新增患者</button>
        </div>
        <div ref="queueCardsElement" class="queue-cards" @scroll.capture.passive="onQueueScroll">
          <template v-if="departmentHead">
          <div v-for="group in queueDoctorGroups" :key="group.id" class="doctor-queue-group" :data-visit-count="group.rows.length" :class="{ collapsed: collapsedDoctorGroups.has(group.id) }">
          <button type="button" class="doctor-group-head" :aria-expanded="!collapsedDoctorGroups.has(group.id)" @click="toggleDoctorGroup(group.id)">
            <span class="doctor-group-avatar">{{ group.name[0] || '医' }}</span>
            <span class="doctor-group-name"><b>{{ group.name }}</b><small>{{ group.rows.length }} 条接诊 · 最近更新 {{ formatDateTime(group.latest) }}</small></span>
            <span class="doctor-group-counts"><em v-if="group.active" class="active-count">接诊中 {{ group.active }}</em><em v-if="group.waiting" class="waiting-count">待接诊 {{ group.waiting }}</em><em v-if="group.completed" class="completed-count">已完成 {{ group.completed }}</em><span class="doctor-group-chevron">⌄</span></span>
          </button>
          <div v-if="!collapsedDoctorGroups.has(group.id)" class="doctor-group-items">
          <button v-for="row in group.rows" :key="row.visit.id" class="patient-tile visit-tile" :class="{ selected: row.visit.id === selectedVisitId }" @click="selectVisit(row.visit.id, row.patient.id)">
            <span class="avatar" :class="avatarClass(row.patient)">{{ row.patient.name[0] }}</span>
            <span class="patient-tile-content">
              <span class="patient-name"><b>{{ row.patient.name }}</b><span>{{ row.patient.gender }} · {{ row.patient.age ?? '—' }} 岁</span></span>
              <span class="patient-meta" :title="`接诊编号 ${row.visit.visit_no || '—'}`">接诊编号 <strong>{{ row.visit.visit_no || '—' }}</strong></span>
              <span class="patient-owner" :title="`最近更新：${formatDateTime(row.visit.last_activity_at || row.visit.created_at)}`">最近更新：{{ formatDateTime(row.visit.last_activity_at || row.visit.created_at) }}</span>
            </span>
            <span class="tile-status" :class="statusClass(row.visit)">{{ statusText(row.visit) }}</span>
          </button>
          </div>
          </div>
          <div v-if="!queueDoctorGroups.length" class="queue-filter-empty"><Icon name="search" /><b>{{ queueStatusFilter === 'OPEN' ? '暂无待处理接诊' : '暂无匹配接诊' }}</b><span>可切换状态或清除筛选后查看其他记录</span></div>
          </template>
          <template v-else>
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
          </template>
          <div v-if="!departmentHead && !queuePatients.length" class="small-muted">暂无匹配接诊记录</div>
        </div>
        <Transition name="queue-hint">
          <div v-if="queueHasMore && !queueScrollAcknowledged" class="queue-load-more">
            <button type="button" :aria-label="`继续向下浏览，约还有 ${queueHiddenCount} 条`" @click="scrollQueueFurther">
              <span>继续向下浏览</span><small>约还有 {{ queueHiddenCount }} 条</small><Icon name="arrow-down" />
            </button>
          </div>
        </Transition>
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
           <span class="read-only-note"><Icon name="lock" />科室长只读查看接诊详情，接诊医生：{{ currentVisit?.doctor_name || '—' }}</span>
            <button v-if="allTranscribed && view !== 'transcript'" class="btn primary audio-view-result-btn" @click="openTranscriptResult"><Icon name="text" />查看转写结果</button>
           <button v-if="departmentHead && currentPatient" class="btn danger" :disabled="busy || !!actionBusy" @click="modalError=''; deletePatientChecked=false; modal='delete-patient'"><Icon name="trash" />删除患者</button>
        </div>
        <div v-else class="patient-actions">
           <button v-if="allTranscribed && view !== 'transcript'" class="btn primary audio-view-result-btn" @click="openTranscriptResult"><Icon name="text" />查看转写结果</button>
          <button v-if="departmentHead && currentPatient" class="btn danger" :disabled="busy || !!actionBusy" @click="modalError=''; deletePatientChecked=false; modal='delete-patient'"><Icon name="trash" />删除患者</button>
          <button v-if="waiting" class="btn primary" :disabled="busy" @click="startVisit"><Icon name="play" />开始接诊</button>
          <template v-else-if="!closed">
            <button class="btn" :disabled="!exported || busy" title="当前版本确认并完成导出后可结束接诊" @click="modal='finish'"><Icon name="stop" />结束接诊</button>
            <button class="btn" :disabled="busy || !!actionBusy" @click="modalError=''; modal='cancel'">取消接诊</button>
          </template>
          <span v-else class="small-muted">该患者接诊已完成，不支持重复接诊</span>
        </div>
      </section>

      <nav class="steps" aria-label="接诊流程">
        <button v-for="(label, index) in ['患者 / 接诊', '问诊音频', '转写结果', '病历审核与签署', '病历导出']" :key="label"
                class="step" :class="{ done: index < stage || exported, current: index === stage }"
                :disabled="completed && index === 0"
                @click="navigate((['workbench','audio','transcript','record','export'] as MainView[])[index])">
          <span class="step-number"><Icon v-if="index < stage || exported" name="check" /><template v-else>{{ String(index + 1).padStart(2, '0') }}</template></span>
          <span class="step-label">{{ label }}<small>{{ ['CONSULTATION','UPLOAD','TRANSCRIPTION','REVIEW & SIGN','EXPORT'][index] }}</small></span>
        </button>
      </nav>

      <div class="transcript-notices">
        <div v-if="derivedDataInvalidated && !closed && !confirmed" class="info-banner"><Icon name="info" /><span>录音或转写已更新，手工转写、信息提取和病历草稿已失效，请重新核对并生成病历后再确认。</span></div>
        <div v-if="readOnlyCurrentPatient" class="info-banner read-only-banner"><Icon name="lock" /><span>当前为只读查看，接诊医生：{{ currentVisit?.doctor_name || '—' }}。录音、转写、病历内容和导出记录均只读；已有成功的导出文件可下载。</span></div>
      </div>
      </template>

      <div v-if="view === 'templates'" class="full-view template-management-view">
        <TemplateManagement :doctor-name="doctor.display_name" />
      </div>
      <div v-else-if="view === 'settings'" class="full-view ai-settings-view">
        <section class="card ai-settings-card">
          <div class="card-head"><h2><Icon name="gear" />AI 处理环境</h2><span class="badge teal">全系统统一</span></div>
          <div class="card-body">
            <p class="settings-lead">科室长选择后，新的接诊会统一使用该环境完成转写、角色识别和临床信息提取。已经开始转写的接诊不会改变。</p>
            <div class="ai-route-choices">
              <label class="ai-route-choice" :class="{ active: aiSettingsDraft === 'DASHSCOPE' }"><input v-model="aiSettingsDraft" type="radio" value="DASHSCOPE" /> <span><b>公网 AI</b><small>使用 DashScope 公网模型</small></span></label>
              <label class="ai-route-choice" :class="{ active: aiSettingsDraft === 'LOCAL' }"><input v-model="aiSettingsDraft" type="radio" value="LOCAL" /> <span><b>本地 AI</b><small>问诊内容仅发送到内网模型</small></span></label>
            </div>
            <div class="ai-scope-list"><b>统一应用范围</b><span>✓ 音频转写</span><span>✓ 角色识别</span><span>✓ 临床信息提取</span></div>
            <div v-if="aiSettings" class="ai-capability-grid">
              <div class="capability-heading"><b>能力状态</b><span>公网 AI</span><span>本地 AI</span></div>
              <div><b>音频转写</b><span :class="{ ok: aiSettings.public_asr_available, bad: !aiSettings.public_asr_available }">{{ aiSettings.public_asr_available ? '可用' : (aiSettings.public_asr_message || '未配置') }}</span><span :class="{ ok: aiSettings.local_asr_available, bad: !aiSettings.local_asr_available }">{{ aiSettings.local_asr_available ? '可用' : (aiSettings.local_asr_message || '未配置') }}</span></div>
              <div><b>角色识别</b><span :class="{ ok: aiSettings.public_role_available, bad: !aiSettings.public_role_available }">{{ aiSettings.public_role_available ? '可用' : (aiSettings.public_role_message || '未配置') }}</span><span :class="{ ok: aiSettings.local_role_available, bad: !aiSettings.local_role_available }">{{ aiSettings.local_role_available ? '可用' : (aiSettings.local_role_message || '未配置') }}</span></div>
              <div><b>信息提取</b><span :class="{ ok: aiSettings.public_extraction_available, bad: !aiSettings.public_extraction_available }">{{ aiSettings.public_extraction_available ? '可用' : (aiSettings.public_extraction_message || '未配置') }}</span><span :class="{ ok: aiSettings.local_extraction_available, bad: !aiSettings.local_extraction_available }">{{ aiSettings.local_extraction_available ? '可用' : (aiSettings.local_extraction_message || '未配置') }}</span></div>
            </div>
            <div v-if="aiSettings?.unavailable_capabilities?.length" class="info-banner"><Icon name="info" /><span>当前环境中{{ aiSettings.unavailable_capabilities.join('、') }}不可用；设置仍可保存，但业务操作会明确提示错误，不会自动切换到另一条路由。</span></div>
            <div class="settings-actions"><span class="small-muted">{{ aiSettings?.updated_by ? `最近修改：${aiSettings.updated_by} · ${formatDateTime(aiSettings.updated_at)}` : '尚未记录修改人' }}</span><button class="btn primary" :disabled="aiSettingsSaving || aiSettingsBusy" @click="saveAiRouteSettings">{{ aiSettingsSaving ? '保存中' : '保存设置' }}</button></div>
          </div>
        </section>
      </div>
      <div v-else-if="view === 'workbench'" class="consultation-start-view">
        <section class="card consultation-start-card" :class="{ 'is-active': active, 'is-closed': closed }">
          <div class="consultation-step-chip"><span class="step-chip-number">01</span><span>患者 / 接诊</span><small>CONSULTATION</small></div>
          <div class="consultation-start-content">
            <div class="consultation-start-icon"><Icon :name="waiting ? 'play' : closed ? 'check' : 'mic'" /></div>
            <span class="consultation-state-kicker">{{ readOnlyCurrentPatient ? 'READ ONLY' : waiting ? 'READY TO START' : closed ? 'VISIT CLOSED' : 'VISIT IN PROGRESS' }}</span>
            <h2>{{ readOnlyCurrentPatient ? `接诊状态：${statusText(currentVisit)}` : waiting ? '准备开始本次接诊' : closed ? `本次接诊${statusText(currentVisit)}` : '本次接诊进行中' }}</h2>
             <p>{{ readOnlyCurrentPatient ? '科室长仅可查看患者信息、接诊医生和该接诊已有的业务数据。' : waiting ? '确认患者身份后开始接诊，下一步即可上传录音或使用网页录音。' : closed ? '本次接诊记录已保留。已完成接诊的患者不支持重复接诊。' : '接诊已开始，前往录音上传即可使用文件上传或网页录音。' }}</p>
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
                <div class="dropzone" role="button" tabindex="0" :class="{ dragging }" @click="triggerUpload" @keydown.enter.prevent="triggerUpload" @keydown.space.prevent="triggerUpload" @dragover.prevent="dragging=true" @dragleave="dragging=false" @drop.prevent="dragging=false; uploadFiles($event.dataTransfer?.files ?? null)">
                  <div class="upload-icon"><Icon name="upload" /></div>
                  <p><strong>点击上传</strong> 或拖拽录音文件到此处</p><small>支持 MP3、WAV、M4A · 时长与大小不限</small>
                  <input ref="fileInput" type="file" accept=".mp3,.wav,.m4a,.webm,audio/mpeg,audio/wav,audio/mp4,audio/webm" hidden @change="handlePickedFiles" />
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
                  <span class="badge" :class="{ teal: item.status === 'DONE', red: item.status === 'FAILED' }">{{ recordingStatusLabel(item) }}</span>
                  <button v-if="active && !locked && item.status === 'DONE'" class="btn small" :aria-label="`重新转写 ${item.file_name}`" @click="requestRetranscribeRecording(item)"><Icon name="refresh" />重新转写</button>
                  <button v-if="active && !locked && ['UPLOADED', 'FAILED', 'DONE'].includes(item.status)" class="btn small danger" :aria-label="`删除 ${item.file_name}`" @click="requestDeleteRecording(item)"><Icon name="trash" />删除</button>
                </div>
                <p v-if="item.status === 'FAILED' && item.error_message" class="issue-hint">{{ item.error_message }}</p>
                <RecordingAudioPlayer
                  :recording="item"
                  :active="playingId === item.id"
                  :loading="isAudioLoading(item)"
                  :loaded="playingId === item.id && !!audioPlaybackUrl"
                  :playing="playingId === item.id && audioIsPlaying"
                  :ended="playingId === item.id && audioEnded"
                  :src="playingId === item.id ? audioPlaybackUrl : ''"
                  :current-time="playingId === item.id ? audioCurrentTime : 0"
                  :actual-duration="playingId === item.id ? audioMediaDuration : 0"
                  :volume="playingId === item.id ? audioVolume : 1"
                  :playback-rate="playingId === item.id ? audioPlaybackRate : 1"
                  :error="playingId === item.id ? audioPlaybackError : ''"
                  :autoplay-blocked="playingId === item.id && audioAutoplayBlocked"
                  @toggle="playRecording(item)"
                  @retry="retryAudio(item)"
                  @seek="seekAudio"
                  @volume="setAudioVolume"
                  @rate="setAudioPlaybackRate"
                  @audio-element="setAudioElement(item.id, $event)"
                  @time-update="updateAudioTime"
                  @loaded-metadata="updateAudioTime(audioCurrentTime, $event)"
                  @playing="onAudioPlaying"
                  @paused="onAudioPaused"
                  @ended="onAudioEnded"
                  @media-error="onAudioMediaError"
                />
              </div>
              <div v-if="recordings.length" class="asr-controls">
                <div class="ai-route-readonly"><span class="small-muted">本次接诊 AI 处理环境：{{ currentAiRouteLabel }}</span><span class="small-muted">转写、角色识别和信息提取统一使用此环境</span></div>
                <p v-if="activeAsrProvider" class="small-muted">最近提交任务：{{ activeAsrProvider === 'LOCAL' ? '本地 ASR' : '公网 ASR' }}</p>
              </div>
              <div v-if="recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" style="margin-top:13px">
                <button class="btn soft" :disabled="locked || !recordings.some(item => item.status === 'UPLOADED' || item.status === 'FAILED')" @click="requestTranscription"><Icon name="sparkle" />{{ recordings.some(item => item.status === 'FAILED') ? '重试转写' : '开始转写' }}</button>
              </div>
            </div>
          </section>

          <AsrPreviewCard :previews="asrPreviews" :error="asrPreviewError" />

          <section class="card">
            <div class="card-head"><h2><Icon name="text" />转写结果 <span v-if="allTranscribed" class="badge teal">已完成</span></h2>
              <div class="record-header-actions"><button class="text-btn" :disabled="!transcriptDraft" aria-label="复制转写文本" @click="copyTranscript"><Icon name="copy" /></button></div>
            </div>
            <template v-if="transcriptDraft">
              <aside v-if="roleReviewCount" class="role-attention" :class="{ 'is-analysis': unclassifiedRoleCount }" aria-live="polite">
                <div class="role-attention-icon"><Icon :name="unclassifiedRoleCount ? 'sparkle' : 'warning'" /></div>
                <div class="role-attention-copy"><div class="role-attention-title"><h3>{{ unclassifiedRoleCount ? '角色判断尚未完成' : '请核对角色判断' }}</h3><span>{{ unclassifiedRoleCount || roleReviewCount }} 条{{ unclassifiedRoleCount ? '待分析' : '待核对' }}</span></div>
                  <p v-if="unclassifiedRoleCount">本次转写的角色判断尚未完成，完成后将自动开始信息提取；期间可继续查看转写。</p><p v-else>AI 已完成初步判断，其中部分角色置信度不足或无法确认。信息提取与病历流程不受影响，可逐句核对。</p>
                </div>
             <div class="role-attention-actions"><span class="small-muted">{{ currentAiRouteDescription }} · 角色识别</span><template v-if="!readOnlyCurrentPatient"><button v-if="unclassifiedRoleCount" class="btn primary role-attention-action" :disabled="locked || !turnsMatchTranscript" @click="reclassifyTranscriptRoles"><Icon name="refresh" />{{ actionBusy === 'reclassify-roles' ? '正在 AI 判断' : '开始 AI 判断' }}</button><button v-else class="btn primary role-attention-action" :disabled="locked" @click="openRoleReview"><Icon name="list" />查看待核对句段</button></template></div>
              </aside>
           <div class="transcript-tabs">
                <button class="tab" :class="{ active: transcriptTab === 'dialogue' }" @click="transcriptTab='dialogue'">医患对话</button>
                <button class="tab" :class="{ active: transcriptTab === 'edit' }" @click="transcriptTab='edit'">全文编辑</button>
                <button class="tab" :class="{ active: transcriptTab === 'facts' }" @click="transcriptTab='facts'">信息提取结果</button>
              </div>
              <div v-if="transcriptTab === 'dialogue'" class="transcript-body">
                <div class="transcript-note"><Icon name="info" />真实 ASR 转写结果 · 请核对后采用</div>
                <div v-for="(item, index) in segments" :key="item.turn?.id || index" :ref="element => setRoleReviewElement(item.turn?.id, element)" class="dialogue" :class="{ patient: item.roleCode === 'PATIENT', 'needs-review': item.turn?.role_review_required }">
                  <span class="speaker">{{ item.role === '医生' ? '医' : item.role === '患者' ? '患' : '其' }}</span>
                  <div><div class="dialogue-meta"><span>{{ item.role }}</span><span v-if="roleStatus(item.turn)" class="role-status" :class="{ review: item.turn?.role_review_required, manual: item.turn?.role_source === 'MANUAL' }">{{ roleStatus(item.turn) }}</span><time>{{ item.time }}</time></div>
                    <p><template v-for="(piece, pieceIndex) in highlightText(item.text)" :key="pieceIndex"><mark v-if="piece.mark">{{ piece.text }}</mark><template v-else>{{ piece.text }}</template></template></p>
                     <div v-if="item.turn?.id && turnsMatchTranscript" class="role-control"><label><span>角色</span><select :value="item.turn.role" :disabled="locked || actionBusy === 'role'" @change="updateUtteranceRole(item.turn, $event)"><option value="DOCTOR">医生</option><option value="PATIENT">患者</option><option value="OTHER">其他人</option></select></label><button v-if="!readOnlyCurrentPatient && item.turn.role_review_required" class="role-confirm" type="button" :disabled="locked || actionBusy === 'role'" @click="confirmCurrentUtteranceRole(item.turn)">确认当前角色</button></div>
                  </div>
                </div>
              </div>
              <div v-else-if="transcriptTab === 'edit'" class="card-body transcript-edit-body">
                <label for="transcript-edit" class="transcript-note">核对转写文字；修改后需重新生成并确认病历</label>
                <textarea id="transcript-edit" v-model="transcriptDraft" class="transcript-editor" :disabled="locked"></textarea>
              </div>
              <div v-else class="transcript-body extraction-body">
                <div class="extraction-header">
                  <div class="extraction-heading">
                    <span class="extraction-heading-icon"><Icon name="shield" /></span>
                    <div><h3>信息提取结果</h3><p>请核对每项内容及其原文依据，确认后才能生成病历。</p></div>
                  </div>
                  <div class="extraction-status"><span class="badge" :class="{ teal: extractionConfirmed, amber: !extractionConfirmed }">{{ extractionStatusText }}</span><span v-if="extraction?.version_no" class="small-muted">提取版本 v{{ extraction.version_no }}</span></div>
                </div>
                <div class="extraction-content">
                  <div v-if="extraction?.quality_issues.length" class="extraction-issues"><b>需要处理</b><span v-for="issue in extraction.quality_issues" :key="issue">{{ issue }}</span></div>
                  <template v-else-if="extractionFacts.length">
                    <div class="extraction-facts">
                      <div v-for="item in extractionFacts" :key="item.key" class="source-fact extraction-fact" :class="{ 'fact-empty': !item.fact.value }">
                        <div class="fact-head"><div class="fact-label"><b>{{ item.label }}</b><span v-if="!item.fact.value" class="fact-state">待补充</span></div><span v-if="item.fact.value && item.fact.confidence != null" class="confidence-pill" :class="{ 'confidence-high': item.fact.confidence >= 90, 'confidence-medium': item.fact.confidence >= 70 && item.fact.confidence < 90, 'confidence-low': item.fact.confidence < 70 }">置信度 {{ item.fact.confidence }}%</span></div>
                        <p>{{ item.fact.value || '未从当前对话提取到明确事实' }}</p>
                        <div v-if="item.fact.evidence && item.fact.evidence.length" class="fact-evidence"><small v-for="evidence in item.fact.evidence" :key="`${evidence.turn_index}-${evidence.quote}`">{{ evidence.role === 'DOCTOR' ? '医生' : '患者' }} · {{ formatTime(evidence.start_ms) }} · “{{ evidence.quote }}”</small></div>
                      </div>
                    </div>
                  </template>
                  <div v-else class="empty-extraction">当前快照尚未生成结构化提取结果。</div>
                </div>
                <div v-if="!readOnlyCurrentPatient && !locked" class="extraction-actions">
                  <span class="extraction-route-note">{{ currentAiRouteDescription }} · 信息提取</span>
                  <div class="extraction-action-buttons">
                    <button v-if="extraction?.status !== 'GENERATED' && !extractionConfirmed" class="btn soft" :disabled="extractionRunning || !allTranscribed || !!unclassifiedRoleCount" @click="generateClinicalExtraction"><Icon name="sparkle" />{{ extractionRunning ? '正在提取' : extraction?.status === 'STALE' ? '重新提取' : extraction?.status === 'FAILED' ? '重试提取' : '生成信息提取' }}</button>
                    <button v-else-if="extraction?.status === 'GENERATED'" class="btn primary" :disabled="actionBusy === 'confirm-extraction'" @click="confirmClinicalExtraction"><Icon name="check" />{{ actionBusy === 'confirm-extraction' ? '正在确认' : '整体确认提取结果' }}</button>
                  </div>
                </div>
              </div>
              <div class="transcript-actions">
                <span class="small-muted">{{ transcriptDraft.length }} 字 · {{ recordings.filter(item => item.status === 'DONE').length }} 段录音</span>
             <button v-if="!readOnlyCurrentPatient && transcriptTab === 'edit'" class="btn small soft" :disabled="locked" @click="saveTranscript"><Icon name="save" />保存转写</button>
             <button v-else-if="!readOnlyCurrentPatient" class="btn small soft" :disabled="locked || !allTranscribed" @click="generateRecord"><Icon name="sparkle" />生成病历</button>
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

      <div v-else-if="view === 'audio'" class="full-view audio-view" :class="{
        'audio-view-waiting': waiting,
        'audio-view-active': active,
        'audio-view-completed': completed,
        'audio-view-asr-empty': !hasAsrPreviewContent && !asrPreviewError
      }">
        <section class="card">
          <div class="card-head">
            <h2><Icon name="mic" />问诊录音</h2>
            <div class="audio-card-head-actions">
              <span class="small-muted">{{ String(recordings.length).padStart(2, '0') }} 段录音</span>
              <button v-if="allTranscribed || completed" class="btn primary audio-view-result-btn" @click="openTranscriptResult"><Icon name="text" />查看转写结果</button>
            </div>
          </div>
          <div class="card-body">
            <div v-if="waiting" class="step-guard">
              <div class="empty-icon"><Icon name="mic" /></div>
              <span class="step-guard-kicker">步骤 01 尚未开始</span>
              <h3>先开始本次接诊</h3>
              <p>开始接诊后，才能上传录音或使用网页录音。</p>
              <button v-if="!readOnlyCurrentPatient" class="btn primary" :disabled="busy || !currentPatient" @click="startVisit"><Icon name="play" />开始接诊</button>
              <span v-else class="small-muted">科室长仅可查看该接诊记录</span>
            </div>
            <template v-else-if="active">
              <div v-if="readOnlyCurrentPatient" class="info-banner read-only-banner"><Icon name="lock" />当前接诊正在进行，科室长仅可查看已有录音和处理结果。</div>
              <template v-if="!readOnlyCurrentPatient">
              <div class="ai-route-readonly audio-route-note">
                <Icon name="shield" />
                <div>
                  <strong>本次接诊 AI 处理环境：{{ currentAiRouteLabel }}</strong>
                  <span>转写、角色识别和信息提取统一使用此环境</span>
                  <span v-if="activeAsrProvider">最近提交任务：{{ activeAsrProvider === 'LOCAL' ? '本地 ASR' : '公网 ASR' }}</span>
                </div>
              </div>
              <div class="audio-source-grid">
                <div class="upload-method">
                  <div class="method-heading"><span class="method-icon"><Icon name="upload" /></span><div><h3>上传录音文件</h3><p>支持 MP3、WAV、M4A、WEBM</p></div></div>
                  <div class="dropzone compact-dropzone" role="button" tabindex="0" :class="{ dragging }" @click="triggerUpload" @keydown.enter.prevent="triggerUpload" @keydown.space.prevent="triggerUpload" @dragover.prevent="dragging=true" @dragleave="dragging=false" @drop.prevent="dragging=false; uploadFiles($event.dataTransfer?.files ?? null)">
                    <div class="upload-icon"><Icon name="upload" /></div><p><strong>点击上传</strong> 或拖拽到此处</p><small>单个文件最大 200MB</small>
                    <input ref="fileInput" type="file" accept=".mp3,.wav,.m4a,.webm,audio/mpeg,audio/wav,audio/mp4,audio/webm" hidden @change="handlePickedFiles" />
                  </div>
                </div>
                <div class="recorder-card" :class="`is-${recordingState}`">
                  <div class="recorder-card-head"><div class="method-heading"><span class="method-icon recorder-method-icon"><Icon name="mic" /></span><div><h3>网页录音</h3><p>可选择本次录音使用的麦克风</p></div></div><span class="recorder-state-badge" :class="`state-${recordingState}`"><i></i>{{ actionBusy === 'upload' ? '正在保存' : recordingStateLabel }}</span></div>
                  <div class="recording-input-control">
                    <label for="recording-audio-input">输入设备</label>
                    <select id="recording-audio-input" v-model="selectedAudioInputDeviceId" aria-label="网页录音输入设备" :disabled="recordingState !== 'idle' || actionBusy === 'upload' || audioInputRefreshBusy">
                      <option value="">系统默认麦克风</option>
                      <option v-for="(device, index) in audioInputDevices" :key="device.deviceId" :value="device.deviceId">{{ audioInputLabel(device, index) }}</option>
                    </select>
                    <button type="button" class="recording-input-refresh" :disabled="recordingState !== 'idle' || actionBusy === 'upload' || audioInputRefreshBusy" @click="refreshAudioInputDevices(true)">
                      <Icon name="refresh" />{{ audioInputRefreshBusy ? '读取中' : '刷新设备' }}
                    </button>
                  </div>
                  <p v-if="audioInputHint" class="recording-input-hint" role="status">{{ audioInputHint }}</p>
                  <p v-if="recordingInputLabel" class="recording-input-hint recording-input-current">本次录音输入：{{ recordingInputLabel }}</p>
                  <div class="recorder-live" :class="`state-${recordingState}`">
                    <div class="recorder-timer">{{ recordingTimeLabel }}</div>
                    <div class="live-waveform" :aria-label="recordingSignalLabel" role="img"><i v-for="n in 36" :key="n" :style="{ height: recordingWaveHeight(n), opacity: recordingSignalDetected ? 1 : 0.3 }"></i></div>
                    <p>{{ recordingState === 'paused' ? '录音已暂停，点击继续录音' : recordingSignalLabel }}</p>
                  </div>
                  <div class="record-controls recorder-controls">
                    <button class="btn recorder-main-btn" :class="{ primary: recordingState === 'idle', soft: recordingState === 'paused' }" :disabled="locked || actionBusy === 'upload'" @click="recordingState === 'idle' ? startRecording() : resumeRecording()"><Icon :name="recordingState === 'recording' ? 'pause' : recordingState === 'paused' ? 'play' : 'mic'" />{{ recordingState === 'recording' ? '暂停录音' : recordingState === 'paused' ? '继续录音' : '开始录音' }}</button>
                    <button class="btn recorder-stop-btn" :disabled="locked || recordingState === 'idle' || !recorder" @click="stopRecording"><Icon name="stop" />结束录音</button>
                  </div>
                </div>
              </div>
              <div class="upload-footer"><span>完整录音上传后，点击“开始转写”</span></div>
              </template>
              <div v-if="allTranscribed" class="info-banner audio-transcription-complete-banner">
                <Icon name="check" />
                <span>录音已全部转写完成，可直接查看医患对话和转写结果。</span>
                <button class="btn primary audio-view-result-btn" @click="openTranscriptResult"><Icon name="text" />查看转写结果</button>
              </div>
              <div v-for="item in recordings" :key="item.id" class="audio-item">
                <div class="audio-info">
                  <div class="file-icon"><Icon name="file" /></div>
                  <div><div class="audio-name">{{ item.file_name }}</div><div class="audio-meta">{{ formatBytes(item.size_bytes) }} · {{ formatDuration(item.duration_ms) }}</div></div>
                  <span class="badge" :class="{ teal: item.status === 'DONE', red: item.status === 'FAILED' }">{{ recordingStatusLabel(item) }}</span>
                  <button v-if="active && !locked && item.status === 'DONE'" class="btn small" :aria-label="`重新转写 ${item.file_name}`" @click="requestRetranscribeRecording(item)"><Icon name="refresh" />重新转写</button>
                  <button v-if="active && !locked && ['UPLOADED', 'FAILED', 'DONE'].includes(item.status)" class="btn small danger" :aria-label="`删除 ${item.file_name}`" @click="requestDeleteRecording(item)"><Icon name="trash" />删除</button>
                </div>
                <p v-if="item.status === 'FAILED' && item.error_message" class="issue-hint">{{ item.error_message }}</p>
                <div class="audio-playback-row">
                  <RecordingAudioPlayer
                    :recording="item"
                    :active="playingId === item.id"
                    :loading="isAudioLoading(item)"
                    :loaded="playingId === item.id && !!audioPlaybackUrl"
                    :playing="playingId === item.id && audioIsPlaying"
                    :ended="playingId === item.id && audioEnded"
                    :src="playingId === item.id ? audioPlaybackUrl : ''"
                    :current-time="playingId === item.id ? audioCurrentTime : 0"
                    :actual-duration="playingId === item.id ? audioMediaDuration : 0"
                    :volume="playingId === item.id ? audioVolume : 1"
                    :playback-rate="playingId === item.id ? audioPlaybackRate : 1"
                    :error="playingId === item.id ? audioPlaybackError : ''"
                    :autoplay-blocked="playingId === item.id && audioAutoplayBlocked"
                    @toggle="playRecording(item)"
                    @retry="retryAudio(item)"
                    @seek="seekAudio"
                    @volume="setAudioVolume"
                    @rate="setAudioPlaybackRate"
                    @audio-element="setAudioElement(item.id, $event)"
                    @time-update="updateAudioTime"
                    @loaded-metadata="updateAudioTime(audioCurrentTime, $event)"
                    @playing="onAudioPlaying"
                    @paused="onAudioPaused"
                    @ended="onAudioEnded"
                    @media-error="onAudioMediaError"
                  />
                  <div v-if="!readOnlyCurrentPatient && item.id === transcriptionActionRecordingId" class="transcribe-action">
                    <button class="btn soft" :disabled="locked" @click="requestTranscription"><Icon name="sparkle" />{{ recordings.some(recording => recording.status === 'FAILED') ? '重试转写' : '开始转写' }}</button>
                  </div>
                </div>
              </div>
            </template>
            <template v-else-if="completed">
              <div class="info-banner audio-completed-banner">
                <Icon name="lock" />
                <span>本次接诊已完成，以下录音仅供查看和播放，不能再上传或转写。</span>
                <button class="btn primary audio-view-result-btn" @click="openTranscriptResult"><Icon name="text" />查看转写结果</button>
              </div>
              <div v-if="!recordings.length" class="empty-state"><div class="empty-icon"><Icon name="mic" /></div><h3>没有保留的录音</h3><p>该已完成接诊未找到录音记录。</p></div>
              <div v-for="item in recordings" :key="item.id" class="audio-item">
                <div class="audio-info">
                  <div class="file-icon"><Icon name="file" /></div>
                  <div><div class="audio-name">{{ item.file_name }}</div><div class="audio-meta">{{ formatBytes(item.size_bytes) }} · {{ formatDuration(item.duration_ms) }}</div></div>
                  <span class="badge" :class="{ teal: item.status === 'DONE', red: item.status === 'FAILED' }">{{ ({ UPLOADED:'待转写', PROCESSING:'转写中', DONE:'已转写', FAILED:'转写失败' })[item.status] || item.status }}</span>
                </div>
                <p v-if="item.status === 'FAILED' && item.error_message" class="issue-hint">{{ item.error_message }}</p>
                <RecordingAudioPlayer
                  :recording="item"
                  :active="playingId === item.id"
                  :loading="isAudioLoading(item)"
                  :loaded="playingId === item.id && !!audioPlaybackUrl"
                  :playing="playingId === item.id && audioIsPlaying"
                  :ended="playingId === item.id && audioEnded"
                  :src="playingId === item.id ? audioPlaybackUrl : ''"
                  :current-time="playingId === item.id ? audioCurrentTime : 0"
                  :actual-duration="playingId === item.id ? audioMediaDuration : 0"
                  :volume="playingId === item.id ? audioVolume : 1"
                  :playback-rate="playingId === item.id ? audioPlaybackRate : 1"
                  :error="playingId === item.id ? audioPlaybackError : ''"
                  :autoplay-blocked="playingId === item.id && audioAutoplayBlocked"
                  @toggle="playRecording(item)"
                  @retry="retryAudio(item)"
                  @seek="seekAudio"
                  @volume="setAudioVolume"
                  @rate="setAudioPlaybackRate"
                  @audio-element="setAudioElement(item.id, $event)"
                  @time-update="updateAudioTime"
                  @loaded-metadata="updateAudioTime(audioCurrentTime, $event)"
                  @playing="onAudioPlaying"
                  @paused="onAudioPaused"
                  @ended="onAudioEnded"
                  @media-error="onAudioMediaError"
                />
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
        <AsrPreviewCard
          :previews="asrPreviews"
          :error="asrPreviewError"
          :show-view-action="completed || (allTranscribed && hasAsrPreviewContent)"
          @view-transcript="openTranscriptResult"
        />
      </div>

      <div v-else-if="view === 'transcript'" class="full-view transcript-view">
        <section class="card">
          <div class="card-head"><h2><Icon name="text" />转写结果 <span v-if="allTranscribed" class="badge teal">已完成</span></h2><div class="record-header-actions"><button v-if="canReturnToAudio" class="btn small" aria-label="返回录音上传" @click="navigate('audio')"><Icon name="arrow-left" />返回录音上传</button><button class="text-btn" :disabled="!transcriptDraft" @click="copyTranscript"><Icon name="copy" /></button></div></div>
          <aside v-if="roleReviewCount" class="role-attention" :class="{ 'is-analysis': unclassifiedRoleCount }" aria-live="polite">
            <div class="role-attention-icon"><Icon :name="unclassifiedRoleCount ? 'sparkle' : 'warning'" /></div>
            <div class="role-attention-copy"><div class="role-attention-title"><h3>{{ unclassifiedRoleCount ? '角色判断尚未完成' : '请核对角色判断' }}</h3><span>{{ unclassifiedRoleCount || roleReviewCount }} 条{{ unclassifiedRoleCount ? '待分析' : '待核对' }}</span></div>
              <p v-if="unclassifiedRoleCount">本次转写的角色判断尚未完成，完成后将自动开始信息提取；期间可继续查看转写。</p><p v-else>AI 已完成初步判断，其中部分角色置信度不足或无法确认。信息提取与病历流程不受影响，可逐句核对。</p>
            </div>
            <div class="role-attention-actions"><span class="small-muted">{{ currentAiRouteDescription }} · 角色识别</span><template v-if="!readOnlyCurrentPatient"><button v-if="unclassifiedRoleCount" class="btn primary role-attention-action" :disabled="locked || !turnsMatchTranscript" @click="reclassifyTranscriptRoles"><Icon name="refresh" />{{ actionBusy === 'reclassify-roles' ? '正在 AI 判断' : '开始 AI 判断' }}</button><button v-else class="btn primary role-attention-action" :disabled="locked" @click="openRoleReview"><Icon name="list" />查看待核对句段</button></template></div>
          </aside>
          <div class="transcript-tabs">
            <button class="tab" :class="{ active: transcriptTab === 'dialogue' }" @click="transcriptTab='dialogue'">医患对话</button>
            <button class="tab" :class="{ active: transcriptTab === 'edit' }" @click="transcriptTab='edit'">全文编辑</button>
            <button class="tab" :class="{ active: transcriptTab === 'facts' }" @click="transcriptTab='facts'">信息提取结果</button>
          </div>
          <div v-if="transcriptTab === 'dialogue'" class="transcript-dialogue-layout">
            <div ref="transcriptBody" class="transcript-body">
              <div class="transcript-note"><Icon name="info" />真实 ASR 转写结果 · 请核对后采用</div>
              <div v-for="(item, index) in segments" :key="item.turn?.id || index" :ref="element => setRoleReviewElement(item.turn?.id, element)" class="dialogue" :class="{ patient: item.roleCode === 'PATIENT', 'needs-review': item.turn?.role_review_required }">
                <span class="speaker">{{ item.role === '医生' ? '医' : item.role === '患者' ? '患' : '其' }}</span>
                <div><div class="dialogue-meta"><span>{{ item.role }}</span><span v-if="roleStatus(item.turn)" class="role-status" :class="{ review: item.turn?.role_review_required, manual: item.turn?.role_source === 'MANUAL' }">{{ roleStatus(item.turn) }}</span><time>{{ item.time }}</time></div>
                  <p><template v-for="(piece, pieceIndex) in highlightText(item.text)" :key="pieceIndex"><mark v-if="piece.mark">{{ piece.text }}</mark><template v-else>{{ piece.text }}</template></template></p>
                  <div v-if="item.turn?.id && turnsMatchTranscript" class="role-control"><label><span>角色</span><select :value="item.turn.role" :disabled="locked || actionBusy === 'role'" @change="updateUtteranceRole(item.turn, $event)"><option value="DOCTOR">医生</option><option value="PATIENT">患者</option><option value="OTHER">其他人</option></select></label><button v-if="!readOnlyCurrentPatient && item.turn.role_review_required" class="role-confirm" type="button" :disabled="locked || actionBusy === 'role'" @click="confirmCurrentUtteranceRole(item.turn)">确认当前角色</button></div>
                </div>
              </div>
            </div>
            <ClinicalSummaryCard :extraction="extraction" :snapshot-id="transcript?.snapshot_id || null"
              :snapshot-hash="transcript?.snapshot_hash || null" :role-review-count="roleReviewCount"
              :role-analysis-pending-count="unclassifiedRoleCount"
              :all-transcribed="allTranscribed" :read-only="readOnlyCurrentPatient || locked"
              :busy="extractionRequestBusy" :error="extractionError"
              @retry="generateClinicalExtraction" @review="openRoleReview" @details="transcriptTab = 'facts'"
              @confirm="confirmClinicalExtraction" />
          </div>
          <div v-else-if="transcriptTab === 'edit'" class="card-body transcript-edit-body">
            <label for="transcript-edit" class="transcript-note">核对转写文字；修改后需重新生成并确认病历</label>
            <textarea id="transcript-edit" v-model="transcriptDraft" class="transcript-editor" :disabled="locked"></textarea>
          </div>
          <div v-else class="transcript-body extraction-body">
            <div class="extraction-header">
              <div class="extraction-heading">
                <span class="extraction-heading-icon"><Icon name="shield" /></span>
                <div><h3>信息提取结果</h3><p>请核对每项内容及其原文依据，确认后才能生成病历。</p></div>
              </div>
              <div class="extraction-status"><span class="badge" :class="{ teal: extractionConfirmed, amber: !extractionConfirmed }">{{ extractionStatusText }}</span><span v-if="extraction?.version_no" class="small-muted">提取版本 v{{ extraction.version_no }}</span></div>
            </div>
            <div class="extraction-content">
              <div v-if="extraction?.quality_issues.length" class="extraction-issues"><b>需要处理</b><span v-for="issue in extraction.quality_issues" :key="issue">{{ issue }}</span></div>
              <template v-else-if="extractionFacts.length">
                <div class="extraction-facts">
                  <div v-for="item in extractionFacts" :key="item.key" class="source-fact extraction-fact" :class="{ 'fact-empty': !item.fact.value }"><div class="fact-head"><div class="fact-label"><b>{{ item.label }}</b><span v-if="!item.fact.value" class="fact-state">待补充</span></div><span v-if="item.fact.value && item.fact.confidence != null" class="confidence-pill" :class="{ 'confidence-high': item.fact.confidence >= 90, 'confidence-medium': item.fact.confidence >= 70 && item.fact.confidence < 90, 'confidence-low': item.fact.confidence < 70 }">置信度 {{ item.fact.confidence }}%</span></div><p>{{ item.fact.value || '未从当前对话提取到明确事实' }}</p><div v-if="item.fact.evidence && item.fact.evidence.length" class="fact-evidence"><small v-for="evidence in item.fact.evidence" :key="`${evidence.turn_index}-${evidence.quote}`">{{ evidence.role === 'DOCTOR' ? '医生' : '患者' }} · {{ formatTime(evidence.start_ms) }} · “{{ evidence.quote }}”</small></div></div>
                </div>
              </template>
              <div v-else class="empty-extraction">当前快照尚未生成结构化提取结果。</div>
            </div>
            <div v-if="!readOnlyCurrentPatient && !locked" class="extraction-actions"><span class="extraction-route-note">{{ currentAiRouteDescription }} · 信息提取</span><div class="extraction-action-buttons"><button v-if="extraction?.status !== 'GENERATED' && !extractionConfirmed" class="btn soft" :disabled="extractionRunning || !allTranscribed || !!unclassifiedRoleCount" @click="generateClinicalExtraction"><Icon name="sparkle" />{{ extractionRunning ? '正在提取' : extraction?.status === 'STALE' ? '重新提取' : extraction?.status === 'FAILED' ? '重试提取' : '生成信息提取' }}</button><button v-else-if="extraction?.status === 'GENERATED'" class="btn primary" :disabled="actionBusy === 'confirm-extraction'" @click="confirmClinicalExtraction"><Icon name="check" />{{ actionBusy === 'confirm-extraction' ? '正在确认' : '整体确认提取结果' }}</button></div></div>
          </div>
          <div class="transcript-actions"><span class="small-muted">{{ transcriptDraft.length }} 字 · {{ recordings.filter(item => item.status === 'DONE').length }} 段录音</span>
            <button v-if="!readOnlyCurrentPatient && transcriptTab === 'edit'" class="btn small soft" :disabled="locked" @click="saveTranscript"><Icon name="save" />保存转写</button>
            <button v-else-if="!readOnlyCurrentPatient" class="btn small soft" :disabled="locked || !allTranscribed" @click="generateRecord"><Icon name="sparkle" />生成病历</button>
          </div>
        </section>
      </div>

      <div v-else-if="view === 'record'" class="full-view">
        <section class="card record-card">
          <div class="card-head"><h2><Icon name="file" />门诊病历 <span v-if="record?.record_id" class="badge" :class="confirmed ? 'teal' : 'amber'">{{ confirmed ? '已签署' : '待签署' }}</span></h2>
            <div class="record-header-actions"><span v-if="record?.record_id" class="record-version">v{{ record.version_no }}.0</span>
              <button v-if="!readOnlyCurrentPatient && record?.record_id && !confirmed && !closed" class="btn small" :disabled="busy || !allTranscribed" @click="modal='regenerate'"><Icon name="refresh" />重新生成</button>
            </div>
          </div>
          <div class="record-subbar"><span class="template-label"><Icon name="list" />标准门诊病历模板</span><span>{{ doctor.department_name }} · v0.1</span></div>
           <div v-if="!record?.record_id" class="empty-state"><div class="empty-icon"><Icon name="file" /></div><h3>让对话成为清晰的病历</h3><p>完成全部录音转写后，按标准模板生成一份病历草稿。</p><button v-if="!readOnlyCurrentPatient" class="btn primary" :disabled="!allTranscribed || busy || closed" @click="generateRecord"><Icon name="sparkle" />生成病历草稿</button><span v-else class="small-muted">该接诊尚未生成病历</span></div>
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

      <div v-else-if="view === 'export'" class="full-view export-view" :class="{ 'department-readonly-export': readOnlyCurrentPatient }">
        <div class="info-banner"><Icon :name="confirmed ? 'shield' : 'lock'" />
          <span>{{ confirmed ? `当前可导出版本：v${record?.version_no}.0 · 签署医生：${record?.confirmed_by_name || doctor.display_name} · 签署时间：${formatDateTime(record?.confirmed_at)}` : '请先完成医生签署，签署后的版本才可以导出。' }}</span>
        </div>
        <section class="card export-preview-card">
          <div class="card-head"><h2><Icon :name="readOnlyCurrentPatient ? 'eye' : 'download'" />{{ readOnlyCurrentPatient ? '病历预览' : '选择导出格式' }}</h2><span class="small-muted">{{ record?.record_id ? `病历编号 MR-${currentVisit?.visit_no}` : '等待生成病历' }}</span></div>
          <div class="template-picker">
            <label for="export-template">{{ readOnlyCurrentPatient ? '预览模板' : '导出模板' }}</label>
            <select id="export-template" v-model="selectedTemplateRevisionId" :disabled="!confirmed || busy || previewBusy || !exportTemplates.length">
              <option v-for="t in exportTemplates" :key="t.current_revision_id" :value="t.current_revision_id">{{ t.name }}（v{{ t.current_revision_no }}）· {{ t.description }}</option>
            </select>
            <button class="btn" :disabled="!confirmed || !selectedTemplate || previewBusy || busy" @click="showPreview"><Icon name="eye" />实时预览</button>
          </div>
          <div v-if="!readOnlyCurrentPatient" class="export-options">
            <div class="export-option"><Icon name="file" /><h3>Word 文档</h3><p>统一 A4 版式、章节编号与字段标签。<br>生成 .docx 并自动归档下载。</p><button class="btn primary" :disabled="!confirmed || busy" @click="exportWord"><Icon name="download" />导出 Word</button></div>
            <div class="export-option"><Icon name="file" /><h3>PDF 文档</h3><p>统一 A4 版式、表格与页码。<br>生成 PDF 并自动归档下载。</p><button class="btn" :disabled="!confirmed || busy" @click="exportPdf"><Icon name="download" />导出 PDF</button></div>
          </div>
          <div v-if="!readOnlyCurrentPatient" class="readonly-note">导出任务完成后才允许下载；只有当前确认版本可以导出。</div>
        </section>

        <section class="card export-history-card">
          <div class="card-head"><h2><Icon name="clock" />导出记录</h2><span class="small-muted">当前接诊 {{ currentVisit?.visit_no || '—' }}</span></div>
          <div class="table-wrap"><table class="log-table"><thead><tr><th>病历版本</th><th>模板</th><th>格式 / 状态</th><th>操作时间</th><th>操作</th></tr></thead><tbody>
            <tr v-if="!exports.length"><td colspan="5">暂无导出记录</td></tr>
            <tr v-for="item in exports.slice().reverse()" :key="item.id"><td>v{{ item.version_no }}.0</td><td>{{ item.template_name }} · v{{ item.template_revision_no }}</td><td>{{ item.format }} · {{ exportStatusLabel(item.status) }}</td><td>{{ formatDateTime(item.created_at) }}</td><td><button v-if="item.status === 'SUCCEEDED'" class="btn small" :disabled="busy" :aria-label="`下载 v${item.version_no}.0 ${item.format}`" @click="downloadHistoricalExport(item)"><Icon name="download" />下载</button><span v-else class="small-muted">—</span></td></tr>
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
            <label>操作类型<select v-model="auditAction"><option value="">全部类型</option><option value="LOGIN">登录系统</option><option value="VISIT_DETAIL_VIEWED">查看接诊详情</option><option value="RECORDING_UPLOADED">上传录音</option><option value="RECORDING_DELETED">删除录音</option><option value="MEDICAL_RECORD_CONFIRMED">确认病历</option><option value="MEDICAL_RECORD_EXPORT">病历导出</option><option value="PATIENT_DELETED">删除患者</option></select></label>
            <button class="btn primary audit-search" :disabled="auditBusy" @click="loadAudit(1)"><Icon name="search" />查询</button>
          </div>
        </section>
        <section class="card audit-table-card">
          <div class="card-head"><h2><Icon name="list" />操作记录</h2><span class="small-muted">共 {{ auditTotal }} 条</span></div>
          <div class="table-wrap"><table class="log-table audit-log-table"><thead><tr><th>时间</th><th>操作人</th><th>操作类型</th><th>患者 / 接诊</th><th>操作详情</th><th>结果</th><th>IP</th></tr></thead><tbody>
            <tr v-if="auditBusy"><td colspan="7">正在加载日志…</td></tr>
            <tr v-else-if="!auditLogs.length"><td colspan="7">暂无符合条件的日志记录</td></tr>
            <tr v-for="item in auditLogs" :key="item.id"><td>{{ formatDateTime(item.created_at) }}</td><td>{{ item.operator_name || '系统' }}</td><td>{{ auditActionLabel(item.action) }}</td><td><template v-if="item.visit_no || item.patient_name">{{ item.patient_name || '未记录患者' }}<br><span class="small-muted">{{ item.patient_no || '—' }} · {{ item.visit_no || '—' }}</span></template><template v-else-if="item.patient_no">患者编号 {{ item.patient_no }}</template><template v-else>—</template></td><td>{{ item.detail || '—' }}</td><td><span class="badge" :class="item.result === 'SUCCESS' ? 'teal' : 'red'">{{ auditResultLabel(item.result) }}</span></td><td>{{ item.client_ip || '—' }}</td></tr>
          </tbody></table></div>
          <div class="audit-pagination"><span>第 {{ auditPage }} 页</span><div><button class="btn small" :disabled="auditBusy || auditPage <= 1" @click="loadAudit(auditPage - 1)">上一页</button><button class="btn small" :disabled="auditBusy || auditPage * 20 >= auditTotal" @click="loadAudit(auditPage + 1)">下一页</button></div></div>
        </section>
      </div>
    </main>

    <footer v-if="view !== 'audit' && view !== 'templates' && view !== 'settings' && !readOnlyCurrentPatient" class="bottom-bar">
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
          <h2 :id="`modal-title-${modal}`">{{ ({ 'new-patient':'新增患者','cancel':'取消本次接诊','finish':'结束本次接诊','regenerate':'重新生成当前病历','confirm':'确认并签署病历','supplemental-transcription':'重新转写确认','retranscribe-recording':'重新转写录音','help':'使用帮助','activity':'当前接诊动态','delete-recording':'删除录音','delete-patient':'删除患者及业务数据' })[modal] }}</h2>
          <button class="icon-btn" aria-label="关闭对话框" @click="modal=null"><Icon name="x" /></button>
        </div>
        <div class="modal-body">
          <template v-if="modal === 'new-patient'">
            <div class="manual-patient-grid">
              <div class="modal-field"><label for="manual-patient-name">患者姓名 <span class="required">*</span></label><input id="manual-patient-name" v-model="newPatientForm.name" maxlength="128" placeholder="请输入真实姓名" /></div>
              <div class="modal-field"><label for="manual-patient-gender">性别 <span class="required">*</span></label><select id="manual-patient-gender" v-model="newPatientForm.gender"><option value="">请选择</option><option value="男">男</option><option value="女">女</option></select></div>
              <div class="modal-field"><label for="manual-patient-age">年龄</label><input id="manual-patient-age" v-model.number="newPatientForm.age" type="number" min="0" max="150" step="1" placeholder="选填" /></div>
              <div class="modal-field" :class="{ invalid: newPatientErrors.phone }">
                <label for="manual-patient-phone">联系方式 <span class="required">*</span></label>
                <input id="manual-patient-phone" v-model="newPatientForm.phone" type="tel" inputmode="numeric" maxlength="11"
                  placeholder="请输入11位手机号，保存后脱敏显示" @input="newPatientForm.phone = newPatientForm.phone.replace(/\D/g, '').slice(0, 11); clearNewPatientError('phone')" @blur="validateNewPatientField('phone')" />
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
          <template v-else-if="modal === 'supplemental-transcription'"><p>将把补传录音与已转写录音合并，重新生成本次接诊的完整转写文本。</p><p>当前手工编辑的转写文本和病历草稿将失效，需在新转写完成后重新生成并核对病历。</p></template>
          <template v-else-if="modal === 'retranscribe-recording'"><p>将保留原录音文件，使用本次接诊已固定的 <b>{{ currentAiRouteLabel }} AI</b> 重新转写 <b>{{ recordingToRetranscribe?.file_name }}</b>。</p><p>当前手工编辑的转写文本、信息提取和病历草稿将失效，转写完成后必须重新核对并生成病历。</p><div class="modal-note">转写、角色识别和信息提取统一使用该接诊环境；如需调整，只对尚未首次转写的新接诊生效。</div><div v-if="modalError" class="modal-error">{{ modalError }}</div></template>
          <template v-else-if="modal === 'confirm'">
            <p>患者 <b>{{ recordForm?.name }}</b> · 接诊 <b>{{ currentVisit?.visit_no }}</b> · 病历 <b>v{{ record?.version_no }}.0</b></p>
            <p>签署后将记录签署医生、时间及版本，并锁定当前病历；后续修改会创建新修订版并需重新签署。</p>
            <label class="confirm-check"><input v-model="confirmChecked" type="checkbox" /><span>我已核对病历内容，确认当前记录准确反映本次接诊情况。</span></label>
            <div class="modal-error">{{ modalError }}</div>
          </template>
          <template v-else-if="modal === 'delete-recording'"><p>确定删除录音 <b>{{ recordingToDelete?.file_name }}</b> 吗？</p><p>删除后录音对象和相关转写链路会物理清理，且不会写入删除审计，可重新上传正确录音。</p><p>当前手工编辑的转写文本、信息提取和病历草稿会失效，需重新转写、核对并生成病历。</p><div class="modal-note">该操作只允许在接诊进行中执行；已完成接诊不能删除录音。</div><div v-if="modalError" class="modal-error">{{ modalError }}</div></template>
          <template v-else-if="modal === 'delete-patient'"><p>即将删除患者 <b>{{ currentPatient?.name }}</b>（患者编号 <b>{{ currentPatient?.patient_no }}</b>，{{ currentPatient?.gender || '性别未记录' }}，{{ currentPatient?.age ?? '年龄未记录' }} 岁），共包含 <b>{{ currentPatientVisitCount }}</b> 条接诊记录。</p><p>患者和所有接诊业务数据将立即物理删除，包含录音、转写、信息提取、病历、导出记录及其业务文件；既有审计日志和此次删除记录会保留。</p><div class="modal-note">此操作无法撤销。外部文件暂时无法删除时，后台会继续重试。</div><label class="confirm-check"><input v-model="deletePatientChecked" type="checkbox" /><span>我已核对患者身份和接诊数量，确认永久删除该患者的全部业务数据。</span></label><div v-if="modalError" class="modal-error">{{ modalError }}</div></template>
          <template v-else-if="modal === 'help'"><p><b>完整流程</b><br>开始接诊 → 上传录音 → 开始转写 → 生成病历 → 审核并签署病历 → 后端生成并下载 Word / PDF → 结束接诊。</p><p>非必填字段未提及则留空；诊疗记录仅供医生补充。</p></template>
          <template v-else><p v-if="!activity.length">接诊开始后将在这里记录业务操作。</p><p v-for="item in activity.slice(0, 12)" :key="item.time"><span class="small-muted">{{ item.time }}</span><br>{{ item.text }}</p></template>
        </div>
        <div class="modal-actions">
          <template v-if="modal === 'new-patient'"><button class="btn" @click="modal=null">取消</button><button class="btn primary" :disabled="busy" @click="createNewPatient"><Icon name="save" />保存患者</button></template>
          <template v-else-if="modal === 'cancel'"><button class="btn" @click="modal=null">返回</button><button class="btn danger" :disabled="busy" @click="doCancel">确认取消接诊</button></template>
          <template v-else-if="modal === 'finish'"><button class="btn" @click="modal=null">返回</button><button class="btn primary" :disabled="busy" @click="doFinish"><Icon name="check" />完成本次接诊</button></template>
          <template v-else-if="modal === 'regenerate'"><button class="btn" @click="modal=null">返回</button><button class="btn primary" :disabled="busy" @click="modal=null;generateRecord()"><Icon name="refresh" />重新生成</button></template>
          <template v-else-if="modal === 'supplemental-transcription'"><button class="btn" @click="modal=null">返回检查</button><button class="btn primary" aria-label="确认重新转写" :disabled="locked" @click="modal=null;startTranscription()"><Icon name="refresh" />继续转写</button></template>
          <template v-else-if="modal === 'retranscribe-recording'"><button class="btn" @click="modal=null">返回检查</button><button class="btn primary" aria-label="确认重新转写录音" :disabled="locked" @click="retranscribeSelectedRecording"><Icon name="refresh" />确认重新转写</button></template>
          <template v-else-if="modal === 'confirm'"><button class="btn" @click="modal=null">返回核对</button><button class="btn primary" :disabled="busy || actionBusy === 'confirm'" @click="doConfirm"><Icon name="shield" />确认签署</button></template>
          <template v-else-if="modal === 'delete-recording'"><button class="btn" @click="modal=null">返回</button><button class="btn danger" :disabled="actionBusy === 'delete'" @click="deleteSelectedRecording"><Icon name="trash" />确认删除</button></template>
          <template v-else-if="modal === 'delete-patient'"><button class="btn" @click="modal=null; deletePatientChecked=false">取消</button><button class="btn danger" :disabled="!deletePatientChecked || actionBusy === 'delete-patient'" @click="deleteSelectedPatient"><Icon name="trash" />永久删除患者</button></template>
          <template v-else><button class="btn primary" @click="modal=null"><Icon name="check" />开始使用</button></template>
        </div>
      </div>
    </div>
    <div v-if="previewOpen" class="preview-backdrop" role="presentation" @click.self="closePreview" @keydown.esc="closePreview">
      <div class="preview-dialog" role="dialog" aria-modal="true" aria-label="导出模板预览">
        <div class="preview-head">
          <h2><Icon name="eye" />模板预览 · {{ selectedTemplate?.name || '导出模板' }}（v{{ selectedTemplate?.current_revision_no || '—' }}）</h2>
          <button class="icon-btn" aria-label="关闭预览" @click="closePreview"><Icon name="x" /></button>
        </div>
        <iframe v-if="previewUrl" :src="previewUrl" class="preview-frame" title="PDF 预览"></iframe>
      </div>
    </div>
  </div>
</template>

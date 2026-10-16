<script setup lang="ts">
import Icon from './Icon.vue'
import type { AsrPreviewRecording } from '../types'

const props = defineProps<{
  previews: AsrPreviewRecording[]
  error?: string
  showViewAction?: boolean
}>()

const emit = defineEmits<{
  (event: 'view-transcript'): void
}>()

const formatTime = (milliseconds: number) => {
  const ms = Number.isFinite(milliseconds) ? Math.max(0, milliseconds) : 0
  return `${String(Math.floor(ms / 60000)).padStart(2, '0')}:${String(Math.floor((ms % 60000) / 1000)).padStart(2, '0')}`
}

const segmentsFor = (preview: AsrPreviewRecording) => Array.isArray(preview.segments) ? preview.segments : []

const statusLabel = (preview: AsrPreviewRecording) => {
  const hasSegments = segmentsFor(preview).length > 0
  if (preview.status === 'UPLOADED') return '待转写'
  if (preview.status === 'PROCESSING') return hasSegments ? '角色判断中' : 'ASR 识别中'
  if (preview.status === 'DONE') return hasSegments ? '已完成' : '暂无 ASR 原文预览'
  if (preview.status === 'FAILED') return hasSegments ? 'ASR 原文已保留 · 后续处理失败' : '处理失败'
  return preview.status || ''
}

const statusClass = (status?: string | null) => ({
  DONE: 'teal', FAILED: 'red', PROCESSING: 'amber', UPLOADED: 'amber'
}[status || ''] || '')

const emptyMessage = (preview: AsrPreviewRecording) => {
  if (preview.status === 'UPLOADED') return '开始转写后，ASR 原文会显示在这里。'
  if (preview.status === 'PROCESSING') return 'ASR 正在识别录音，原文生成后会在这里显示。'
  if (preview.status === 'DONE') return '该历史录音没有可展示的 ASR 原文预览。'
  if (preview.status === 'FAILED') return '处理失败，当前没有可展示的 ASR 原文。'
  return '当前没有可展示的 ASR 原文。'
}
</script>

<template>
  <section class="card asr-preview-card" aria-label="ASR 原文预览">
    <div class="card-head">
      <h2><Icon name="text" />ASR 原文预览</h2>
      <span class="small-muted">{{ props.previews.length ? `${props.previews.length} 段录音` : '等待识别' }}</span>
    </div>
    <div class="asr-preview-body">
      <div class="transcript-note"><Icon name="info" />这里只展示 ASR 原始文字；角色判断完成后的正式结果仍在“转写结果”页。</div>
      <p v-if="props.error" class="issue-hint asr-preview-error" role="status">ASR 原文预览暂时无法加载：{{ props.error }}</p>
      <div v-if="!props.previews.length && !props.error" class="asr-preview-empty">
        <Icon name="text" />
        <span>完成录音转写后，ASR 原文将在此显示</span>
      </div>
      <article v-for="preview in props.previews" :key="preview.recording_id" class="asr-preview-recording">
        <div class="asr-preview-recording-head">
          <div>
            <strong>{{ preview.file_name || preview.recording_no || preview.recording_id }}</strong>
            <span v-if="preview.recording_no" class="small-muted">录音 {{ preview.recording_no }}</span>
          </div>
          <span v-if="preview.status" class="badge" :class="statusClass(preview.status)">{{ statusLabel(preview) }}</span>
        </div>
        <p v-if="preview.error_message" class="issue-hint">{{ preview.error_message }}</p>
        <div v-if="segmentsFor(preview).length" class="asr-preview-segments">
          <div v-for="(segment, index) in segmentsFor(preview)" :key="segment.utterance_id || `${preview.recording_id}-${segment.start_ms}-${index}`" class="asr-preview-segment">
            <time>{{ formatTime(segment.start_ms) }}</time>
            <p>{{ segment.text }}</p>
          </div>
        </div>
        <p v-else class="asr-preview-pending">{{ emptyMessage(preview) }}</p>
      </article>
    </div>
    <div v-if="props.showViewAction" class="asr-preview-actions">
      <span>角色判断完成后的正式结果在转写结果页查看</span>
      <button type="button" class="btn primary audio-view-result-btn" @click="emit('view-transcript')">
        <Icon name="text" />查看转写结果
      </button>
    </div>
  </section>
</template>

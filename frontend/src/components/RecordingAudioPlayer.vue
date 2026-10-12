<script setup lang="ts">
import { computed, type ComponentPublicInstance } from 'vue'
import type { Recording } from '../types'
import Icon from './Icon.vue'

const props = defineProps<{
  recording: Recording
  active: boolean
  loading: boolean
  loaded: boolean
  playing: boolean
  ended: boolean
  src: string
  currentTime: number
  actualDuration: number
  volume: number
  playbackRate: number
  error: string
  autoplayBlocked: boolean
}>()

const emit = defineEmits<{
  toggle: []
  retry: []
  seek: [time: number]
  volume: [value: number]
  rate: [value: number]
  audioElement: [element: HTMLAudioElement | null]
  timeUpdate: [currentTime: number, duration: number]
  loadedMetadata: [duration: number]
  playing: []
  paused: []
  ended: []
  mediaError: []
}>()

const fallbackDuration = computed(() => Math.max(0, props.recording.duration_ms || 0) / 1000)
const duration = computed(() => props.actualDuration > 0 ? props.actualDuration : fallbackDuration.value)
const progress = computed(() => duration.value > 0
  ? Math.min(100, Math.max(0, props.currentTime / duration.value * 100))
  : 0)
const playLabel = computed(() => {
  if (props.loading) return `正在加载 ${props.recording.file_name}`
  if (props.ended) return `重新播放 ${props.recording.file_name}`
  return `${props.playing ? '暂停' : '播放'} ${props.recording.file_name}`
})

function formatTime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00'
  const wholeSeconds = Math.floor(seconds)
  return `${String(Math.floor(wholeSeconds / 60)).padStart(2, '0')}:${String(wholeSeconds % 60).padStart(2, '0')}`
}

function setAudioElement(element: Element | ComponentPublicInstance | null) {
  emit('audioElement', element instanceof HTMLAudioElement ? element : null)
}

function onTimeUpdate(event: Event) {
  const audio = event.currentTarget as HTMLAudioElement
  emit('timeUpdate', audio.currentTime, audio.duration)
}

function onLoadedMetadata(event: Event) {
  const audio = event.currentTarget as HTMLAudioElement
  emit('loadedMetadata', audio.duration)
}

function onSeek(event: Event) {
  emit('seek', Number((event.currentTarget as HTMLInputElement).value))
}

function onVolume(event: Event) {
  emit('volume', Number((event.currentTarget as HTMLInputElement).value))
}

function onRate(event: Event) {
  emit('rate', Number((event.currentTarget as HTMLSelectElement).value))
}
</script>

<template>
  <div class="audio-player" :class="{ 'is-loading': loading, 'is-playing': playing, 'has-error': !!error }">
    <audio
      v-if="active && src"
      :ref="setAudioElement"
      :key="recording.id"
      class="audio-engine"
      :src="src"
      preload="auto"
      aria-hidden="true"
      tabindex="-1"
      @timeupdate="onTimeUpdate"
      @loadedmetadata="onLoadedMetadata"
      @durationchange="onLoadedMetadata"
      @play="emit('playing')"
      @pause="emit('paused')"
      @ended="emit('ended')"
      @error="emit('mediaError')"
    ></audio>

    <button
      class="play-btn"
      type="button"
      :aria-label="playLabel"
      :aria-busy="loading"
      :disabled="loading"
      @click="emit('toggle')"
    >
      <span v-if="loading" class="spinner audio-loading-spinner" aria-hidden="true"></span>
      <Icon v-else :name="playing ? 'pause' : ended ? 'replay' : 'play'" />
    </button>

    <div class="audio-player-main">
      <div class="audio-progress-row">
        <input
          class="audio-progress"
          type="range"
          min="0"
          :max="duration || 1"
          step="0.1"
          :value="Math.min(currentTime, duration || 0)"
          :style="{ '--audio-progress': `${progress}%` }"
          :aria-label="`播放进度：${recording.file_name}`"
          :aria-valuetext="`${formatTime(currentTime)} / ${formatTime(duration)}`"
          :disabled="!loaded || !duration || !!error"
          @input="onSeek"
        />
        <span class="audio-time" aria-live="off">{{ formatTime(currentTime) }} / {{ formatTime(duration) }}</span>
      </div>
      <p v-if="loading" class="audio-status" role="status" aria-live="polite">正在加载音频…</p>
      <p v-else-if="autoplayBlocked && loaded" class="audio-status" role="status">音频已载入，点击播放开始</p>
      <div v-else-if="error" class="audio-error" role="alert">
        <span>{{ error }}</span>
        <button type="button" class="audio-retry" @click="emit('retry')">重试</button>
      </div>
    </div>

    <div class="audio-player-options">
      <label class="audio-volume-control">
        <span>音量</span>
        <input
          type="range"
          min="0"
          max="1"
          step="0.05"
          :value="volume"
          :style="{ '--audio-volume': `${volume * 100}%` }"
          :aria-label="`音量：${recording.file_name}`"
          :disabled="!loaded"
          @input="onVolume"
        />
        <span class="audio-volume-value">{{ Math.round(volume * 100) }}%</span>
      </label>
      <label class="audio-rate-control">
        <span>速度</span>
        <select :value="playbackRate" :aria-label="`播放速度：${recording.file_name}`" :disabled="!loaded" @change="onRate">
          <option :value="1">1×</option>
          <option :value="1.25">1.25×</option>
          <option :value="1.5">1.5×</option>
          <option :value="2">2×</option>
        </select>
      </label>
    </div>
  </div>
</template>

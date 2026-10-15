<script setup lang="ts">
import { computed } from 'vue'
import Icon from './Icon.vue'
import type { ClinicalExtraction } from '../types'

const props = defineProps<{
  extraction: ClinicalExtraction | null
  snapshotId: string | null
  snapshotHash: string | null
  roleReviewCount: number
  roleAnalysisPendingCount?: number
  allTranscribed: boolean
  readOnly: boolean
  busy: boolean
  error: string
}>()
const emit = defineEmits<{ retry: []; review: []; details: []; confirm: [] }>()
const matches = computed(() => !!props.snapshotId && !!props.snapshotHash
  && props.extraction?.snapshot_id === props.snapshotId && props.extraction?.snapshot_hash === props.snapshotHash)
const roleAnalysisPendingCount = computed(() => props.roleAnalysisPendingCount || 0)
const pending = computed(() => props.allTranscribed
  && (props.busy || (matches.value && ['QUEUED', 'RUNNING'].includes(props.extraction?.status || ''))))
const available = computed(() => props.allTranscribed && matches.value
  && ['GENERATED', 'CONFIRMED'].includes(props.extraction?.status || ''))
const status = computed(() => {
  if (!props.allTranscribed) return '等待转写完成'
  if (pending.value) return '正在提取主诉与病情信息'
  if (roleAnalysisPendingCount.value) return '角色判断中'
  if (matches.value && props.extraction?.status === 'FAILED') return props.roleReviewCount ? '待角色核对' : '提取失败'
  if (available.value && props.roleReviewCount) return `信息提取已完成，仍有 ${props.roleReviewCount} 条角色待核对`
  if (available.value) return props.extraction?.status === 'CONFIRMED' ? '已确认' : '已生成（待确认）'
  if (props.roleReviewCount) return '信息提取已完成'
  if (matches.value && props.extraction?.status === 'FAILED') return '提取失败'
  return '待生成'
})
const field = (key: string) => available.value ? props.extraction?.fields[key]?.value?.trim() || '' : ''
const present = computed(() => ['onset_course', 'symptom_characteristics', 'associated_symptoms']
  .map(field).filter(Boolean).map(value => value.replace(/[；;。]+$/u, '')).join('；'))
const additional = computed(() => [
  ['past_medical_history', '既往史'], ['medication_history', '用药史'], ['allergy_history', '过敏史'],
  ['family_history', '家族史'], ['social_history', '生活史']
].map(([key, label]) => ({ key, label, value: field(key) })).filter(item => item.value))
const issues = computed(() => matches.value && !pending.value && !available.value ? props.extraction?.quality_issues || [] : [])
const canRetry = computed(() => props.allTranscribed && !roleAnalysisPendingCount.value && !pending.value && !available.value && !props.readOnly)
</script>

<template>
  <aside class="clinical-summary" aria-labelledby="clinical-summary-title" :aria-busy="pending">
    <header class="clinical-summary-head">
      <h3 id="clinical-summary-title">主诉与病情描述</h3>
      <span class="clinical-summary-status" :class="{ ready: available && !roleReviewCount, warning: roleAnalysisPendingCount || roleReviewCount || (matches && extraction?.status === 'FAILED') }" role="status">{{ status }}</span>
    </header>
    <div class="clinical-summary-content">
      <div v-if="pending" class="clinical-summary-hint" role="status"><Icon name="sparkle" />正在提取主诉与病情信息</div>
      <div v-else-if="roleAnalysisPendingCount" class="clinical-summary-hint">角色判断尚未完成，完成后将自动开始提取。</div>
      <div v-else-if="roleReviewCount" class="clinical-summary-hint"><span v-if="available" class="badge teal">信息提取已完成</span>{{ available ? '仍有' : '还有' }} {{ roleReviewCount }} 条角色待核对，不影响提取确认。</div>
      <div v-else-if="available" class="clinical-summary-hint"><span class="badge teal">已自动更新</span>根据当前完整对话提取</div>
      <div v-else class="clinical-summary-hint">{{ allTranscribed ? '角色判断完成后自动整理本次对话。' : '转写和角色判断完成后，将在此显示提取结果。' }}</div>
      <section class="clinical-summary-section">
        <h4>主诉</h4>
        <p class="clinical-summary-chief" :class="{ placeholder: !field('chief_complaint') }">{{ field('chief_complaint') || (pending ? '正在提取…' : '待提取') }}</p>
      </section>
      <section class="clinical-summary-section">
        <h4>现病史</h4>
        <p :class="{ placeholder: !present }">{{ present || (available ? '本次对话未提及明确病程及症状细节，待补充。' : '等待信息提取结果。') }}</p>
      </section>
      <section class="clinical-summary-section clinical-summary-additional">
        <h4>补充信息</h4>
        <dl v-if="additional.length"><div v-for="item in additional" :key="item.key"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div></dl>
        <p v-else class="placeholder">{{ available ? '本次对话未提及，待补充。' : '等待信息提取结果。' }}</p>
      </section>
      <div v-if="error || issues.length" class="clinical-summary-error" role="alert"><p v-if="error">{{ error }}</p><p v-for="issue in issues" :key="issue">{{ issue }}</p></div>
    </div>
    <footer class="clinical-summary-actions">
      <button v-if="roleReviewCount && !readOnly" class="btn small soft" type="button" @click="emit('review')">核对角色</button>
      <button v-if="canRetry" class="btn small soft" type="button" @click="emit('retry')"><Icon name="refresh" />{{ extraction?.status === 'FAILED' ? '重试提取' : extraction?.status === 'STALE' ? '重新提取' : '开始提取' }}</button>
      <button v-if="available" class="btn small soft" type="button" @click="emit('details')">查看原文证据</button>
      <button v-if="available && extraction?.status === 'GENERATED' && !readOnly" class="btn small primary" type="button" :disabled="busy" @click="emit('confirm')">确认提取结果</button>
    </footer>
  </aside>
</template>

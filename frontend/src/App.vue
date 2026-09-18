<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import ClinicalWorkbench from './components/ClinicalWorkbench.vue'
import Icon from './components/Icon.vue'
import { api } from './api'
import { showMessage, type MessageDetail, type MessageType } from './message'
import type { DemoRole, Doctor } from './types'

const doctor = ref<Doctor | null>(null)
const name = ref('')
const role = ref<DemoRole>('DOCTOR')
const busy = ref(false)
const messageText = ref('')
const messageType = ref<MessageType>('info')
const messageVisible = ref(false)
let messageTimer: number | undefined
const loggedIn = computed(() => !!doctor.value)

function handleMessage(event: Event) {
  const detail = (event as CustomEvent<MessageDetail>).detail
  if (!detail?.text) return
  messageText.value = detail.text
  messageType.value = detail.type || 'info'
  messageVisible.value = true
  window.clearTimeout(messageTimer)
  messageTimer = window.setTimeout(() => { messageVisible.value = false }, 3400)
}

async function login() {
  if (!name.value.trim()) {
    showMessage('请输入医生名称', 'error')
    return
  }

  busy.value = true
  try {
    const result = await api.login(name.value, role.value)
    doctor.value = result.doctor
    showMessage(`欢迎，${result.doctor.display_name}`, 'success')
  }
  catch (e: any) {
    showMessage(e instanceof Error ? e.message : '登录失败，请重试', 'error')
  }
  finally {
    busy.value = false
  }
}

async function logout() {
  try {
    await api.logout()
    showMessage('已安全退出登录', 'success')
  }
  catch (e) {
    showMessage(e instanceof Error ? e.message : '退出失败，请重试', 'error')
  }
  finally {
    doctor.value = null
  }
}

function sessionExpired() {
  doctor.value = null
  showMessage('登录状态已失效，请重新登录', 'error')
}

onMounted(async () => {
  window.addEventListener('medicalai:message', handleMessage)
  window.addEventListener('medicalai:unauthorized', sessionExpired)
  if (!localStorage.getItem('medicalai_token')) return

  try {
    doctor.value = await api.me()
  }
  catch (e) {
    showMessage(e instanceof Error ? e.message : '登录恢复失败', 'error')
  }
})

onUnmounted(() => {
  window.removeEventListener('medicalai:message', handleMessage)
  window.removeEventListener('medicalai:unauthorized', sessionExpired)
  window.clearTimeout(messageTimer)
})
</script>

<template>
  <main v-if="!loggedIn" class="login-shell">
    <section class="brand-panel">
      <div class="brand-mark"><Icon name="pulse" /></div>
      <h1>听诊助手</h1>
      <p>让记录回归简单</p>
      <small>CLINICAL COPILOT</small>
    </section>
    <section class="login-card">
      <span class="eyebrow">医生工作台 · 演示登录</span>
      <h2>欢迎回来</h2>
      <p class="muted">输入医生名称进入接诊工作台</p>
      <div class="login-role" role="group" aria-label="选择模拟登录身份">
        <button type="button" :class="{ active: role === 'DOCTOR' }" @click="role = 'DOCTOR'">医生</button>
        <button type="button" :class="{ active: role === 'DEPARTMENT_HEAD' }" @click="role = 'DEPARTMENT_HEAD'">科室长</button>
      </div>
      <input v-model="name" autofocus placeholder="请输入医生名称" @keyup.enter="login">
      <div class="quick">
        <!-- <button v-for="n in ['李明', '王医生', '张医生']" :key="n" @click="name = n">{{ n }}</button> -->
      </div>
      <button class="primary" :disabled="busy" @click="login">{{ busy ? '正在登录…' : '进入工作台' }}</button>
      <p class="notice">当前为演示登录，后续可切换 OA 单点登录。</p>
    </section>
  </main>
  <ClinicalWorkbench v-else-if="doctor" :doctor="doctor" @logout="logout" />
  <div v-if="messageVisible" class="global-message" :class="`global-message-${messageType}`" role="status" aria-live="polite">
    <span class="global-message-mark" aria-hidden="true"></span>{{ messageText }}
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import ClinicalWorkbench from './components/ClinicalWorkbench.vue'
import Icon from './components/Icon.vue'
import { api } from './api'
import type { Doctor } from './types'

const doctor = ref<Doctor | null>(null)
const name = ref('')
const error = ref('')
const busy = ref(false)
const loggedIn = computed(() => !!doctor.value)

async function login() {
  if (!name.value.trim()) {
    error.value = '请输入医生名称'
    return
  }

  busy.value = true
  error.value = ''
  try {
    const result = await api.login(name.value)
    doctor.value = result.doctor
  }
  catch (e: any) {
    error.value = e.message
  }
  finally {
    busy.value = false
  }
}

async function logout() {
  try {
    await api.logout()
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '退出失败，请重试'
  }
  finally {
    doctor.value = null
  }
}

function sessionExpired() {
  doctor.value = null
}

onMounted(async () => {
  window.addEventListener('medicalai:unauthorized', sessionExpired)
  if (!localStorage.getItem('medicalai_token')) return

  try {
    doctor.value = await api.me()
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '登录恢复失败'
  }
})

onUnmounted(() => {
  window.removeEventListener('medicalai:unauthorized', sessionExpired)
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
      <input v-model="name" autofocus placeholder="请输入医生名称" @keyup.enter="login">
      <div class="quick">
        <button v-for="n in ['李明', '王医生', '张医生']" :key="n" @click="name = n">{{ n }}</button>
      </div>
      <button class="primary" :disabled="busy" @click="login">{{ busy ? '正在登录…' : '进入工作台' }}</button>
      <p v-if="error" class="error">{{ error }}</p>
      <p class="notice">当前为演示登录，后续可切换 OA 单点登录。</p>
    </section>
  </main>
  <ClinicalWorkbench v-else-if="doctor" :doctor="doctor" @logout="logout" />
</template>

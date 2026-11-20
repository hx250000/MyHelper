<script setup>
import { ref } from 'vue'
import { api } from '../api/client'
import { validateRegistration } from '../lib/workspace'
import AppIcon from './AppIcon.vue'

const emit = defineEmits(['connect', 'demo'])
const dialog = ref(null)
const tab = ref('connect')
const userId = ref('')
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const registered = ref(null)

function open() {
  error.value = ''
  password.value = ''
  dialog.value.showModal()
}

function close() {
  if (!busy.value) dialog.value.close()
}

async function submit() {
  if (busy.value) return
  error.value = ''
  if (tab.value === 'register') {
    error.value = validateRegistration(username.value, password.value)
  } else if (!userId.value.trim() || userId.value.trim().length > 16) {
    error.value = '请输入有效的用户 ID（最多 16 个字符）。'
  }
  if (error.value) return
  busy.value = true
  try {
    if (tab.value === 'register') {
      const user = await api.register(username.value.trim(), password.value)
      registered.value = user
      userId.value = user.id
      password.value = ''
      tab.value = 'connect'
    }
    const result = await api.conversations(userId.value.trim())
    emit('connect', { user: { id: userId.value.trim(), username: result.username }, conversations: result.conversations })
    dialog.value.close()
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
    password.value = ''
  }
}

function useDemo() {
  emit('demo')
  close()
}

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="account-dialog" aria-labelledby="account-title" @cancel="busy && $event.preventDefault()" @click="!busy && $event.target === dialog && close()">
    <div class="dialog-body">
      <button class="icon-button dialog-close" aria-label="关闭" :disabled="busy" @click="close"><AppIcon name="close" /></button>
      <div class="dialog-mark"><AppIcon name="link" :size="25" /></div>
      <h2 id="account-title">连接你的助手</h2>
      <p class="dialog-description">连接后端，开启真正的 AI 对话。</p>
      <div class="account-tabs" aria-label="账户操作">
        <button :class="{ selected: tab === 'connect' }" :disabled="busy" @click="tab = 'connect'; error = ''">已有用户</button>
        <button :class="{ selected: tab === 'register' }" :disabled="busy" @click="tab = 'register'; error = ''">注册用户</button>
      </div>
      <form @submit.prevent="submit">
        <template v-if="tab === 'connect'">
          <label for="user-id">用户 ID</label>
          <input id="user-id" v-model="userId" placeholder="输入注册时生成的用户 ID" maxlength="16" required :disabled="busy" autocomplete="off" />
          <p class="field-help">当前后端尚未提供登录鉴权，用户 ID 仅用于开发联调，不是安全登录。</p>
        </template>
        <template v-else>
          <label for="username">用户名</label>
          <input id="username" v-model="username" placeholder="给自己起个名字" maxlength="64" autocomplete="username" required :disabled="busy" />
          <label for="password">密码</label>
          <input id="password" v-model="password" type="password" placeholder="至少 8 个字符" autocomplete="new-password" required :disabled="busy" />
          <p class="field-help">密码只用于本次注册，不会保存在浏览器中。</p>
        </template>
        <p v-if="registered" class="registration-success">注册成功，请保存用户 ID：<strong>{{ registered.id }}</strong></p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="primary-button connect-submit" :disabled="busy">{{ busy ? '正在连接…' : tab === 'register' ? '注册并连接' : '连接助手' }}<AppIcon name="chevron" :size="17" /></button>
      </form>
      <button class="text-button demo-switch" :disabled="busy" @click="useDemo">暂时不连接，使用本地演示</button>
    </div>
  </dialog>
</template>

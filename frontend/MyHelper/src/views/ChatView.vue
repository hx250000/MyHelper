<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import AccountDialog from '../components/AccountDialog.vue'
import { api } from '../api/client'
import { conversationTitle, demoReply, readWorkspace, saveWorkspace } from '../lib/workspace'

const cached = readWorkspace()
const mode = ref(cached?.mode === 'live' && cached?.user?.id ? 'live' : 'demo')
const user = ref(cached?.user || null)
const conversations = ref(mode.value === 'demo' ? cached?.conversations || [] : [])
const messages = ref(mode.value === 'demo' ? cached?.messages || {} : {})
const demoSnapshot = ref(mode.value === 'demo'
  ? { conversations: conversations.value, messages: messages.value }
  : cached?.demo || { conversations: [], messages: {} })
const selectedId = ref(null)
const search = ref('')
const drafts = reactive({})
const busy = ref(false)
const loading = ref(false)
const historyReady = ref(true)
const error = ref('')
const toast = ref('')
const sidebarOpen = ref(false)
const accountDialog = ref(null)
const deleteDialog = ref(null)
const pendingDelete = ref(null)
const textarea = ref(null)
const thread = ref(null)
let historyVersion = 0
let toastTimer

const suggestions = [
  { icon: 'pen', title: '写作与表达', description: '让文字，更有温度', prompt: '帮我写一封简洁、友好的项目进度确认邮件。', color: 'peach' },
  { icon: 'book', title: '学习与探索', description: '把复杂的事，讲简单', prompt: '介绍一下费曼学习法，以及如何用它学习新知识。', color: 'sage' },
  { icon: 'calendar', title: '计划与效率', description: '让每一天，更有条理', prompt: '帮我制定一个平衡工作、学习和休息的一日计划。', color: 'lavender' },
  { icon: 'bulb', title: '灵感与创意', description: '给想法，多一点可能', prompt: '我想培养一个新的兴趣爱好，帮我梳理一下选择思路。', color: 'sand' },
]

const current = computed(() => conversations.value.find((item) => item.id === selectedId.value))
const currentMessages = computed(() => messages.value[selectedId.value] || [])
const title = computed(() => conversationTitle(current.value, currentMessages.value))
const draftKey = computed(() => selectedId.value ?? 'new')
const draft = computed({ get: () => drafts[draftKey.value] || '', set: (value) => { drafts[draftKey.value] = value } })
const locked = computed(() => busy.value || loading.value)
const canSend = computed(() => !locked.value && historyReady.value && draft.value.trim().length > 0 && draft.value.length <= 10000)
const filtered = computed(() => [...conversations.value]
  .sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || ''))
  .filter((item) => conversationTitle(item, messages.value[item.id]).toLowerCase().includes(search.value.toLowerCase())))

function notify(text) {
  clearTimeout(toastTimer)
  toast.value = text
  toastTimer = setTimeout(() => { toast.value = '' }, 3200)
}

function persist() {
  // Real chat history stays on the server; only the development user identity is remembered.
  const data = mode.value === 'demo'
    ? { mode: 'demo', user: null, conversations: conversations.value, messages: messages.value }
    : { mode: 'live', user: user.value, conversations: [], messages: {}, demo: demoSnapshot.value }
  if (!saveWorkspace(data)) notify('浏览器存储不可用，本地演示记录将在刷新后丢失。')
}

function resizeInput() {
  if (!textarea.value) return
  textarea.value.style.height = 'auto'
  textarea.value.style.height = `${Math.min(textarea.value.scrollHeight, 180)}px`
}

async function scrollBottom() {
  await nextTick()
  if (thread.value) thread.value.scrollTop = thread.value.scrollHeight
}

watch(draft, () => nextTick(resizeInput))
watch(() => currentMessages.value.length, scrollBottom)

async function selectConversation(id) {
  if (busy.value) return
  selectedId.value = id
  sidebarOpen.value = false
  error.value = ''
  const version = ++historyVersion
  loading.value = mode.value === 'live'
  historyReady.value = mode.value === 'demo'
  if (mode.value === 'live') {
    try {
      const result = await api.history(user.value.id, id)
      if (version === historyVersion) {
        messages.value[id] = result
        historyReady.value = true
      }
    } catch (cause) {
      if (version === historyVersion) error.value = `${cause.message} 请刷新历史记录后再发送。`
    } finally {
      if (version === historyVersion) loading.value = false
    }
  }
  scrollBottom()
}

function newConversation() {
  if (locked.value) return
  selectedId.value = null
  historyReady.value = true
  error.value = ''
  search.value = ''
  sidebarOpen.value = false
  nextTick(() => textarea.value?.focus())
}

async function refresh() {
  if (locked.value || mode.value !== 'live') return
  loading.value = true
  error.value = ''
  try {
    const data = await api.conversations(user.value.id)
    conversations.value = data.conversations
    if (selectedId.value && !conversations.value.some((item) => item.id === selectedId.value)) selectedId.value = null
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
  if (selectedId.value && !error.value) await selectConversation(selectedId.value)
}

async function send() {
  if (!canSend.value || error.value) return
  const content = draft.value.trim()
  const originalDraftKey = draftKey.value
  busy.value = true
  let id = selectedId.value
  let pendingId
  try {
    if (!id) {
      const conversation = mode.value === 'live'
        ? await api.createConversation(user.value.id)
        : { id: crypto.randomUUID(), title: '新对话', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }
      conversations.value.unshift(conversation)
      id = conversation.id
      selectedId.value = id
      messages.value[id] = []
    }
    drafts[originalDraftKey] = ''
    drafts[id] = ''
    pendingId = `pending-${crypto.randomUUID()}`
    messages.value[id] = [...(messages.value[id] || []), { id: pendingId, role: 'user', content, status: 'SENDING', createdAt: new Date().toISOString() }]
    scrollBottom()
    if (mode.value === 'demo') {
      await new Promise((resolve) => setTimeout(resolve, 650))
      const question = messages.value[id].find((item) => item.id === pendingId)
      question.status = 'COMPLETED'
      messages.value[id].push({ id: crypto.randomUUID(), role: 'assistant', content: demoReply(content), status: 'COMPLETED', createdAt: new Date().toISOString() })
    } else {
      const result = await api.send(user.value.id, id, content)
      messages.value[id] = messages.value[id].filter((item) => item.id !== pendingId)
      messages.value[id].push(result.userMessage, result.assistantMessage)
    }
    const conversation = conversations.value.find((item) => item.id === id)
    conversation.updatedAt = new Date().toISOString()
    persist()
  } catch (cause) {
    error.value = cause.message
    drafts[id ?? originalDraftKey] = content
    if (id && pendingId && mode.value === 'live') {
      // A failed response may still have saved the user message. Never automatically resend it.
      try {
        messages.value[id] = await api.history(user.value.id, id)
      } catch {
        const pending = messages.value[id].find((item) => item.id === pendingId)
        if (pending) pending.status = 'UNKNOWN'
        historyReady.value = false
      }
      error.value += ' 消息可能已保存，请先刷新历史记录确认；不会自动重发。'
    }
  } finally {
    busy.value = false
    scrollBottom()
    nextTick(() => textarea.value?.focus())
  }
}

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    send()
  }
}

function useSuggestion(prompt) {
  draft.value = prompt
  nextTick(() => textarea.value?.focus())
}

function connect({ user: nextUser, conversations: list }) {
  ++historyVersion
  if (mode.value === 'demo') demoSnapshot.value = { conversations: conversations.value, messages: messages.value }
  user.value = nextUser
  mode.value = nextUser ? 'live' : 'demo'
  conversations.value = nextUser ? list : demoSnapshot.value.conversations
  messages.value = nextUser ? {} : demoSnapshot.value.messages
  Object.keys(drafts).forEach((key) => delete drafts[key])
  selectedId.value = null
  historyReady.value = true
  loading.value = false
  error.value = ''
  sidebarOpen.value = false
  persist()
  if (nextUser) notify(`已连接，${nextUser.username}。用户 ID：${nextUser.id}`)
}

function useDemo() {
  if (mode.value === 'demo') return
  connect({ user: null, conversations: [] })
  persist()
  notify('已切换到本地演示，不会调用 AI。')
}

function askDelete(conversation) {
  if (mode.value !== 'demo' || locked.value) return
  pendingDelete.value = conversation
  deleteDialog.value.showModal()
}

function deleteConversation() {
  const id = pendingDelete.value.id
  conversations.value = conversations.value.filter((item) => item.id !== id)
  delete messages.value[id]
  delete drafts[id]
  if (selectedId.value === id) selectedId.value = null
  persist()
  deleteDialog.value.close()
  notify('已删除这条本地演示会话。')
}

async function copy(content) {
  try {
    await navigator.clipboard.writeText(content)
    notify('已复制到剪贴板。')
  } catch {
    notify('无法访问剪贴板，请选中文字手动复制。')
  }
}

function exportConversation() {
  const content = currentMessages.value.map((item) => `${item.role === 'user' ? '你' : 'MyHelper'}\n${item.content}`).join('\n\n────────\n\n')
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `MyHelper-${title.value.replace(/[\\/:*?"<>|]/g, '-')}.txt`
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function time(value) {
  return value ? new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : ''
}

onMounted(() => { if (mode.value === 'live') refresh() })
</script>

<template>
  <div class="workspace">
    <button v-if="sidebarOpen" class="sidebar-backdrop" aria-label="关闭侧边栏" @click="sidebarOpen = false" />
    <aside class="sidebar" :class="{ 'is-open': sidebarOpen }" aria-label="会话侧边栏" @keydown.esc="sidebarOpen = false">
      <a class="brand" href="/" @click.prevent="newConversation"><span class="brand-mark"><AppIcon name="spark" :size="23" /></span><span>MyHelper<span class="brand-dot">.</span></span></a>
      <div class="sidebar-main">
        <button class="primary-button new-chat" :disabled="locked" @click="newConversation"><AppIcon name="plus" :size="19" /><span>开启新对话</span><span class="new-chat-decoration">↗</span></button>
        <label class="search-box"><AppIcon name="search" :size="16" /><input v-model="search" placeholder="搜索会话" aria-label="搜索会话" /><span class="search-hint">⌕</span></label>
        <div class="history-heading"><span>你的对话</span><span>{{ conversations.length.toString().padStart(2, '0') }}</span><button v-if="mode === 'live'" class="icon-button" aria-label="刷新会话列表" :disabled="locked" @click="refresh"><AppIcon name="refresh" :size="14" /></button></div>
        <nav class="conversation-list" aria-label="历史会话">
          <div v-for="conversation in filtered" :key="conversation.id" class="conversation-item" :class="{ active: selectedId === conversation.id }">
            <button class="conversation-select" :disabled="busy" :aria-current="selectedId === conversation.id ? 'page' : undefined" @click="selectConversation(conversation.id)"><AppIcon name="chat" :size="16" /><span>{{ conversationTitle(conversation, messages[conversation.id]) }}</span></button>
            <button v-if="mode === 'demo'" class="icon-button delete-chat" :disabled="locked" :aria-label="`删除会话：${conversationTitle(conversation, messages[conversation.id])}`" @click="askDelete(conversation)"><AppIcon name="trash" :size="14" /></button>
          </div>
          <div v-if="!filtered.length" class="history-empty"><AppIcon :name="search ? 'search' : 'chat'" :size="25" /><p>{{ search ? '没有找到相关会话' : '每个好想法，都从对话开始' }}</p><span>{{ search ? '换个关键词试试吧' : '你的对话会出现在这里' }}</span></div>
        </nav>
      </div>
      <div class="sidebar-bottom">
        <div class="little-note"><AppIcon name="spark" :size="17" /><p>一个助手，无限可能。<span>为你的日常，多想一步。</span></p></div>
        <button class="profile-button" :disabled="locked" @click="accountDialog.open()"><span class="avatar">{{ user?.username?.slice(0, 1)?.toUpperCase() || 'M' }}</span><span class="profile-copy"><strong>{{ mode === 'demo' ? '我的工作空间' : user.username }}</strong><span>{{ mode === 'demo' ? '本地演示 · 无需登录' : `ID: ${user.id}` }}</span></span><AppIcon name="chevron" :size="16" /></button>
      </div>
    </aside>

    <main class="main-panel">
      <header class="topbar">
        <div class="topbar-title"><button class="icon-button mobile-menu" aria-label="打开侧边栏" :aria-expanded="sidebarOpen" @click="sidebarOpen = !sidebarOpen"><AppIcon name="panel" /></button><span>个人助手</span><span class="topbar-divider">/</span><span class="topbar-subtitle">{{ selectedId ? title : '新的开始' }}</span></div>
        <div class="topbar-actions"><span class="mode-badge"><i :class="{ live: mode === 'live' }" />{{ mode === 'demo' ? '本地演示' : '后端模式' }}</span><button class="connect-button" :disabled="locked" @click="accountDialog.open()">{{ mode === 'demo' ? '连接后端' : '切换用户' }}<AppIcon name="chevron" :size="14" /></button></div>
      </header>

      <section v-if="!currentMessages.length && !loading" class="welcome">
        <div class="welcome-inner">
          <div class="eyebrow"><span /> YOUR EVERYDAY COPILOT</div>
          <div class="welcome-symbol"><AppIcon name="spark" :size="43" /><span class="orbit-dot" /></div>
          <h1>你好，{{ user?.username || '很高兴见到你' }}<span class="heading-dot">。</span><br /><span>今天，有什么新想法？</span></h1>
          <p class="welcome-description">从一个问题，到一个好主意。<br class="mobile-break" /> 让 MyHelper 陪你一起，把想法变成行动。</p>
          <div class="suggestion-grid"><button v-for="suggestion in suggestions" :key="suggestion.title" class="suggestion-card" :disabled="locked" @click="useSuggestion(suggestion.prompt)"><span class="suggestion-icon" :class="suggestion.color"><AppIcon :name="suggestion.icon" :size="21" /></span><strong>{{ suggestion.title }}</strong><span class="suggestion-description">{{ suggestion.description }}</span><AppIcon class="suggestion-arrow" name="chevron" :size="15" /></button></div>
          <p class="suggestion-caption">没有标准的问题，只有值得探索的可能。</p>
        </div>
      </section>

      <section v-else ref="thread" class="thread" :aria-busy="busy || loading" aria-label="聊天记录">
        <div class="thread-inner">
          <div class="thread-heading"><span>{{ title }}</span><button class="icon-button" aria-label="导出当前对话" title="导出为文本" :disabled="locked || !currentMessages.length" @click="exportConversation"><AppIcon name="download" :size="18" /></button></div>
          <p v-if="loading" class="loading-history" role="status">正在读取历史消息…</p>
          <template v-else><article v-for="message in currentMessages" :key="message.id" class="message" :class="message.role"><div class="message-avatar" :class="message.role"><AppIcon v-if="message.role === 'assistant'" name="spark" :size="20" /><span v-else>{{ user?.username?.slice(0, 1) || '我' }}</span></div><div class="message-main"><div class="message-meta"><strong>{{ message.role === 'assistant' ? 'MyHelper' : '你' }}</strong><span>{{ message.role === 'assistant' && mode === 'demo' ? '演示助手' : time(message.createdAt) }}</span></div><div class="message-content">{{ message.content }}</div><span v-if="message.status === 'UNKNOWN'" class="message-warning">发送结果未知，请刷新历史确认。</span><button v-if="message.role === 'assistant'" class="copy-button" @click="copy(message.content)"><AppIcon name="copy" :size="13" />复制</button></div></article><div v-if="busy" class="thinking" role="status"><span class="message-avatar assistant"><AppIcon name="spark" :size="20" /></span><span class="thinking-dots"><i /><i /><i /></span><span>{{ mode === 'demo' ? '准备演示回复' : '正在思考' }}</span></div></template>
        </div>
      </section>

      <div class="composer-area">
        <div v-if="error" class="error-banner" role="alert"><AppIcon name="info" :size="17" /><span>{{ error }}</span><button v-if="mode === 'live'" class="text-button" :disabled="locked" @click="refresh">刷新</button><button class="icon-button" aria-label="关闭错误提示" @click="error = ''"><AppIcon name="close" :size="15" /></button></div>
        <form class="composer" :class="{ 'is-busy': busy }" @submit.prevent="send"><label class="sr-only" for="message-input">发送消息</label><textarea id="message-input" ref="textarea" v-model="draft" placeholder="说说你的想法，剩下的交给我…" rows="2" maxlength="10000" :disabled="locked" @keydown="onKeydown" /><div class="composer-bottom"><span class="composer-label"><AppIcon name="spark" :size="15" />{{ mode === 'demo' ? 'MyHelper · 演示助手' : 'MyHelper · AI 助手' }}</span><div class="send-controls"><span class="keyboard-hint">{{ draft.length > 9500 ? `${draft.length} / 10000` : 'Enter 发送，Shift + Enter 换行' }}</span><button class="send-button" :disabled="!canSend || !!error" :aria-label="busy ? '等待回复中' : '发送消息'"><span v-if="busy" class="spinner" /><AppIcon v-else name="arrow" :size="21" /></button></div></div></form>
        <p class="composer-disclaimer">{{ mode === 'demo' ? '当前为本地演示，回复为预设示例。连接后端即可与 AI 对话。' : 'AI 也会有不完美的时候，请核实重要信息。' }}</p>
        <div class="bottom-wordmark">MADE FOR YOUR EVERYDAY<span>✳</span></div>
      </div>
    </main>
    <AccountDialog ref="accountDialog" @connect="connect" @demo="useDemo" />
    <dialog ref="deleteDialog" class="account-dialog delete-dialog" aria-labelledby="delete-title"><div class="dialog-body"><h2 id="delete-title">删除这条演示会话？</h2><p class="dialog-description">只会删除浏览器中的这条本地记录，删除后无法恢复。</p><div class="delete-actions"><button class="text-button" @click="deleteDialog.close()">取消</button><button class="danger-button" @click="deleteConversation">确认删除</button></div></div></dialog>
    <Transition name="toast"><div v-if="toast" class="toast-notice" role="status"><AppIcon name="info" :size="16" />{{ toast }}</div></Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, ApiError, isActive, type Run, type RunEvent, type RunStatus,
  type Session, type Snapshot, type Submission } from './api'

const sessions = ref<Session[]>([])
const snapshot = ref<Snapshot | null>(null)
const selectedId = ref('')
const prompt = ref('')
const scenario = ref('NORMAL')
const error = ref('')
const connected = ref(false)
const loading = ref(false)
const submitting = ref(false)
const cancelling = ref(false)
const pending = ref<Submission | null>(null)
const timeline = ref<HTMLElement>()
const current = computed(() => snapshot.value?.runs.find(isActive))
const labels: Record<RunStatus, string> = {
  QUEUED: '排队中', RUNNING: '正在输出', CANCELLING: '正在停止', COMPLETED: '已完成',
  CANCELLED: '已停止', FAILED: '执行失败', TIMED_OUT: '已超时', INTERRUPTED: '已中断',
}
let source: EventSource | null = null
let revision = 0
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let refreshing = false
let dirty = false

function storageGet(key: string) {
  try { return localStorage.getItem(key) } catch { return null }
}
function storageSet(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch { /* The current tab still works without local storage. */ }
}
function setPending(value: Submission | null) {
  pending.value = value
  storageSet(`harness.pending.${selectedId.value}`, value ? JSON.stringify(value) : null)
}
async function refresh(id: string, version: number) {
  const value = await api<Snapshot>(`/sessions/${id}`)
  if (version !== revision || value.lastSeq < (snapshot.value?.lastSeq ?? 0)) return
  const node = timeline.value
  const scroll = !node || node.scrollHeight - node.scrollTop - node.clientHeight < 100
  snapshot.value = value
  if (error.value === '读取记录失败，正在重试。') error.value = ''
  if (pending.value && value.runs.some(run => run.requestId === pending.value?.requestId)) {
    setPending(null)
    prompt.value = ''
  }
  if (scroll) {
    await nextTick()
    timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' })
  }
}
function scheduleRefresh(id: string, version: number, delay = 200) {
  dirty = true
  if (refreshTimer || refreshing) return
  refreshTimer = setTimeout(async () => {
    refreshTimer = undefined
    if (version !== revision) return
    refreshing = true
    dirty = false
    let retryDelay = 200
    try { await refresh(id, version) }
    catch {
      if (version === revision) { error.value = '读取记录失败，正在重试。'; dirty = true; retryDelay = 2000 }
    } finally {
      if (version === revision) {
        refreshing = false
        if (dirty) scheduleRefresh(id, version, retryDelay)
      }
    }
  }, delay)
}
function follow(id: string, version: number) {
  source = new EventSource(`/api/sessions/${id}/events?after=${snapshot.value?.lastSeq ?? 0}`)
  source.onopen = () => {
    if (version !== revision) return
    connected.value = true
    scheduleRefresh(id, version)
  }
  source.onerror = () => { if (version === revision) connected.value = false }
  source.addEventListener('run-event', (message: MessageEvent<string>) => {
    if (version !== revision) return
    const event = JSON.parse(message.data) as RunEvent
    if (event.seq > (snapshot.value?.lastSeq ?? 0)) scheduleRefresh(id, version)
  })
}
async function selectSession(id: string) {
  const version = ++revision
  source?.close()
  source = null
  clearTimeout(refreshTimer)
  refreshTimer = undefined
  refreshing = false
  dirty = false
  connected.value = false
  selectedId.value = id
  snapshot.value = null
  error.value = ''
  loading.value = true
  prompt.value = ''
  pending.value = null
  try {
    const saved = storageGet(`harness.pending.${id}`)
    if (saved) pending.value = JSON.parse(saved) as Submission
  } catch { storageSet(`harness.pending.${id}`, null) }
  if (pending.value) { prompt.value = pending.value.prompt; scenario.value = pending.value.scenario }
  storageSet('harness.session', id)
  try {
    await refresh(id, version)
    if (version === revision) follow(id, version)
  } catch (cause) {
    if (version === revision) error.value = cause instanceof Error ? cause.message : '加载失败'
  } finally { if (version === revision) loading.value = false }
}
async function createSession() {
  if (loading.value || submitting.value) return
  loading.value = true
  try {
    const session = await api<Session>('/sessions', { title: '新会话' })
    sessions.value = [session, ...sessions.value]
    await selectSession(session.id)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '创建失败' }
  finally { loading.value = false }
}
async function initialize() {
  loading.value = true
  error.value = ''
  try {
    sessions.value = await api<Session[]>('/sessions')
    const stored = storageGet('harness.session')
    const id = sessions.value.find(session => session.id === stored)?.id || sessions.value[0]?.id
    if (id) await selectSession(id)
  } catch { error.value = '无法连接服务，请确认后端已启动后重试。' }
  finally { loading.value = false }
}
async function submit() {
  if (submitting.value || !snapshot.value || (!pending.value && (current.value || !prompt.value.trim()))) return
  const id = selectedId.value
  const version = revision
  const request = pending.value ?? { requestId: crypto.randomUUID(), prompt: prompt.value, scenario: scenario.value }
  setPending(request)
  submitting.value = true
  error.value = ''
  try {
    await api<Run>(`/sessions/${id}/runs`, request)
    if (version === revision) {
      setPending(null)
      prompt.value = ''
      await refresh(id, version)
    }
  } catch (cause) {
    if (version === revision) {
      if (cause instanceof ApiError && cause.status < 500) setPending(null)
      error.value = cause instanceof Error ? cause.message : '提交失败'
      scheduleRefresh(id, version)
    }
  } finally { submitting.value = false }
}
async function cancel() {
  if (!current.value || cancelling.value) return
  const id = selectedId.value
  const version = revision
  cancelling.value = true
  try {
    await api<Run>(`/runs/${current.value.id}/cancel`, {})
    await refresh(id, version)
  } catch (cause) { if (version === revision) error.value = cause instanceof Error ? cause.message : '停止失败' }
  finally { cancelling.value = false }
}
onMounted(initialize)
onUnmounted(() => { revision++; source?.close(); clearTimeout(refreshTimer) })
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">JH</span><strong>Java Harness</strong></div>
      <button class="new-session" :disabled="loading || submitting" @click="createSession">＋ 新建会话</button>
      <p class="section-label">会话记录</p>
      <nav aria-label="会话列表">
        <button v-for="session in sessions" :key="session.id" class="session-item"
          :class="{ selected: session.id === selectedId }" :disabled="submitting"
          :aria-current="session.id === selectedId ? 'page' : undefined" @click="selectSession(session.id)">
          <span>{{ session.title }}</span>
          <small>{{ new Date(session.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</small>
        </button>
      </nav>
      <div class="sidebar-note">本地运行 · 自动保存</div>
    </aside>
    <main class="main-panel">
      <header class="workspace-header">
        <div><p class="section-label">工作台</p><h1>{{ snapshot?.session.title || '开始一个新会话' }}</h1></div>
        <span class="connection" :class="{ online: connected }">{{ selectedId ? (connected ? '实时连接' : '正在连接…') : '本地服务' }}</span>
      </header>
      <div class="simulation-banner">模拟模式：用于体验流式输出和任务控制，尚未连接真实模型。</div>
      <div v-if="error" class="error-banner" role="alert">
        {{ error }} <button @click="selectedId ? selectSession(selectedId) : initialize()">重新连接</button>
      </div>
      <div ref="timeline" class="timeline" aria-label="执行记录" :aria-busy="loading">
        <div v-if="loading" class="empty-state">正在读取会话…</div>
        <div v-else-if="!snapshot" class="empty-state">
          <div class="empty-icon" aria-hidden="true">↗</div>
          <h2>从一句话开始</h2><p>新建会话，查看输出过程，或随时停止任务。</p>
          <button class="primary" @click="createSession">新建会话</button>
        </div>
        <div v-else-if="!snapshot.runs.length" class="empty-state"><h2>准备好了</h2><p>在下方输入内容，开始第一次执行。</p></div>
        <article v-for="run in snapshot?.runs" :key="run.id" class="run-card">
          <div class="message-heading"><strong>你</strong><time>{{ new Date(run.createdAt).toLocaleTimeString('zh-CN') }}</time></div>
          <p class="user-message">{{ run.prompt }}</p>
          <div class="response">
            <div class="message-heading"><strong>模拟助手</strong><span class="status" :class="run.status.toLowerCase()">{{ labels[run.status] }}</span></div>
            <div class="output">{{ run.output || (isActive(run) ? '等待输出…' : '没有文本输出') }}</div>
            <p v-if="run.detail" class="run-detail">{{ run.detail }}</p>
          </div>
        </article>
      </div>
      <form v-if="snapshot" class="composer" @submit.prevent="submit">
        <label class="sr-only" for="prompt">输入内容</label>
        <textarea id="prompt" v-model="prompt" placeholder="输入一句话，体验执行过程…" maxlength="4000"
          :disabled="!!pending || submitting" rows="3" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit" />
        <div v-if="pending" class="pending-note">正在确认上次提交。重试会复用同一请求，不会重复创建任务。</div>
        <div class="composer-actions">
          <label>模拟场景
            <select v-model="scenario" :disabled="!!pending || submitting || !!current">
              <option value="NORMAL">正常输出</option><option value="FAIL">中途失败</option><option value="SLOW">等待超时 / 手动停止</option>
            </select>
          </label>
          <div class="action-group">
            <span class="character-count">{{ prompt.length }} / 4000</span>
            <button v-if="current" type="button" class="stop" :disabled="cancelling || current.status === 'CANCELLING'" @click="cancel">{{ current.status === 'CANCELLING' ? '正在停止…' : '停止任务' }}</button>
            <button type="submit" class="primary" :disabled="submitting || (!pending && (!!current || !prompt.trim()))">{{ submitting ? '提交中…' : pending ? '重试提交' : '发送' }}</button>
          </div>
        </div>
        <p class="composer-note">Ctrl / ⌘ + Enter 发送 · 刷新页面不会停止任务</p>
      </form>
    </main>
  </div>
</template>

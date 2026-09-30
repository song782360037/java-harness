<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import ModelRequestDetails from './ModelRequestDetails.vue'
import ToolDetails from './ToolDetails.vue'
import FileChanges from './FileChanges.vue'
import Commands from './Commands.vue'
import SettingsPage from './SettingsPage.vue'
import ComposerSelectors from './ComposerSelectors.vue'
import WorkspaceSettings from './WorkspaceSettings.vue'
import UiIcon from './UiIcon.vue'
import MarkdownContent from './MarkdownContent.vue'
import { api, ApiError, isActive, effortLabel, type Run, type RunEvent, type RunStatus,
  type Session, type Snapshot, type Submission, type ModelProfile } from './api'

const sessions = ref<Session[]>([])
const profiles = ref<ModelProfile[]>([])
const selectedProfileId = ref('default')
const reasoningEffort = ref('')
const selectedProfile = computed(() => profiles.value.find(profile => profile.id === selectedProfileId.value))
const model = computed(() => selectedProfile.value?.model)
const showSettings = ref(false)
const settingsSection = ref<'models' | 'workspace'>('models')
const creatingWorkspace = ref(false)
const settingsBusy = ref(false)
const workspaceSaving = ref(false)
const workspaceSyncing = ref(false)
const workspaceChanging = computed(() => workspaceSaving.value || workspaceSyncing.value)
const workspaceDialog = ref<HTMLDialogElement>()
const browsingWorkspace = ref(false)
const inspectedRunId = ref('')
const inspectorHeading = ref<HTMLElement>()
let inspectorTrigger: HTMLElement | null = null
const collapsedGroups = ref(new Set<string>())
const sidebarCollapsed = ref(window.innerWidth <= 780)
const mobileLayout = window.matchMedia('(max-width: 780px)')
const isMobile = ref(mobileLayout.matches)
function onLayoutChange() { isMobile.value = mobileLayout.matches; if (isMobile.value) sidebarCollapsed.value = true }
function closeSidebarOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && !event.defaultPrevented && isMobile.value) sidebarCollapsed.value = true
}
const sessionSearch = ref('')
const promptInput = ref<HTMLTextAreaElement>()
const copiedRun = ref('')
const copyError = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined
const sessionGroups = computed(() => {
  const groups = new Map<string, { label: string; sessions: Session[] }>()
  for (const session of sessions.value) {
    if (!`${session.title} ${session.workspace}`.toLowerCase().includes(sessionSearch.value.toLowerCase())) continue
    const key = session.workspace || ''
    if (!groups.has(key)) groups.set(key, { label: key.split(/[\\/]/).filter(Boolean).pop() || '最近会话', sessions: [] })
    groups.get(key)!.sessions.push(session)
  }
  return [...groups.entries()]
})
const suggestions = [
  { icon: 'folder', title: '了解项目', prompt: '查看项目结构，读取构建配置，并结合源码解释程序入口。' },
  { icon: 'scan', title: '定位问题', prompt: '请帮我定位一个问题，先告诉我需要提供哪些错误信息和复现步骤。' },
  { icon: 'branch', title: '梳理调用链', prompt: '分析项目的核心模块和调用关系，标明关键类与方法。' },
]
function useSuggestion(value: string) { prompt.value = value; promptInput.value?.focus() }
// 在项目列表中折叠或展开会话，搜索时始终显示结果。
function toggleGroup(path: string) {
  const next = new Set(collapsedGroups.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsedGroups.value = next
}
// 输入框随草稿增长，超过上限后在框内滚动。
function resizePrompt() {
  const input = promptInput.value
  if (!input) return
  input.style.height = 'auto'
  input.style.height = `${Math.min(input.scrollHeight, 200)}px`
}
// 在详情面板中查看本轮执行信息。
async function inspectRun(run: Run, event: MouseEvent) {
  inspectorTrigger = event.currentTarget as HTMLElement
  inspectedRunId.value = run.id
  await nextTick()
  inspectorHeading.value?.focus()
}
// 关闭详情并返回原入口。
async function closeInspector() {
  const trigger = inspectorTrigger
  inspectedRunId.value = ''
  inspectorTrigger = null
  await nextTick()
  if (trigger?.isConnected) trigger.focus()
}
// 从工作区菜单打开目录选择弹窗。
async function browseWorkspace() {
  if (current.value || submitting.value || pending.value || loading.value || workspaceChanging.value) return
  creatingWorkspace.value = !snapshot.value
  browsingWorkspace.value = true
  await nextTick()
  workspaceDialog.value?.showModal()
}
// 保存结束前保持弹窗打开。
function closeWorkspaceDialog() {
  if (workspaceChanging.value) return
  workspaceDialog.value?.close()
  browsingWorkspace.value = false
}
// 沿用既有工作区接口，快速选择项目时保留草稿与模型选择。
async function chooseWorkspace(workspace: string) {
  if (current.value || submitting.value || pending.value || loading.value || workspaceChanging.value || workspace === (snapshot.value?.session.workspace || '')) return
  creatingWorkspace.value = !snapshot.value
  workspaceSyncing.value = true
  error.value = ''
  try {
    const session = snapshot.value
      ? await api<Session>(`/sessions/${selectedId.value}/workspace`, { workspace })
      : await api<Session>('/sessions', { title: '新会话', workspace })
    await workspaceSaved(session)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '切换工作区失败' }
  finally { workspaceSyncing.value = false }
}
// 目录弹窗保存成功后同步会话，再关闭弹窗。
async function workspacePicked(session: Session) {
  workspaceSyncing.value = true
  try {
    await workspaceSaved(session)
    workspaceDialog.value?.close()
    browsingWorkspace.value = false
  } finally {
    workspaceSyncing.value = false
    await nextTick()
    document.getElementById('composer-workspace')?.focus()
  }
}
async function copyAnswer(run: Run) {
  try { await navigator.clipboard.writeText(run.output); copiedRun.value = run.id; copyError.value = ''; clearTimeout(copyTimer); copyTimer = setTimeout(() => { copiedRun.value = '' }, 2000) }
  catch { copyError.value = '复制失败，请选择回答内容手动复制。' }
}
function openSettings(section: 'models' | 'workspace' = 'models', creating = false) {
  if (workspaceChanging.value) return
  settingsSection.value = section
  creatingWorkspace.value = creating
  showSettings.value = true
}
function closeSettings() { showSettings.value = false; settingsBusy.value = false }
function navigateSession(id: string) {
  if (isMobile.value) sidebarCollapsed.value = true
  if (id === selectedId.value && snapshot.value && !loading.value) closeSettings()
  else void selectSession(id)
}
const toolRevisions = ref<Record<string, number>>({})
const changeRevisions = ref<Record<string, number>>({})
const commandRevisions = ref<Record<string, number>>({})
const commandActivity = ref<Record<string, string>>({})
const activityLabel = (run: Run) => run.status === 'RUNNING' && commandActivity.value[run.id] ? commandActivity.value[run.id] : labels[run.status]
const executionRevision = (run: Run) => `${run.status}:${run.context?.characters ?? 0}:${toolRevisions.value[run.id] ?? 0}`
const snapshot = ref<Snapshot | null>(null)
const selectedId = ref('')
const prompt = ref('')
const error = ref('')
const connected = ref(false)
const loading = ref(false)
const submitting = ref(false)
const cancelling = ref(false)
const pending = ref<Submission | null>(null)
const timeline = ref<HTMLElement>()
const current = computed(() => snapshot.value?.runs.find(isActive))
const inspectedRun = computed(() => snapshot.value?.runs.find(run => run.id === inspectedRunId.value))
watch(prompt, resizePrompt, { flush: 'post' })
const labels: Record<RunStatus, string> = {
  QUEUED: '排队中', RUNNING: '正在执行', CANCELLING: '正在停止', COMPLETED: '已完成',
  CANCELLED: '已停止', FAILED: '执行失败', TIMED_OUT: '已超时', INTERRUPTED: '已中断',
}
let source: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | undefined
let revision = 0
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let refreshing = false
let dirty = false
let profileRevision = 0
async function loadProfiles() {
  const version = ++profileRevision
  const values = await api<ModelProfile[]>('/model-profiles')
  if (version === profileRevision) profiles.value = values
}
function chooseProfile(id: string) { selectedProfileId.value = id; reasoningEffort.value = selectedProfile.value?.reasoningEffort || '' }

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
  sessions.value = sessions.value.map(session => session.id === value.session.id ? value.session : session)
  if (error.value === '读取记录失败，正在重试。') error.value = ''
  if (pending.value && value.runs.some(run => run.requestId === pending.value?.requestId)) {
    setPending(null)
    prompt.value = ''
  }
  if (scroll) {
    await nextTick()
    timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' })
  }
  return value
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
  const stream = new EventSource(`/api/sessions/${id}/events?after=${snapshot.value?.lastSeq ?? 0}`)
  source = stream
  stream.onopen = () => {
    if (version !== revision || source !== stream) return
    connected.value = true
    void loadProfiles().catch(() => { /* Keep known profiles during temporary connection loss. */ })
    scheduleRefresh(id, version)
  }
  stream.onerror = () => {
    if (version !== revision || source !== stream) return
    connected.value = false
    // A proxy 500/invalid SSE response can permanently close native EventSource retries.
    // Recreate explicitly and resume from the last committed snapshot cursor.
    stream.close()
    clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = undefined
      if (version === revision && source === stream) follow(id, version)
    }, 1500)
  }
  stream.addEventListener('run-event', (message: MessageEvent<string>) => {
    if (version !== revision || source !== stream) return
    const event = JSON.parse(message.data) as RunEvent
    if (event.type === 'TOOL' && event.runId) toolRevisions.value[event.runId] = event.seq
    if (event.type === 'FILE_CHANGE' && event.runId) changeRevisions.value[event.runId] = event.seq
    if (event.type === 'COMMAND' && event.runId) commandRevisions.value[event.runId] = event.seq
    if (event.seq > (snapshot.value?.lastSeq ?? 0)) scheduleRefresh(id, version)
  })
}
async function selectSession(id: string) {
  inspectedRunId.value = ''; inspectorTrigger = null
  closeSettings()
  const version = ++revision
  source?.close()
  source = null
  clearTimeout(reconnectTimer)
  clearTimeout(refreshTimer)
  refreshTimer = undefined
  refreshing = false
  dirty = false
  connected.value = false
  selectedId.value = id
  snapshot.value = null
  toolRevisions.value = {}
  changeRevisions.value = {}
  commandRevisions.value = {}
  commandActivity.value = {}
  error.value = ''
  loading.value = true
  prompt.value = ''
  pending.value = null
  try {
    const saved = storageGet(`harness.pending.${id}`)
    if (saved) pending.value = JSON.parse(saved) as Submission
  } catch { storageSet(`harness.pending.${id}`, null) }
  if (pending.value) prompt.value = pending.value.prompt
  storageSet('harness.session', id)
  try {
    const loaded = await refresh(id, version)
    if (version === revision) {
      const session = loaded?.session
      selectedProfileId.value = pending.value?.modelProfileId || session?.modelProfileId || 'default'
      reasoningEffort.value = pending.value?.reasoningEffort ?? (loaded?.runs.length ? session?.reasoningEffort || '' : selectedProfile.value?.reasoningEffort || '')
      follow(id, version)
    }
  } catch (cause) {
    if (version === revision) error.value = cause instanceof Error ? cause.message : '加载失败'
  } finally { if (version === revision) loading.value = false }
}
async function addSession(title: string, workspace: string) {
  const profile = selectedProfileId.value
  const effort = reasoningEffort.value
  const session = await api<Session>('/sessions', { title, workspace })
  sessions.value = [session, ...sessions.value]
  await selectSession(session.id)
  selectedProfileId.value = profile; reasoningEffort.value = effort
  if (isMobile.value) sidebarCollapsed.value = true
  await nextTick()
  promptInput.value?.focus()
  return snapshot.value?.session.id === session.id
}
async function createSession() {
  if (loading.value || submitting.value || workspaceChanging.value) return
  loading.value = true; error.value = ''
  try { await addSession('新会话', snapshot.value?.session.workspace || '') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建会话失败' }
  finally { loading.value = false }
}
async function send() {
  if (workspaceChanging.value) return
  if (snapshot.value) { await submit(); return }
  if (loading.value || submitting.value || selectedId.value || !model.value?.configured || !prompt.value.trim()) return
  const draft = prompt.value
  let ready = false
  submitting.value = true; error.value = ''
  try { ready = await addSession(draft.trim().slice(0, 40), '') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建会话失败' }
  finally { prompt.value = draft; submitting.value = false }
  if (ready) await submit()
}
function settingsSaved(value: ModelProfile) {
  profileRevision++
  profiles.value = [...profiles.value.filter(profile => profile.id !== value.id), value]
  if (!pending.value && !submitting.value) {
    selectedProfileId.value = value.id
    reasoningEffort.value = value.reasoningEffort
  }
}
async function profileDeleted(id: string) {
  profileRevision++
  profiles.value = profiles.value.filter(profile => profile.id !== id)
}
async function workspaceSaved(session: Session) {
  const creating = creatingWorkspace.value || !snapshot.value
  const draft = prompt.value
  const profile = selectedProfileId.value
  const effort = reasoningEffort.value
  closeSettings()
  sessions.value = creating ? [session, ...sessions.value] : sessions.value.map(value => value.id === session.id ? session : value)
  try {
    if (creating) {
      await selectSession(session.id)
      prompt.value = draft; selectedProfileId.value = profile; reasoningEffort.value = effort
    }
    else if (selectedId.value === session.id) await refresh(session.id, revision)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '刷新会话失败' }
}
async function initialize() {
  loading.value = true
  error.value = ''
  try {
    await loadProfiles()
    sessions.value = await api<Session[]>('/sessions')
    const stored = storageGet('harness.session')
    const id = sessions.value.find(session => session.id === stored)?.id || sessions.value[0]?.id
    if (id) await selectSession(id)
  } catch { error.value = '无法连接服务，请确认后端已启动后重试。' }
  finally { loading.value = false }
}
async function submit() {
  if (submitting.value || !snapshot.value || (!pending.value && (!model.value?.configured || current.value || !prompt.value.trim()))) return
  const id = selectedId.value
  const version = revision
  const request = pending.value ?? { requestId: crypto.randomUUID(), prompt: prompt.value,
    scenario: 'NORMAL', modelProfileId: selectedProfileId.value, reasoningEffort: reasoningEffort.value }
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
onMounted(() => { void initialize(); mobileLayout.addEventListener('change', onLayoutChange); document.addEventListener('keydown', closeSidebarOnEscape) })
onUnmounted(() => { revision++; source?.close(); clearTimeout(reconnectTimer); clearTimeout(refreshTimer); clearTimeout(copyTimer); mobileLayout.removeEventListener('change', onLayoutChange); document.removeEventListener('keydown', closeSidebarOnEscape) })
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <button v-if="!sidebarCollapsed && !showSettings" class="sidebar-scrim" aria-label="关闭会话侧栏" @click="sidebarCollapsed = true"></button>
    <aside v-show="!showSettings" class="sidebar" :inert="sidebarCollapsed || undefined">
      <div class="brand"><img class="brand-icon" src="/harness-icon.svg" width="28" height="28" alt="" /><strong>Harness</strong><button class="icon-button collapse-sidebar" aria-label="收起侧栏" title="收起侧栏" @click="sidebarCollapsed = true"><UiIcon name="panel" /></button></div>
      <button class="new-session" :disabled="loading || submitting || settingsBusy || workspaceChanging" @click="createSession"><UiIcon name="compose" /><span>新对话</span><UiIcon name="plus" :size="15" /></button>
      <label class="session-search"><UiIcon name="search" :size="16" /><input v-model="sessionSearch" placeholder="搜索会话" aria-label="搜索会话" /><button v-if="sessionSearch" class="icon-button" aria-label="清空搜索" @click="sessionSearch = ''"><UiIcon name="close" :size="14" /></button></label>
      <nav class="session-navigation" aria-label="会话列表">
        <section v-for="[path, group] in sessionGroups" :key="path" class="session-group">
          <h2><button class="group-toggle" :title="path || group.label" :aria-expanded="!collapsedGroups.has(path) || !!sessionSearch" @click="toggleGroup(path)"><UiIcon :name="collapsedGroups.has(path) && !sessionSearch ? 'chevron' : 'down'" :size="12" /><UiIcon v-if="path" name="folder" :size="14" /><span>{{ group.label }}</span><small>{{ group.sessions.length }}</small></button></h2>
          <button v-for="session in group.sessions" v-show="!collapsedGroups.has(path) || !!sessionSearch" :key="session.id" class="session-item" :class="{ selected: session.id === selectedId }" :disabled="submitting || loading || settingsBusy || workspaceChanging" :aria-current="session.id === selectedId ? 'page' : undefined" :title="session.title" @click="navigateSession(session.id)"><span>{{ session.title }}</span><span v-if="session.id === selectedId && current" class="activity-dot" aria-label="任务运行中"></span></button>
        </section>
        <p v-if="!sessionGroups.length" class="sidebar-empty">{{ sessionSearch ? '没有匹配的会话' : '你的对话将保存在这里' }}</p>
      </nav>
      <div class="sidebar-footer"><button class="settings-entry" :disabled="settingsBusy || workspaceChanging" @click="openSettings()"><UiIcon name="settings" /><span>设置</span></button></div>
    </aside>
    <main v-show="!showSettings" class="main-panel" :class="{ 'is-welcome': !snapshot?.runs.length }" :inert="isMobile && !sidebarCollapsed || undefined">
      <header class="workspace-header">
        <div class="workspace-title"><button v-if="sidebarCollapsed" class="icon-button" aria-label="展开侧栏" title="展开侧栏" @click="sidebarCollapsed = false"><UiIcon name="panel" /></button><h1>{{ snapshot?.session.title || '新对话' }}</h1></div>
        <div class="header-meta"><span v-if="snapshot?.session.workspace" class="readonly-label">修改需审核</span><span class="connection" :class="{ online: connected }" :title="selectedId ? (connected ? '实时连接正常' : '正在重新连接') : '本地服务'">{{ selectedId && !connected ? '连接中' : '本地' }}</span></div>
      </header>
      <div v-if="error" class="error-banner" role="alert">{{ error }} <button @click="selectedId ? selectSession(selectedId) : initialize()">重新连接</button></div>
      <div class="workbench-body" :class="{ 'has-inspector': inspectedRun }"><div class="chat-panel">
      <div ref="timeline" class="timeline" aria-label="对话记录" :aria-busy="loading">
        <div v-if="loading" class="empty-state"><span class="loading-indicator"></span><p>正在读取会话…</p></div>
        <div v-else-if="!snapshot?.runs.length" class="welcome">
          <p class="welcome-eyebrow">{{ snapshot?.session.workspace?.split(/[\\/]/).filter(Boolean).pop() || 'Java Harness' }}</p>
          <h2>{{ snapshot?.session.workspace ? '想在这个项目里做些什么？' : '开始你的下一项工作' }}</h2>
        </div>
        <article v-for="run in snapshot?.runs" :key="run.id" class="run-card">
          <div class="user-message-wrap"><p class="user-message">{{ run.prompt }}</p></div>
          <section class="response" :aria-label="run.model + ' 的回答'">
            <div class="assistant-heading"><img class="brand-icon" src="/harness-icon.svg" width="22" height="22" alt="" /><strong>Harness</strong><span v-if="isActive(run)" class="working-label"><span class="activity-dot"></span>{{ activityLabel(run) }}</span></div>
            <button type="button" class="execution-trigger" :aria-label="'查看执行详情 · ' + activityLabel(run)" :aria-expanded="inspectedRunId === run.id" aria-controls="execution-inspector" @click="inspectRun(run, $event)"><span v-if="isActive(run)" class="loading-indicator"></span><UiIcon v-else :name="run.status === 'COMPLETED' ? 'check' : 'chevron'" :size="13" /><span>{{ activityLabel(run) }}</span><UiIcon name="chevron" :size="12" /></button>
            <MarkdownContent v-if="run.output" :content="run.output" />
            <p v-else class="output-placeholder">{{ isActive(run) ? (commandActivity[run.id] ? `${activityLabel(run)}，请查看下方终端卡片。` : '正在分析你的问题…') : '本次没有生成回答。' }}</p>
            <p v-if="run.detail" class="run-notice" :class="{ 'has-error': ['FAILED', 'TIMED_OUT', 'INTERRUPTED'].includes(run.status) }">{{ run.detail }}</p>
            <FileChanges v-if="run.workspace" :run-id="run.id" :revision="`${run.status}:${changeRevisions[run.id] || 0}:${connected}`" :can-apply="run.status === 'COMPLETED' && !current && !submitting && !pending && snapshot?.session.workspace === run.workspace" />
            <Commands v-if="run.workspace" :run-id="run.id" :revision="`${run.status}:${commandRevisions[run.id] || 0}:${connected}`" :active="run.status === 'RUNNING'" @activity="commandActivity[run.id] = $event" />
            <div v-if="!isActive(run)" class="response-footer"><button class="icon-button" :disabled="!run.output" :aria-label="copiedRun === run.id ? '已复制回答' : '复制回答'" :title="copiedRun === run.id ? '已复制' : '复制回答'" @click="copyAnswer(run)"><UiIcon :name="copiedRun === run.id ? 'check' : 'copy'" :size="15" /></button><span>{{ run.model }}</span><time>{{ new Date(run.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</time></div>
          </section>
        </article>
        <p v-if="copyError" role="status" class="run-notice">{{ copyError }}</p>
      </div>
      <div class="composer-dock">
        <form class="composer" @submit.prevent="send">
          <label class="sr-only" for="prompt">输入问题</label>
          <textarea id="prompt" @input="resizePrompt" ref="promptInput" v-model="prompt" :placeholder="snapshot?.session.workspace ? '询问这个项目中的任何问题…' : '描述你的问题，开始一起探索…'" maxlength="4000" :disabled="!!pending || submitting || loading || workspaceChanging" rows="2" @keydown.ctrl.enter.prevent="send" @keydown.meta.enter.prevent="send" />
          <div v-if="pending" class="pending-note">上次提交结果待确认，重试不会重复创建任务。</div>
          <p v-else-if="!model?.configured && !loading" class="configuration-hint"><span>连接一个模型，即可开始对话</span><button type="button" @click="openSettings('models')">配置模型 <UiIcon name="chevron" :size="12" /></button></p>
          <div class="composer-actions">
            <ComposerSelectors :profiles="profiles" :model-id="selectedProfileId" :effort="reasoningEffort" :workspace="snapshot?.session.workspace || ''" :sessions="sessions" :disabled="submitting || !!pending || loading || workspaceChanging" :workspace-disabled="!!current" @model="chooseProfile" @effort="reasoningEffort = $event" @settings="openSettings('models')" @workspace="chooseWorkspace" @browse="browseWorkspace" />
            <button v-if="current" type="button" class="send-button stop-button" :disabled="cancelling || current.status === 'CANCELLING'" :aria-label="current.status === 'CANCELLING' ? '正在停止' : '停止任务'" title="停止任务" @click="cancel"><UiIcon name="stop" :size="15" /></button>
            <button v-else type="submit" class="send-button" :disabled="loading || submitting || workspaceChanging || (!pending && (!model?.configured || !prompt.trim() || (!!selectedId && !snapshot)))" :aria-label="pending ? '重试提交' : '发送消息'" :title="pending ? '重试提交' : '发送 · Ctrl / ⌘ + Enter'"><UiIcon :name="pending ? 'refresh' : 'arrow'" :size="18" /></button>
          </div>
        </form>
        <div v-if="!snapshot?.runs.length && !loading" class="suggestion-list"><button v-for="item in suggestions" :key="item.title" :disabled="submitting || !!pending" @click="useSuggestion(item.prompt)"><UiIcon :name="item.icon" :size="15" />{{ item.title }}</button></div>
        <div class="composer-caption"><span>{{ snapshot?.session.workspace ? '文件修改经你审核后应用' : '在本机管理对话与工作区' }}</span><span v-if="prompt.length > 3500">{{ prompt.length }} / 4000</span><span v-else>Ctrl / ⌘ + Enter 发送</span></div>
      </div>
      </div>
      <aside v-if="inspectedRun" id="execution-inspector" class="execution-inspector" aria-labelledby="inspector-title" @keydown.esc.stop="closeInspector">
        <header class="inspector-header"><h2 id="inspector-title" ref="inspectorHeading" tabindex="-1">执行详情</h2><button type="button" class="icon-button" aria-label="关闭执行详情" @click="closeInspector"><UiIcon name="close" :size="16" /></button></header>
        <div class="inspector-content">
          <dl class="execution-metadata"><dt>状态</dt><dd>{{ activityLabel(inspectedRun) }}</dd><dt>模型</dt><dd>{{ inspectedRun.model }}</dd><dt>思考强度</dt><dd>{{ effortLabel(inspectedRun.reasoningEffort) }}</dd><dt v-if="inspectedRun.workspace">工作区</dt><dd v-if="inspectedRun.workspace">{{ inspectedRun.workspace }}</dd></dl>
          <p v-if="inspectedRun.context" class="run-detail">携带 {{ inspectedRun.context.includedTurns }} 轮历史<span v-if="inspectedRun.context.droppedTurns"> · 已裁剪 {{ inspectedRun.context.droppedTurns }} 轮</span><span v-if="inspectedRun.context.excludedTurns"> · 已跳过 {{ inspectedRun.context.excludedTurns }} 条</span></p>
          <ToolDetails :key="inspectedRun.id" :run-id="inspectedRun.id" :revision="executionRevision(inspectedRun)" />
          <ModelRequestDetails v-if="inspectedRun.context" :key="inspectedRun.id" :run-id="inspectedRun.id" :revision="executionRevision(inspectedRun)" />
        </div>
      </aside>
      </div>
    </main>
    <dialog ref="workspaceDialog" class="workspace-dialog settings-page" aria-labelledby="workspace-dialog-title" @cancel="workspaceChanging && $event.preventDefault()" @close="browsingWorkspace = false">
      <header class="inspector-header"><h2 id="workspace-dialog-title">选择工作区</h2><button type="button" class="icon-button" :disabled="workspaceChanging" aria-label="关闭工作区选择" @click="closeWorkspaceDialog"><UiIcon name="close" :size="17" /></button></header>
      <WorkspaceSettings v-if="browsingWorkspace" :session-id="snapshot?.session.id" :initial-path="snapshot?.session.workspace || ''" :disabled="workspaceSyncing || !!current || submitting || !!pending || loading" @busy="workspaceSaving = $event" @saved="workspacePicked" />
    </dialog>
    <SettingsPage v-if="showSettings" :initial-profile-id="selectedProfileId" :initial-section="settingsSection" :session="snapshot?.session" :creating-workspace="creatingWorkspace" :workspace-disabled="!!current || submitting || !!pending || loading" @close="closeSettings" @busy="settingsBusy = $event" @saved="settingsSaved" @deleted="profileDeleted" @changed="loadProfiles().catch(() => {})" @workspace-saved="workspaceSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError, type CommandExecution, type FileChange, type ModelProfile, type Run, type Snapshot, type Workspace } from './api'
import { activeStatuses, approvalTargets, avatarText, insertMention, memberStates, mentionCandidates, mentionQuery, roundNotice, speakingMembers, studioSummary, taskLabel, taskSteps,
  type GroupMember, type GroupMessage, type GroupSnapshot, type GroupSummary, type GroupTemplate, type RunApprovals } from './groups'
import { preferences, shouldSendPrompt } from './preferences'
import MarkdownContent from './MarkdownContent.vue'
import FileChanges from './FileChanges.vue'
import Commands from './Commands.vue'
import RunTimeline from './RunTimeline.vue'
import GroupSettingsDialog from './GroupSettingsDialog.vue'
import UiIcon from './UiIcon.vue'
// 3D 工作室依赖 three，按需加载，不拖慢聊天视图。
const GroupStudio = defineAsyncComponent(() => import('./GroupStudio.vue'))
const props = defineProps<{ workspaces: Workspace[]; profiles: ModelProfile[]; sidebarCollapsed: boolean; defaultProfileId: string }>()
const emit = defineEmits<{ expandSidebar: []; studio: [active: boolean] }>()
const groups = ref<GroupSummary[]>([])
const templates = ref<GroupTemplate[]>([])
const selectedId = ref('')
const snapshot = ref<GroupSnapshot | null>(null)
const loading = ref(true)
const error = ref('')
const text = ref('')
const sending = ref(false)
const stopping = ref(false)
const settings = ref<'' | 'create' | 'edit'>('')
const timeline = ref<HTMLElement>()
const input = ref<HTMLTextAreaElement>()
const caret = ref(0)
const mentionIndex = ref(0)
// 网络结果不明时沿用同一 requestId 重试，服务端按 requestId 去重。
let requestId = ''
// "查看过程"按需读取成员会话中的运行记录。
const processes = ref<Record<string, Run | null>>({})
const speaking = computed(() => snapshot.value ? speakingMembers(snapshot.value) : [])
const notice = computed(() => roundNotice(snapshot.value?.round ?? null))
const mention = computed(() => mentionQuery(text.value, caret.value))
const candidates = computed(() => mention.value && snapshot.value ? mentionCandidates(snapshot.value.members, mention.value.query).slice(0, 6) : [])
const workspaceName = (id: string) => id ? props.workspaces.find(item => item.id === id)?.name || '工作区已不存在' : '只讨论'
const message = (cause: unknown, fallback: string) => cause instanceof Error ? cause.message : fallback
const memberOf = (item: GroupMessage) => snapshot.value?.members.find(member => member.id === item.memberId)
const runOf = (id: string | null) => id ? snapshot.value?.runs.find(run => run.id === id) : undefined
const taskOf = (id: string | null) => id ? snapshot.value?.tasks.find(task => task.runId === id) : undefined
const steps = computed(() => taskSteps(snapshot.value?.tasks ?? []))
const finishedTasks = computed(() => snapshot.value?.tasks.filter(task => task.status === 'DONE').length ?? 0)
const waitingText = computed(() => snapshot.value?.group.mode !== 'TASKS' ? '主持人正在安排下一位发言…' : snapshot.value.tasks.length ? '正在推进任务…' : '主持人正在拆分任务…')
const time = (value: string) => new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
const sendHint = computed(() => preferences.value.sendShortcut === 'enter' ? 'Enter 发送，Shift + Enter 换行' : 'Ctrl / ⌘ + Enter 发送')
// 每次进入群聊默认显示聊天；工作室期间由 App 收起并锁定全局侧栏。
const view = ref<'chat' | 'studio'>('chat')
// 工作室视图需要的命令与文件审核状态，按运行的 status:activity 缓存，变化时才重新读取。
const approvals = ref<Record<string, RunApprovals>>({})
let approvalKeys: Record<string, string> = {}
const summary = computed(() => snapshot.value ? studioSummary(snapshot.value, memberStates(snapshot.value, approvals.value)) : { text: '', attention: false })

let source: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | undefined
let watchdogTimer: ReturnType<typeof setTimeout> | undefined
let reconnectDelay = 1500
let lastStreamMessageAt = 0
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let pollTimer: ReturnType<typeof setInterval> | undefined
let revision = 0
// 并发刷新只采用最后发起的那一次，避免旧快照覆盖新快照。
let refreshSeq = 0

async function loadGroups() {
  groups.value = await api<GroupSummary[]>('/groups')
}
async function refresh(id: string, version: number) {
  const seq = ++refreshSeq
  const value = await api<GroupSnapshot>(`/groups/${id}`)
  if (version !== revision || seq !== refreshSeq) return
  const node = timeline.value
  const follow = !snapshot.value || (preferences.value.autoScroll && (!node || node.scrollHeight - node.scrollTop - node.clientHeight < 120))
  snapshot.value = value
  groups.value = groups.value.map(item => item.group.id === id ? { group: value.group, members: value.members.length, busy: value.busy } : item)
  if (error.value === '读取群聊失败，正在重试。') error.value = ''
  if (follow) { await nextTick(); timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' }) }
}
// 合并短时间内的多次通知，只读取一次快照。
function scheduleRefresh(id: string, version: number, delay = 150) {
  if (refreshTimer) return
  refreshTimer = setTimeout(async () => {
    refreshTimer = undefined
    if (version !== revision) return
    try { await refresh(id, version) }
    catch (cause) {
      if (version !== revision) return
      if (cause instanceof ApiError && cause.status === 404) { await groupDeleted(id); return }
      error.value = '读取群聊失败，正在重试。'
      scheduleRefresh(id, version, 2000)
    }
  }, delay)
}
// 服务端每 5 秒发送 ping；15 秒内没有任何消息就视为连接已静默失效并重建。
function armWatchdog(version: number, stream: EventSource) {
  lastStreamMessageAt = Date.now()
  clearTimeout(watchdogTimer)
  watchdogTimer = setTimeout(() => { if (version === revision && source === stream) restartStream() }, 15000)
}
function follow(id: string, version: number) {
  // 加载快照期间 resumeStream 可能已先建流，这里关闭它，保证始终只有一条连接。
  source?.close()
  const stream = new EventSource(`/api/groups/${id}/events`)
  source = stream
  armWatchdog(version, stream)
  stream.onopen = () => {
    if (version !== revision || source !== stream) return
    reconnectDelay = 1500
    armWatchdog(version, stream)
    scheduleRefresh(id, version)
  }
  stream.onerror = () => {
    if (version !== revision || source !== stream) return
    // 显式重建连接；服务端每 55 秒结束一次流，重连后重新读取快照即可。读取快照也能发现群聊已被删除。
    stream.close()
    clearTimeout(watchdogTimer)
    scheduleRefresh(id, version, 1000)
    clearTimeout(reconnectTimer)
    // 后端持续不可用时按指数退避重连，连接成功后恢复为 1.5 秒。
    const delay = reconnectDelay
    reconnectDelay = Math.min(reconnectDelay * 2, 15000)
    reconnectTimer = setTimeout(() => { if (version === revision && source === stream) follow(id, version) }, delay)
  }
  stream.addEventListener('ping', () => { if (version === revision && source === stream) armWatchdog(version, stream) })
  stream.addEventListener('group-event', () => {
    if (version !== revision || source !== stream) return
    armWatchdog(version, stream)
    scheduleRefresh(id, version)
  })
}
// 替换可能已静默失效的连接；连接打开后会补读一次快照。
function restartStream() {
  const id = selectedId.value
  if (!id) return
  source?.close(); source = null
  clearTimeout(reconnectTimer); reconnectTimer = undefined
  follow(id, revision)
}
// 后台标签页的计时器可能被节流，回到前台或网络恢复时按最近消息或建连时间补做一次存活检查。
function resumeStream(event: Event) {
  if (!selectedId.value || document.visibilityState !== 'visible') return
  if (event.type === 'online') reconnectDelay = 1500
  if (event.type === 'online' || Date.now() - lastStreamMessageAt > 15000) restartStream()
}
async function select(id: string) {
  const version = ++revision
  source?.close(); source = null
  clearTimeout(reconnectTimer); clearTimeout(watchdogTimer); reconnectDelay = 1500
  clearTimeout(refreshTimer); refreshTimer = undefined
  // 清空输入，避免发给上一个群的内容被误发到新群。
  selectedId.value = id; snapshot.value = null; processes.value = {}; error.value = ''; text.value = ''; requestId = ''
  approvals.value = {}; approvalKeys = {}
  // 最后一个群被删除时没有工作室可看，退回聊天以解除侧栏锁定。
  if (!id && view.value === 'studio') void setView('chat')
  try { localStorage.setItem('harness.group', id) } catch { /* 无本地存储时不记住选择 */ }
  if (!id) return
  try {
    await refresh(id, version)
    if (version === revision) follow(id, version)
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 404) await groupDeleted(id)
    else if (version === revision) error.value = message(cause, '读取群聊失败')
  }
}
async function groupDeleted(id: string) {
  groups.value = groups.value.filter(item => item.group.id !== id)
  if (selectedId.value === id) await select(groups.value[0]?.group.id || '')
}
async function groupSaved(value: GroupSnapshot) {
  const exists = groups.value.some(item => item.group.id === value.group.id)
  const summary = { group: value.group, members: value.members.length, busy: value.busy }
  groups.value = exists ? groups.value.map(item => item.group.id === value.group.id ? summary : item) : [summary, ...groups.value]
  if (!exists) await select(value.group.id)
  else if (selectedId.value === value.group.id) snapshot.value = value
}
async function send() {
  const content = text.value.trim()
  if (!snapshot.value || sending.value || !content) return
  const id = snapshot.value.group.id
  const version = revision
  requestId ||= crypto.randomUUID()
  sending.value = true; error.value = ''
  try {
    await api<GroupMessage>(`/groups/${id}/messages`, { requestId, content })
    if (version !== revision) return
    text.value = ''; requestId = ''
    // 消息已送达；随后的刷新失败交给重试，不报告为发送失败。
    scheduleRefresh(id, version, 0)
  } catch (cause) {
    if (version !== revision) return
    if (cause instanceof ApiError && cause.status < 500) requestId = ''
    error.value = message(cause, '发送失败')
  } finally { sending.value = false }
}
async function stop() {
  if (!snapshot.value || stopping.value) return
  const id = snapshot.value.group.id
  stopping.value = true; error.value = ''
  try { await api(`/groups/${id}/stop`, {}); scheduleRefresh(id, revision) }
  catch (cause) { error.value = message(cause, '停止失败') }
  finally { stopping.value = false }
}
async function toggleProcess(item: GroupMessage) {
  const run = runOf(item.runId)
  if (!item.runId || !run) return
  if (item.runId in processes.value) { const next = { ...processes.value }; delete next[item.runId]; processes.value = next; return }
  processes.value = { ...processes.value, [item.runId]: null }
  try {
    const value = await api<Snapshot>(`/sessions/${run.sessionId}`)
    if (item.runId in processes.value) processes.value = { ...processes.value, [item.runId]: value.runs.find(entry => entry.id === item.runId) ?? null }
  } catch (cause) { error.value = message(cause, '读取过程失败') }
}
/** 读取工作室需要的审核状态；失败的运行清除缓存键，下次刷新再试。 */
async function loadApprovals(value: GroupSnapshot, version: number) {
  const stale = approvalTargets(value).filter(run => approvalKeys[run.id] !== `${run.status}:${run.activity}`)
  await Promise.all(stale.map(async run => {
    const key = `${run.status}:${run.activity}`
    approvalKeys[run.id] = key
    try {
      const [commands, changes] = await Promise.all([
        activeStatuses.includes(run.status) ? api<CommandExecution[]>(`/runs/${run.id}/commands`) : Promise.resolve([]),
        api<FileChange[]>(`/runs/${run.id}/file-changes`),
      ])
      if (version !== revision || approvalKeys[run.id] !== key) return
      approvals.value = { ...approvals.value, [run.id]: { commands: commands.map(item => item.status), changes: changes.map(item => item.status) } }
    } catch {
      if (version === revision && approvalKeys[run.id] === key) delete approvalKeys[run.id]
    }
  }))
}
watch([snapshot, view], ([value, current]) => { if (value && current === 'studio') void loadApprovals(value, revision) })
async function setView(value: 'chat' | 'studio') {
  view.value = value
  emit('studio', value === 'studio')
  await nextTick()
  // 两种视图的输入框行数不同，按新布局重算高度；回到聊天时停在最新消息处。
  resizeInput()
  if (value === 'chat') timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' })
}
function trackCaret() { caret.value = input.value?.selectionStart ?? text.value.length; mentionIndex.value = 0 }
async function pickMention(member: GroupMember) {
  const value = insertMention(text.value, caret.value, member.name)
  text.value = value.text
  await nextTick()
  input.value?.focus()
  input.value?.setSelectionRange(value.caret, value.caret)
  trackCaret()
}
// 点击成员头像直接 @ 该成员。
async function mentionMember(member: GroupMember) {
  if (!member.enabled) return
  const prefix = text.value && !/\s$/.test(text.value) ? ' ' : ''
  text.value = `${text.value}${prefix}@${member.name} `
  await nextTick()
  input.value?.focus()
  trackCaret()
}
function onKeydown(event: KeyboardEvent) {
  if (candidates.value.length && !event.isComposing) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      mentionIndex.value = (mentionIndex.value + step + candidates.value.length) % candidates.value.length
      return
    }
    if (event.key === 'Enter' || event.key === 'Tab') { event.preventDefault(); void pickMention(candidates.value[mentionIndex.value]); return }
    if (event.key === 'Escape') { event.preventDefault(); caret.value = -1; return }
  }
  if (!shouldSendPrompt(event, preferences.value.sendShortcut)) return
  event.preventDefault()
  void send()
}
function resizeInput() {
  const node = input.value
  if (!node) return
  node.style.height = 'auto'
  node.style.height = `${Math.min(node.scrollHeight, 200)}px`
}
// 内容改变后视为新消息，不再沿用上一次待确认的 requestId。
watch(text, () => { requestId = ''; void nextTick(resizeInput) })
onMounted(async () => {
  document.addEventListener('visibilitychange', resumeStream)
  window.addEventListener('online', resumeStream)
  try {
    templates.value = await api<GroupTemplate[]>('/group-templates')
    await loadGroups()
    let stored = ''
    try { stored = localStorage.getItem('harness.group') || '' } catch { /* 忽略 */ }
    await select(groups.value.find(item => item.group.id === stored)?.group.id || groups.value[0]?.group.id || '')
  } catch (cause) { error.value = message(cause, '读取群聊失败') }
  finally { loading.value = false }
  // SSE 可能在后端重启后静默断开；协作进行中定时补读快照。
  pollTimer = setInterval(() => { if (snapshot.value?.busy) scheduleRefresh(snapshot.value.group.id, revision) }, 4000)
})
onUnmounted(() => {
  if (view.value === 'studio') emit('studio', false)
  revision++
  source?.close()
  clearTimeout(reconnectTimer); clearTimeout(watchdogTimer); clearTimeout(refreshTimer); clearInterval(pollTimer)
  document.removeEventListener('visibilitychange', resumeStream)
  window.removeEventListener('online', resumeStream)
})
</script>

<template>
  <main class="main-panel groups-page" aria-labelledby="groups-title">
    <header class="workspace-header">
      <div class="workspace-title"><button v-if="view === 'studio'" type="button" class="icon-button" aria-label="返回聊天" title="返回聊天" @click="setView('chat')"><UiIcon name="back" /></button><button v-else-if="sidebarCollapsed" class="icon-button" aria-label="展开侧栏" title="展开侧栏" @click="emit('expandSidebar')"><UiIcon name="panel" /></button><span class="header-breadcrumb">工作台 <span aria-hidden="true">/</span></span><h1 id="groups-title">{{ snapshot?.group.name || '群聊' }}</h1></div>
      <div class="header-meta">
        <div v-if="snapshot" class="group-view-switch" role="group" aria-label="群聊视图">
          <button type="button" :aria-pressed="view === 'chat'" @click="setView('chat')"><UiIcon name="chat" :size="14" />聊天</button>
          <button type="button" :aria-pressed="view === 'studio'" @click="setView('studio')"><UiIcon name="users" :size="14" />工作室</button>
        </div>
        <span v-if="snapshot" class="readonly-label">{{ workspaceName(snapshot.group.workspaceId) }}</span>
        <button v-if="snapshot" type="button" class="icon-button" aria-label="群聊设置" title="群聊设置" @click="settings = 'edit'"><UiIcon name="settings" :size="16" /></button>
      </div>
    </header>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <div class="groups-body" :class="{ 'is-studio': view === 'studio' }" :aria-busy="loading">
      <nav v-if="view === 'chat'" class="group-list" aria-label="群聊列表">
        <button type="button" class="primary group-create" :disabled="loading" @click="settings = 'create'"><UiIcon name="plus" :size="15" />新建群聊</button>
        <button v-for="item in groups" :key="item.group.id" type="button" class="group-item" :aria-current="item.group.id === selectedId ? 'true' : undefined" @click="select(item.group.id)">
          <span class="group-avatar" :style="{ background: item.group.color }" aria-hidden="true"><UiIcon name="users" :size="15" /></span>
          <span class="group-item-copy"><strong>{{ item.group.name }}</strong><small>{{ item.members }} 位成员 · {{ workspaceName(item.group.workspaceId) }}</small></span>
          <span v-if="item.busy" class="activity-dot" aria-label="协作中"></span>
        </button>
      </nav>
      <section class="group-chat" :aria-label="snapshot?.group.name || '群聊'">
        <p v-if="loading" class="automations-empty"><span class="loading-indicator"></span>正在读取群聊…</p>
        <div v-else-if="!groups.length" class="automations-empty">
          <UiIcon name="users" :size="26" />
          <h2>让 AI 成员一起协作</h2>
          <button type="button" class="primary" @click="settings = 'create'"><UiIcon name="plus" :size="15" />新建群聊</button>
        </div>
        <template v-else-if="snapshot">
          <GroupStudio v-if="view === 'studio'" :snapshot="snapshot" :approvals="approvals" @mention="mentionMember" />
          <template v-else>
          <div class="group-roster" aria-label="群成员">
            <button v-for="member in snapshot.members" :key="member.id" type="button" class="group-chip" :class="{ disabled: !member.enabled }" :disabled="!member.enabled" :title="`${member.focus}\n点击 @ ${member.name}`" @click="mentionMember(member)"><span class="group-avatar small" :style="{ background: member.color }" aria-hidden="true">{{ avatarText(member.name) }}</span>{{ member.name }}</button>
          </div>
          <details v-if="snapshot.tasks.length" class="group-tasks" open>
            <summary>任务计划<span>{{ finishedTasks }} / {{ snapshot.tasks.length }} 已完成</span></summary>
            <ol>
              <li v-for="(step, index) in steps" :key="index">
                <span class="group-task-step">第 {{ index + 1 }} 步</span>
                <ul>
                  <li v-for="task in step" :key="task.id" class="group-task" :class="`is-${task.status.toLowerCase()}`">
                    <span class="group-task-status">{{ taskLabel(task.status) }}</span>
                    <span class="group-task-copy"><strong>{{ task.title }}</strong><small class="group-task-instruction">{{ task.instruction }}</small><small>{{ task.memberName }}<template v-if="task.attempts > 1"> · 第 {{ task.attempts }} 次尝试</template><template v-if="task.detail"> · {{ task.detail }}</template></small></span>
                  </li>
                </ul>
              </li>
            </ol>
          </details>
          <div ref="timeline" class="timeline group-timeline" aria-label="群聊记录" aria-live="polite">
            <div v-if="!snapshot.messages.length" class="welcome"><div class="welcome-copy"><h2>说说你的问题或需求</h2><p class="welcome-description">输入 @昵称 可点名成员</p></div></div>
            <template v-for="item in snapshot.messages" :key="item.id">
              <div v-if="item.author === 'USER'" class="user-message-wrap"><p class="user-message">{{ item.content }}</p></div>
              <p v-else-if="item.author === 'SYSTEM'" class="group-system">{{ item.content }}</p>
              <article v-else class="group-message">
                <span class="group-avatar" :style="{ background: memberOf(item)?.color || 'var(--muted)' }" aria-hidden="true">{{ avatarText(item.authorName) }}</span>
                <div class="group-message-body">
                  <div class="group-message-heading"><strong>{{ item.authorName }}</strong><span v-if="taskOf(item.runId)" class="group-task-tag">{{ taskOf(item.runId)!.title }}</span><time>{{ time(item.createdAt) }}</time><button v-if="runOf(item.runId)" type="button" class="group-process" :aria-expanded="!!item.runId && item.runId in processes" @click="toggleProcess(item)">{{ item.runId && item.runId in processes ? '收起过程' : '查看过程' }}</button></div>
                  <div class="group-bubble"><MarkdownContent :content="item.content" /></div>
                  <div v-if="item.runId && item.runId in processes" class="group-process-panel">
                    <RunTimeline v-if="processes[item.runId]" :run="processes[item.runId]!" :revision="processes[item.runId]!.status" />
                    <p v-else class="field-note">正在读取过程…</p>
                  </div>
                  <template v-if="runOf(item.runId)?.workspace">
                    <FileChanges :run-id="item.runId!" :revision="`${runOf(item.runId)!.status}:${runOf(item.runId)!.activity}`" :can-apply="runOf(item.runId)!.status === 'COMPLETED' && !snapshot.busy" :can-revert="!snapshot.busy" />
                    <Commands :run-id="item.runId!" :revision="`${runOf(item.runId)!.status}:${runOf(item.runId)!.activity}`" :active="false" />
                  </template>
                </div>
              </article>
            </template>
            <article v-for="{ member, run } in speaking" :key="run.id" class="group-message typing">
              <span class="group-avatar" :style="{ background: member.color }" aria-hidden="true">{{ avatarText(member.name) }}</span>
              <div class="group-message-body">
                <div class="group-message-heading"><strong>{{ member.name }}</strong><span class="working-label"><span class="activity-dot"></span>{{ run.status === 'CANCELLING' ? '正在停止' : '正在输入…' }}</span></div>
                <Commands v-if="run.workspace" :run-id="run.id" :revision="`${run.status}:${run.activity}`" :active="run.status === 'RUNNING'" />
              </div>
            </article>
            <p v-if="snapshot.busy && !speaking.length" class="group-system"><span class="activity-dot"></span>{{ waitingText }}</p>
            <p v-if="notice" class="run-notice" :class="{ 'has-error': notice.error }">{{ notice.text }}</p>
          </div>
          </template>
          <div class="composer-dock" :class="{ 'is-floating': view === 'studio' }">
            <form class="composer" @submit.prevent="send">
              <div v-if="view === 'studio'" class="composer-members" role="group" aria-label="点名成员">
                <button v-for="member in snapshot.members.filter(item => item.enabled)" :key="member.id" type="button" :style="{ background: member.color }" :title="`@${member.name}`" :aria-label="`@${member.name}`" @click="mentionMember(member)">{{ avatarText(member.name) }}</button>
              </div>
              <ul v-if="candidates.length" id="group-mentions" class="group-mentions" role="listbox" aria-label="选择要 @ 的成员">
                <li v-for="(member, index) in candidates" :id="`group-mention-${member.id}`" :key="member.id" role="option" :aria-selected="index === mentionIndex" @mousedown.prevent="pickMention(member)"><span class="group-avatar small" :style="{ background: member.color }" aria-hidden="true">{{ avatarText(member.name) }}</span>{{ member.name }}</li>
              </ul>
              <label class="sr-only" for="group-input">发送群消息</label>
              <textarea id="group-input" ref="input" v-model="text" :rows="view === 'studio' ? 1 : 2" maxlength="4000" :disabled="sending" :placeholder="view === 'studio' ? '对团队说点什么，@ 点名成员…' : '提出问题或需求，输入 @ 点名成员…'" role="combobox" aria-autocomplete="list" :aria-expanded="candidates.length > 0" :aria-controls="candidates.length ? 'group-mentions' : undefined" :aria-activedescendant="candidates[mentionIndex] ? `group-mention-${candidates[mentionIndex].id}` : undefined" @keydown="onKeydown" @input="trackCaret" @click="trackCaret" @keyup.left="trackCaret" @keyup.right="trackCaret" @blur="caret = -1"></textarea>
              <div class="composer-actions">
                <span class="group-hint">{{ snapshot.busy ? '发送新消息会让成员转向新问题' : '' }}</span>
                <button v-if="snapshot.busy" type="button" class="send-button stop-button" :disabled="stopping" aria-label="停止本轮协作" title="停止本轮协作" @click="stop"><UiIcon name="stop" :size="15" /></button>
                <button type="submit" class="send-button" :disabled="sending || !text.trim()" aria-label="发送消息" :title="sendHint"><UiIcon name="arrow" :size="18" /></button>
              </div>
            </form>
            <div class="composer-caption"><span v-if="text.length > 3500">{{ text.length }} / 4000</span><span v-else-if="view === 'studio'" class="studio-summary" :class="{ 'has-attention': summary.attention }">{{ summary.text }}</span><span v-else>{{ sendHint }}</span></div>
          </div>
        </template>
      </section>
    </div>
    <GroupSettingsDialog v-if="settings" :key="settings === 'edit' ? snapshot?.group.id : 'create'" :snapshot="settings === 'edit' ? snapshot ?? undefined : undefined" :workspaces="workspaces" :profiles="profiles" :templates="templates" :default-profile-id="defaultProfileId" @saved="groupSaved" @deleted="groupDeleted" @close="settings = ''" />
  </main>
</template>

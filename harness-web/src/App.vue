<script setup lang="ts">
import DesktopUpdateNotice from './DesktopUpdateNotice.vue'
import TaskCenter from './TaskCenter.vue'
import { connectTaskActivity, taskActivity } from './taskActivity'
import type { TaskActivity } from './taskActivityModel'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import AppSidebar from './AppSidebar.vue'
import ChatRunCard from './ChatRunCard.vue'
import PluginCommandPicker from './PluginCommandPicker.vue'
import { externalCommandAlias } from './pluginCommands'
import type { ExternalAgentCommand } from './api'
import MentionPicker from './MentionPicker.vue'
import ComposerAddMenu from './ComposerAddMenu.vue'
import ComposerAttachments from './ComposerAttachments.vue'
import PromptOptimizer from './PromptOptimizer.vue'
import SettingsPage from './SettingsPage.vue'
import AutomationsPage from './AutomationsPage.vue'
import MemoriesPage from './MemoriesPage.vue'
import GroupChatsPage from './GroupChatsPage.vue'
import FlowsPage from './FlowsPage.vue'
import ComposerSelectors from './ComposerSelectors.vue'
import TokenUsageRing from './TokenUsageRing.vue'
import WorkspaceSettings from './WorkspaceSettings.vue'
import UiIcon from './UiIcon.vue'
import { modifierKeyLabel, preferences, shouldSendPrompt } from './preferences'
import { api, ApiError, isActive, normalizeReasoningDepth, upload, type Run, type RunEvent,
  type Session, type Snapshot, type Submission, type ModelProfile, type Workspace, type ApprovalMode } from './api'
import { addMention, attachmentProblem, attachmentsOnlyPrompt, droppedFiles, isImage, isLongPaste, mentionLabel, MAX_ATTACHMENTS, MAX_MENTIONS, nextPastedName, removeFileMentionQuery,
  type Attachment, type DraftAttachment, type FileEntry } from './composer'
import { draftSession } from './workspaces'

const sessions = ref<Session[]>([])
const workspaces = ref<Workspace[]>([])
const editingWorkspace = ref<Workspace>()
const profiles = ref<ModelProfile[]>([])
const selectedProfileId = ref('default')
const reasoningEffort = ref('low')
const approvalMode = ref<ApprovalMode>('MANUAL')
const selectedProfile = computed(() => profiles.value.find(profile => profile.id === selectedProfileId.value))
const model = computed(() => selectedProfile.value?.model)
const showSettings = ref(false)
const showAutomations = ref(false)
const showMemories = ref(false)
const showGroups = ref(false)
const showFlows = ref(false)
const activePage = computed<'chat' | 'automations' | 'memories' | 'groups' | 'flows'>(() =>
  showAutomations.value ? 'automations' : showMemories.value ? 'memories' : showGroups.value ? 'groups' : showFlows.value ? 'flows' : 'chat',
)
const groupPage = ref<{ openTask(id: string): Promise<void>; navigationBlocked: boolean }>()
const flowPage = ref<{ openTask(flowId: string, itemId?: string): Promise<void> }>()
const waitingFlowCount = computed(() => taskActivity.value.active.filter(task => task.source === 'FLOW' && task.status.startsWith('WAITING_')).length)
const taskNavigationBlocked = computed(() => submitting.value || loading.value || settingsBusy.value || workspaceChanging.value || !!groupPage.value?.navigationBlocked)
const queuedTask = ref<TaskActivity>()
let disconnectTaskActivity = () => {}
const settingsSection = ref<'general' | 'models' | 'workspace' | 'approval' | 'memory' | 'updates' | 'plugins' | 'agents'>('general')
const fontSize = computed(() => ({ small: 13, standard: 14, large: 16 })[preferences.value.chatFontSize])
// 根字号控制全局文字，辅助文字仍保留最小可读尺寸。
watch(fontSize, value => {
  document.documentElement.style.setProperty('--app-font-size', `${value}px`)
}, { immediate: true })
const sendShortcutHint = computed(() => preferences.value.sendShortcut === 'enter' ? 'Enter 发送，Shift + Enter 换行' : `${modifierKeyLabel} + Enter 发送`)
const creatingWorkspace = ref(false)
const settingsBusy = ref(false)
const workspaceSaving = ref(false)
const workspaceSyncing = ref(false)
const workspaceChanging = computed(() => workspaceSaving.value || workspaceSyncing.value)
const workspaceDialog = ref<HTMLDialogElement>()
const browsingWorkspace = ref(false)
const sidebarCollapsed = ref(window.innerWidth <= 780)
const savedSidebarWidth = Number(storageGet('harness.sidebar.width'))
const sidebarWidth = ref(Number.isFinite(savedSidebarWidth) && savedSidebarWidth > 0 ? Math.max(200, Math.min(400, savedSidebarWidth)) : 248)
watch(sidebarWidth, value => storageSet('harness.sidebar.width', String(value)))
// 群聊工作室需要整屏空间：进入时收起并锁定侧栏，离开时恢复原来的展开状态。
const groupStudio = ref(false)
let sidebarBeforeStudio = false
function setGroupStudio(active: boolean) {
  if (active === groupStudio.value) return
  if (active) sidebarBeforeStudio = sidebarCollapsed.value
  groupStudio.value = active
  sidebarCollapsed.value = active || sidebarBeforeStudio || isMobile.value
}
watch(sidebarCollapsed, value => { if (groupStudio.value && !value) sidebarCollapsed.value = true })
let titleRevision = 0
// 仅同步标题，保留可能同时刷新的工作区和运行记录。
function sessionRenamed(session: Session) {
  titleRevision++
  sessions.value = sessions.value.map(value => value.id === session.id ? { ...value, title: session.title } : value)
  if (snapshot.value?.session.id === session.id) snapshot.value.session.title = session.title
}
// 删除成功后移除列表项；删除的是当前会话时，断开实时连接并切换到下一个会话或回到空白页。
async function sessionDeleted(session: { id: string }) {
  sessions.value = sessions.value.filter(value => value.id !== session.id)
  storageSet(`harness.pending.${session.id}`, null)
  saveDraft(session.id, '')
  if (session.id !== selectedId.value) return
  clearComposerInputs()
  const next = sessions.value[0]?.id
  if (next) { await selectSession(next); return }
  revision++
  source?.close(); source = null
  clearTimeout(reconnectTimer); clearTimeout(watchdogTimer); clearTimeout(refreshTimer); refreshTimer = undefined
  refreshing = false; dirty = false; connected.value = false
  selectedId.value = ''; snapshot.value = null; pending.value = null
  prompt.value = readDraft('')
  toolRevisions.value = {}; stepRevisions.value = {}; changeRevisions.value = {}; commandRevisions.value = {}; questionRevisions.value = {}
  error.value = ''
  storageSet('harness.session', null)
}
const mobileLayout = window.matchMedia('(max-width: 780px)')
const isMobile = ref(mobileLayout.matches)
function onLayoutChange() { isMobile.value = mobileLayout.matches; if (isMobile.value) sidebarCollapsed.value = true }
const promptInput = ref<HTMLTextAreaElement>()
const optimizingPrompt = ref(false)
const optimizationError = ref('')
const pluginCommandPicker = ref<InstanceType<typeof PluginCommandPicker>>()
const mentionPicker = ref<InstanceType<typeof MentionPicker>>()
const draftAttachments = ref<DraftAttachment[]>([])
const draftMentions = ref<FileEntry[]>([])
const sentAttachments = ref<Attachment[]>([])
let sentAttachmentsKey = ''
const multiRoot = computed(() => (workspaces.value.find(value => value.id === snapshot.value?.session.workspaceId)?.directories.length || 0) > 1)
// 上传中或上传失败的附件需要处理后才能发送。
const attachmentsBlocked = computed(() => draftAttachments.value.some(item => !item.id || item.error))
// 正文、引用或附件任一项非空即可发送。
const hasDraftContent = computed(() => !!prompt.value.trim() || draftMentions.value.length > 0 || draftAttachments.value.length > 0)
// 只有正在输入时才提示可发送，切到带草稿的会话不弹跳。
const sendReady = ref(false)
// 本次发送的消息播放一次出场，历史消息不播放。
const freshRunId = ref('')
const attachmentsOf = (runId: string) => sentAttachments.value.filter(item => item.runId === runId)
// 选择、粘贴或拖入后立即上传，失败的附件保留错误提示，移除后才能发送。
function addFiles(files: File[]) {
  for (const file of files) {
    if (draftAttachments.value.length >= MAX_ATTACHMENTS) { error.value = `每条消息最多附带 ${MAX_ATTACHMENTS} 个附件`; break }
    const item: DraftAttachment = { key: crypto.randomUUID(), name: file.name, size: file.size, mediaType: file.type,
      preview: isImage(file.type) ? URL.createObjectURL(file) : '', id: '', error: attachmentProblem(file) || '' }
    draftAttachments.value = [...draftAttachments.value, item]
    if (!item.error) void uploadAttachment(item.key, file)
  }
}
async function uploadAttachment(key: string, file: File) {
  try {
    const saved = await upload<Attachment>('/attachments', file)
    draftAttachments.value = draftAttachments.value.map(item => item.key === key ? { ...item, id: saved.id, name: saved.name, mediaType: saved.mediaType } : item)
  } catch (cause) {
    draftAttachments.value = draftAttachments.value.map(item => item.key === key ? { ...item, error: cause instanceof Error ? cause.message : '上传失败' } : item)
  }
}
function removeAttachment(key: string) {
  const item = draftAttachments.value.find(value => value.key === key)
  if (item?.preview) URL.revokeObjectURL(item.preview)
  draftAttachments.value = draftAttachments.value.filter(value => value.key !== key)
}
// 消息发出或切换会话后清空附件与引用。
function clearComposerInputs() {
  for (const item of draftAttachments.value) if (item.preview) URL.revokeObjectURL(item.preview)
  draftAttachments.value = []; draftMentions.value = []
}
// Office 等应用复制文字时会附带一张位图，带文本的粘贴按普通文字处理。
function onPromptPaste(event: ClipboardEvent) {
  // 长文本不塞进输入框，转成文本附件卡片随消息发送。
  const pasted = event.clipboardData?.getData('text/plain') ?? ''
  if (isLongPaste(pasted)) {
    event.preventDefault()
    addFiles([new File([pasted], nextPastedName(draftAttachments.value.map(item => item.name)), { type: 'text/plain' })])
    return
  }
  if (event.clipboardData?.types.includes('text/plain')) return
  const files = droppedFiles(event.clipboardData?.items)
  if (!files.length) return
  event.preventDefault(); addFiles(files)
}
// 只接管拖入的文件，拖入文字保持输入框原有行为。
function onComposerDragover(event: DragEvent) { if (event.dataTransfer?.types.includes('Files')) event.preventDefault() }
function onComposerDrop(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  if (optimizingPrompt.value || submitting.value || pending.value || loading.value || workspaceChanging.value) return
  addFiles(droppedFiles(event.dataTransfer.items))
}
// 选中的文件以卡片显示在输入框上方，并从正文中删掉正在输入的 @ 文本。
async function pickMention(entry: FileEntry) {
  const input = promptInput.value
  const mentions = addMention(draftMentions.value, entry)
  if (mentions.length > MAX_MENTIONS) { error.value = `每条消息最多引用 ${MAX_MENTIONS} 个文件或目录`; return }
  const value = removeFileMentionQuery(prompt.value, input?.selectionStart ?? prompt.value.length)
  prompt.value = value.text
  draftMentions.value = mentions
  await nextTick(); input?.focus(); input?.setSelectionRange(value.caret, value.caret)
}
function removeMention(entry: FileEntry) {
  draftMentions.value = draftMentions.value.filter(value => value.root !== entry.root || value.path !== entry.path)
}
// "+" 菜单插入 @ 或 /，先放好光标再聚焦，由对应的候选菜单接手。
async function startMention() {
  const input = promptInput.value
  const caret = input?.selectionStart ?? prompt.value.length
  const before = prompt.value.slice(0, caret)
  const inserted = before && !/\s$/.test(before) ? ' @' : '@'
  prompt.value = before + inserted + prompt.value.slice(caret)
  await nextTick(); input?.setSelectionRange(caret + inserted.length, caret + inserted.length); input?.focus()
}
// 已有正文时不改写，命令只能写在开头，空输入框才插入 /。
async function startCommand() {
  if (prompt.value && !prompt.value.startsWith('/')) { error.value = '斜杠命令需写在消息开头，请先清空输入框或在开头输入 /'; return }
  if (!prompt.value) prompt.value = '/'
  await nextTick(); promptInput.value?.setSelectionRange(1, 1); promptInput.value?.focus()
}
// 运行数量变化时重新读取会话附件，只采纳最新一次请求，失败时等下次刷新重试。
let sentAttachmentsRequest = 0
async function loadSentAttachments(id: string, version: number, runs: number) {
  const key = `${id}:${runs}`
  if (key === sentAttachmentsKey) return
  const request = ++sentAttachmentsRequest
  try {
    const values = await api<Attachment[]>(`/sessions/${id}/attachments`)
    if (version === revision && request === sentAttachmentsRequest) { sentAttachments.value = values; sentAttachmentsKey = key }
  } catch { /* 下次刷新重试 */ }
}
const suggestions = [
  { icon: 'folder', title: '了解项目', description: '从结构到入口，建立全局视图', prompt: '查看项目结构，读取构建配置，并结合源码解释程序入口。' },
  { icon: 'scan', title: '定位问题', description: '从现象出发，找到问题所在', prompt: '请帮我定位一个问题，先告诉我需要提供哪些错误信息和复现步骤。' },
  { icon: 'branch', title: '梳理调用链', description: '连接模块，理清执行脉络', prompt: '分析项目的核心模块和调用关系，标明关键类与方法。' },
]
function useSuggestion(value: string) { prompt.value = value; promptInput.value?.focus() }
// 输入框随草稿增长，超过上限后在框内滚动。
function resizePrompt() {
  const input = promptInput.value
  if (!input) return
  // 发送过渡中正文已显示在对话区，输入框按空内容收起。
  if (launching.value) { input.style.height = ''; return }
  input.style.height = 'auto'
  input.style.height = `${Math.min(input.scrollHeight, 200)}px`
}
watch([fontSize, showSettings, showAutomations, showMemories, showGroups, showFlows], () => { if (!showSettings.value && !showAutomations.value && !showMemories.value && !showGroups.value && !showFlows.value) void nextTick(resizePrompt) })
// 根据常规偏好处理发送，输入法确认回车保留给输入框。
function onPromptKeydown(event: KeyboardEvent) {
  if (pluginCommandPicker.value?.keydown(event) || mentionPicker.value?.keydown(event)) return
  if (!shouldSendPrompt(event, preferences.value.sendShortcut)) return
  event.preventDefault()
  void send()
}
// 从工作区菜单打开目录选择弹窗。
async function browseWorkspace(workspace?: Workspace) {
  if (current.value || submitting.value || pending.value || loading.value || workspaceChanging.value) return
  creatingWorkspace.value = !snapshot.value
  editingWorkspace.value = workspace
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
async function chooseWorkspace(workspaceId: string) {
  if (current.value || submitting.value || pending.value || loading.value || workspaceChanging.value || workspaceId === (snapshot.value?.session.workspaceId || '')) return
  creatingWorkspace.value = !snapshot.value
  workspaceSyncing.value = true
  error.value = ''
  try {
    const session = selectedId.value
      ? await api<Session>(`/sessions/${selectedId.value}/workspace`, { workspaceId })
      : draftSession(workspaces.value.find(value => value.id === workspaceId))
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
function openSettings(section: 'general' | 'models' | 'workspace' | 'approval' | 'memory' | 'updates' | 'plugins' | 'agents' = 'general', creating = false) {
  if (workspaceChanging.value) return
  settingsSection.value = section
  creatingWorkspace.value = creating
  showSettings.value = true
}
function closeSettings() {
  if (showSettings.value) void loadExternalAgents()
  showSettings.value = false
  settingsBusy.value = false
}
function navigateSession(id: string) {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = false
  showMemories.value = false
  showGroups.value = false
  showFlows.value = false
  if (id === selectedId.value && snapshot.value && !loading.value) closeSettings()
  else { clearComposerInputs(); void selectSession(id) }
}
const toolRevisions = ref<Record<string, number>>({})
const stepRevisions = ref<Record<string, number>>({})
const changeRevisions = ref<Record<string, number>>({})
const commandRevisions = ref<Record<string, number>>({})
const questionRevisions = ref<Record<string, number>>({})
// 时间线异步加载会改变高度，跟随最新时重新贴底。
function timelineLoaded() {
  if (!preferences.value.autoScroll || awayFromLatest.value) return
  timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' })
  trackTimeline()
}
// 输入框用量圆环以最近一次带 token 预算的请求为准。
const latestContext = computed(() => [...(snapshot.value?.runs ?? [])].reverse().find(run => !!run.context?.maxTokens)?.context ?? null)
const snapshot = ref<Snapshot | null>(null)
const selectedId = ref('')
const draftCache = new Map<string, string>()
const prompt = ref(readDraft(''))
const error = ref('')
const connected = ref(false)
const loading = ref(false)
const submitting = ref(false)
const cancelling = ref(false)
const pending = ref<Submission | null>(null)
const timeline = ref<HTMLElement>()
const awayFromLatest = ref(false)
const current = computed(() => snapshot.value?.runs.find(isActive))
watch(prompt, resizePrompt, { flush: 'post' })
// 同步保存，避免会话切换后把上一条草稿写入新会话。
watch(prompt, value => saveDraft(selectedId.value, value), { flush: 'sync' })
// 在当前滚动容器内恢复跟读，不改变用户的自动跟随偏好。
function trackTimeline() {
  const node = timeline.value
  awayFromLatest.value = !!node && node.scrollHeight - node.scrollTop - node.clientHeight >= 100
}
function goToLatest() {
  timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' })
  trackTimeline()
}
let source: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | undefined
let watchdogTimer: ReturnType<typeof setTimeout> | undefined
let reconnectDelay = 1500
let lastStreamMessageAt = 0
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
function chooseProfile(id: string) { selectedProfileId.value = id; reasoningEffort.value = normalizeReasoningDepth(selectedProfile.value?.reasoningEffort || '') }

function storageGet(key: string) {
  try { return localStorage.getItem(key) } catch { return null }
}
function storageSet(key: string, value: string | null) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch { /* The current tab still works without local storage. */ }
}
// 存储不可用时仍在当前标签页保留每个会话的草稿。
function readDraft(id: string) { return draftCache.get(id) ?? storageGet(`harness.draft.${id || 'home'}`) ?? '' }
function saveDraft(id: string, value: string) {
  draftCache.set(id, value)
  storageSet(`harness.draft.${id || 'home'}`, value || null)
}
function setPending(value: Submission | null) {
  pending.value = value
  storageSet(`harness.pending.${selectedId.value}`, value ? JSON.stringify(value) : null)
}
// 从欢迎页发送时立即切到对话布局，用占位消息在对话区等待真实记录返回。
const launching = ref<{ text: string; attachments: DraftAttachment[]; agentName?: string } | null>(null)
const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
let landing: Animation[] = []
function composerBox() {
  const input = promptInput.value
  const box = input?.closest('.composer')?.getBoundingClientRect()
  return input && box ? { top: box.top, bottom: box.bottom, inputHeight: input.offsetHeight } : undefined
}
/** 布局切换后，让输入框落到新位置，对话内容同时从输入框处升起。 */
function landComposer(from: NonNullable<ReturnType<typeof composerBox>>) {
  // 提交很快失败时上一段还在播放：from 已记下当前视觉位置，先取消才能量到静止位置。
  landing.forEach(animation => animation.cancel())
  landing = []
  if (prefersReducedMotion()) return
  resizePrompt()
  const to = composerBox()
  if (!to) return
  const timing = { duration: 380, easing: 'cubic-bezier(.2, .8, .2, 1)' }
  const dock = promptInput.value?.closest('.composer-dock')?.animate([{ transform: `translateY(${from.bottom - to.bottom}px)` }, { transform: 'none' }], timing)
  // 动画期间读到的是过渡中的高度，结束时按实际内容重算一次。
  const settle = promptInput.value?.animate([{ height: `${from.inputHeight}px` }, { height: `${to.inputHeight}px` }], timing)
  if (settle) settle.onfinish = resizePrompt
  // 动画放在滚动容器上，真实记录中途替换占位消息时不会中断。
  const messageTop = timeline.value?.querySelector('.user-message')?.getBoundingClientRect().top
  const rise = messageTop === undefined ? 0 : Math.max(0, from.top - messageTop)
  const content = timeline.value?.animate([{ opacity: 0, transform: `translateY(${rise}px)` }, { opacity: 1, offset: .7 }, { opacity: 1, transform: 'none' }], timing)
  landing = [dock, settle, content].filter(animation => animation !== undefined)
}
/** 欢迎内容已被布局移除，用定格的副本原地淡出。 */
function fadeOutWelcome() {
  if (prefersReducedMotion()) return
  const panel = timeline.value?.closest('.main-panel')
  for (const element of panel?.querySelectorAll<HTMLElement>('.welcome, .suggestion-list') ?? []) {
    const box = element.getBoundingClientRect()
    const ghost = element.cloneNode(true) as HTMLElement
    ghost.inert = true
    Object.assign(ghost.style, { position: 'fixed', top: `${box.top}px`, left: `${box.left}px`, width: `${box.width}px`, margin: '0', animation: 'none' })
    // 排在输入框之前，下落的输入框才能盖住副本。
    panel?.prepend(ghost)
    ghost.animate([{ opacity: 1 }, { opacity: 0, transform: 'translateY(-8px)' }], { duration: 110, easing: 'ease-out' }).onfinish = () => ghost.remove()
  }
}
function startLaunch() {
  const from = composerBox()
  fadeOutWelcome()
  launching.value = { text: pending.value?.prompt ?? draftText(), attachments: draftAttachments.value,
    agentName: externalDraft.value ? draftAgent.value?.description || draftAlias.value : undefined }
  if (from) void nextTick(() => landComposer(from))
}
// 提交失败时还没有对话记录，输入框要退回欢迎页。
function endLaunch() {
  if (!launching.value) return
  const from = snapshot.value?.runs.length ? undefined : composerBox()
  launching.value = null
  if (from) void nextTick(() => landComposer(from))
}
async function refresh(id: string, version: number) {
  const titles = titleRevision
  const value = await api<Snapshot>(`/sessions/${id}`)
  if (version !== revision || value.lastSeq < (snapshot.value?.lastSeq ?? 0)) return
  // 重命名完成前发出的刷新可能仍携带旧标题。
  if (titles !== titleRevision) value.session.title = sessions.value.find(session => session.id === id)?.title ?? value.session.title
  const node = timeline.value
  const initialLoad = !snapshot.value
  const scroll = initialLoad || (preferences.value.autoScroll && (!node || node.scrollHeight - node.scrollTop - node.clientHeight < 100))
  // 已发送附件要多一次请求才回来，先沿用占位卡片的附件，替换时不闪动。
  const launched = launching.value && !snapshot.value?.runs.length ? value.runs[0] : undefined
  if (launched) sentAttachments.value = launching.value!.attachments.map(({ id, name, mediaType, size }) => ({ id, name, mediaType, size, runId: launched.id, createdAt: launched.createdAt }))
  snapshot.value = value
  sessions.value = sessions.value.map(session => session.id === value.session.id ? value.session : session)
  if (error.value === '读取记录失败，正在重试。') error.value = ''
  if (pending.value && value.runs.some(run => run.requestId === pending.value?.requestId)) {
    setPending(null)
    prompt.value = ''
    clearComposerInputs()
  }
  void loadSentAttachments(id, version, value.runs.length)
  if (scroll) {
    await nextTick()
    if (initialLoad || preferences.value.autoScroll) timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' })
  }
  await nextTick()
  trackTimeline()
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
    catch (cause) {
      // 会话已在别处（如另一个标签页）被删除：停止重连并按删除处理。
      if (cause instanceof ApiError && cause.status === 404) void sessionDeleted({ id })
      else if (version === revision) { error.value = '读取记录失败，正在重试。'; dirty = true; retryDelay = 2000 }
    } finally {
      if (version === revision) {
        refreshing = false
        if (dirty) scheduleRefresh(id, version, retryDelay)
        // SSE can stay silently open across a backend restart; keep fetching while a run is active.
        else if (snapshot.value?.runs.some(isActive)) scheduleRefresh(id, version, 4000)
      }
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
  const stream = new EventSource(`/api/sessions/${id}/events?after=${snapshot.value?.lastSeq ?? 0}`)
  source = stream
  armWatchdog(version, stream)
  stream.onopen = () => {
    if (version !== revision || source !== stream) return
    reconnectDelay = 1500
    armWatchdog(version, stream)
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
    clearTimeout(watchdogTimer)
    clearTimeout(reconnectTimer)
    // 后端持续不可用时按指数退避重连，连接成功后恢复为 1.5 秒。
    const delay = reconnectDelay
    reconnectDelay = Math.min(reconnectDelay * 2, 15000)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = undefined
      if (version === revision && source === stream) follow(id, version)
    }, delay)
  }
  stream.addEventListener('ping', () => { if (version === revision && source === stream) armWatchdog(version, stream) })
  stream.addEventListener('run-event', (message: MessageEvent<string>) => {
    if (version !== revision || source !== stream) return
    armWatchdog(version, stream)
    const event = JSON.parse(message.data) as RunEvent
    if (event.type === 'TOOL' && event.runId) toolRevisions.value[event.runId] = event.seq
    // 子智能体的进展记在父运行上，使父运行时间线里的嵌套过程随之刷新。
    const parentRunId = event.runId && snapshot.value?.subagents?.find(value => value.run.id === event.runId)?.parentRunId
    if (parentRunId) toolRevisions.value[parentRunId] = event.seq
    if (['CONTEXT', 'REASONING'].includes(event.type) && event.runId) stepRevisions.value[event.runId] = event.seq
    if (event.type === 'FILE_CHANGE' && event.runId) changeRevisions.value[event.runId] = event.seq
    if (['COMMAND', 'PLUGIN'].includes(event.type) && event.runId) commandRevisions.value[event.runId] = event.seq
    if (event.type === 'QUESTION' && event.runId) questionRevisions.value[event.runId] = event.seq
    if (event.type === 'WORKSPACE') void loadWorkspaces().catch(() => {})
    if (event.seq > (snapshot.value?.lastSeq ?? 0)) scheduleRefresh(id, version)
  })
}
// Replace a stream that may have died without an error event (for example after a backend restart).
function restartStream() {
  const id = selectedId.value
  if (!id) return
  const version = revision
  source?.close()
  source = null
  clearTimeout(reconnectTimer)
  reconnectTimer = undefined
  connected.value = false
  follow(id, version)
}
// 后台标签页的计时器可能被节流，回到前台或网络恢复时按最近消息或建连时间补做一次存活检查。
function resumeStream(event: Event) {
  if (!selectedId.value || document.visibilityState !== 'visible') return
  if (event.type === 'online') reconnectDelay = 1500
  if (event.type === 'online' || Date.now() - lastStreamMessageAt > 15000) restartStream()
}
async function selectSession(id: string, keepSnapshot = false) {
  freshRunId.value = ''
  sendReady.value = false
  awayFromLatest.value = false
  closeSettings()
  const version = ++revision
  source?.close()
  source = null
  clearTimeout(reconnectTimer)
  clearTimeout(watchdogTimer)
  reconnectDelay = 1500
  clearTimeout(refreshTimer)
  refreshTimer = undefined
  refreshing = false
  dirty = false
  connected.value = false
  selectedId.value = id
  if (!keepSnapshot) { snapshot.value = null; launching.value = null }
  toolRevisions.value = {}
  stepRevisions.value = {}
  changeRevisions.value = {}
  commandRevisions.value = {}
  questionRevisions.value = {}
  error.value = ''
  loading.value = true
  prompt.value = readDraft(id)
  pending.value = null
  try {
    const saved = storageGet(`harness.pending.${id}`)
    if (saved) pending.value = JSON.parse(saved) as Submission
  } catch { storageSet(`harness.pending.${id}`, null) }
  if (pending.value) { prompt.value = pending.value.prompt; draftMentions.value = pending.value.mentions ?? [] }
  storageSet('harness.session', id)
  if (!id) {
    storageSet('harness.session', null)
    snapshot.value = { session: draftSession(), runs: [], lastSeq: 0, usage: null }
    approvalMode.value = 'MANUAL'
    loading.value = false
    return
  }
  try {
    const loaded = await refresh(id, version)
    if (version === revision) {
      const session = loaded?.session
      selectedProfileId.value = pending.value?.modelProfileId || session?.modelProfileId || 'default'
      reasoningEffort.value = normalizeReasoningDepth(pending.value?.reasoningEffort ?? (loaded?.runs.length ? session?.reasoningEffort || '' : selectedProfile.value?.reasoningEffort || ''))
      approvalMode.value = pending.value?.approvalMode || session?.approvalMode || 'MANUAL'
      follow(id, version)
    }
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 404) await sessionDeleted({ id })
    else if (version === revision) error.value = cause instanceof Error ? cause.message : '加载失败'
  } finally { if (version === revision) loading.value = false }
}
// 首次发送时才保存会话，沿用草稿的模型和审批选择。
async function addSession(title: string, workspaceId: string) {
  const profile = selectedProfileId.value
  const effort = reasoningEffort.value
  const approval = approvalMode.value
  const session = await api<Session>('/sessions', { title, workspaceId })
  sessions.value = [session, ...sessions.value]
  showAutomations.value = false
  showMemories.value = false
  showGroups.value = false
  showFlows.value = false
  // 发送过渡中保留草稿快照，避免工作区等选项闪回空状态。
  await selectSession(session.id, !!launching.value)
  if (selectedId.value === session.id && snapshot.value?.session.id !== session.id) snapshot.value = null
  selectedProfileId.value = profile; reasoningEffort.value = effort; approvalMode.value = approval
  if (isMobile.value) sidebarCollapsed.value = true
  await nextTick()
  promptInput.value?.focus()
  return snapshot.value?.session.id === session.id
}
async function createSession() {
  await createWorkspaceSession(snapshot.value?.session.workspaceId || '')
}
async function send() {
  if (optimizingPrompt.value || workspaceChanging.value) return
  if (selectedId.value) {
    const launch = !snapshot.value?.runs.length && canSubmit()
    if (launch) startLaunch()
    await submit()
    if (launch) endLaunch()
    return
  }
  if (attachmentsBlocked.value) { error.value = '附件仍在上传或上传失败，请等待完成或移除后再发送'; return }
  if (loading.value || submitting.value || !canUseDraftRoute.value || !hasDraftContent.value) return
  const draft = prompt.value
  let ready = false
  startLaunch()
  submitting.value = true; error.value = ''
  try { ready = await addSession(draft.trim().slice(0, 40) || '新会话', snapshot.value?.session.workspaceId || '') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建会话失败' }
  finally { prompt.value = draft; submitting.value = false }
  if (ready) { saveDraft('', ''); await submit() }
  endLaunch()
}
function settingsSaved(value: ModelProfile) {
  profileRevision++
  profiles.value = [...profiles.value.filter(profile => profile.id !== value.id), value]
  if (!pending.value && !submitting.value) {
    selectedProfileId.value = value.id
    reasoningEffort.value = normalizeReasoningDepth(value.reasoningEffort)
  }
}
async function profileDeleted(id: string) {
  profileRevision++
  profiles.value = profiles.value.filter(profile => profile.id !== id)
}
async function workspaceSaved(session: Session) {
  const fromHome = !selectedId.value
  const creating = creatingWorkspace.value || !snapshot.value
  const draft = prompt.value
  const profile = selectedProfileId.value
  const effort = reasoningEffort.value
  const approval = approvalMode.value
  closeSettings()
  // 引用按目录序号和路径记录，换了工作区就不再适用。
  draftMentions.value = []
  if (!session.id) {
    if (selectedId.value) { clearComposerInputs(); await selectSession('') }
    snapshot.value = { session, runs: [], lastSeq: 0, usage: null }
    prompt.value = draft; selectedProfileId.value = profile; reasoningEffort.value = effort; approvalMode.value = approval
    try { await loadWorkspaces() }
    catch (cause) { error.value = cause instanceof Error ? cause.message : '刷新工作区失败' }
    return
  }
  sessions.value = creating ? [session, ...sessions.value] : sessions.value.map(value => value.id === session.id ? session : value)
  try {
    await loadWorkspaces()
    sessions.value = await api<Session[]>('/sessions')
    if (creating) {
      await selectSession(session.id)
      prompt.value = draft; selectedProfileId.value = profile; reasoningEffort.value = effort; approvalMode.value = approval
      if (fromHome) saveDraft('', '')
    }
    else if (selectedId.value === session.id) await refresh(session.id, revision)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '刷新会话失败' }
}
// 刷新独立工作区列表，包括尚未创建会话的工作区。
async function loadWorkspaces() { workspaces.value = await api<Workspace[]>('/workspaces') }
// 自动化在后台新建会话；打开其会话或窗口重新获得焦点时同步侧栏，失败时保留当前列表等待下次同步。
async function reloadSessions() {
  try { sessions.value = await api<Session[]>('/sessions') } catch { /* 下次同步重试 */ }
}
function openAutomations() {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = true
  showMemories.value = false
  showGroups.value = false
  showFlows.value = false
}
// 图谱作为独立工作台打开，原会话的草稿和实时连接继续保留。
function openMemories() {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = false
  showMemories.value = true
  showGroups.value = false
  showFlows.value = false
}
// 群聊作为独立页面打开，原会话的草稿和实时连接继续保留。
function openGroups() {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = false
  showMemories.value = false
  showGroups.value = true
  showFlows.value = false
}
// 流水线作为独立页面打开，原会话的草稿和实时连接继续保留。
function openFlows() {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = false
  showMemories.value = false
  showGroups.value = false
  showFlows.value = true
}
async function openAutomationSession(id: string) {
  // 与侧栏会话入口的禁用条件一致，避免打断进行中的提交或切换。
  if (submitting.value || loading.value || settingsBusy.value || workspaceChanging.value) return
  await reloadSessions()
  navigateSession(id)
}
async function openFlowTask(flowId: string, itemId?: string) {
  openFlows()
  await nextTick()
  await flowPage.value?.openTask(flowId, itemId)
}
/** 通知在提交过程中到达时暂存跳转，避免中断提交或丢失群聊草稿。 */
async function openTask(task: TaskActivity) {
  queuedTask.value = task
  if (taskNavigationBlocked.value) return
  queuedTask.value = undefined
  closeSettings()
  if (task.groupId) {
    openGroups()
    await nextTick()
    await groupPage.value?.openTask(task.groupId)
  } else if (task.flowId) {
    await openFlowTask(task.flowId, task.flowItemId || task.id)
  } else {
    await openAutomationSession(task.sessionId)
  }
}
watch(taskNavigationBlocked, blocked => { if (!blocked && queuedTask.value) void openTask(queuedTask.value) })
watch(hasDraftContent, has => { sendReady.value = has && document.activeElement === promptInput.value })
// 欢迎页与对话页的最小高度不同，内联高度要随状态和程序写入的内容重新计算。
watch([prompt, () => snapshot.value?.runs.length, launching], () => { void nextTick(resizePrompt) })
// 新建只切换到草稿；重复点击当前草稿入口时保留输入，不创建空会话。
async function createWorkspaceSession(workspaceId: string) {
  if (loading.value || submitting.value || workspaceChanging.value) return
  showAutomations.value = false; showMemories.value = false; showGroups.value = false; showFlows.value = false
  closeSettings()
  if (selectedId.value || !snapshot.value || snapshot.value.session.workspaceId !== workspaceId) {
    clearComposerInputs()
    await selectSession('')
    snapshot.value = { session: draftSession(workspaces.value.find(value => value.id === workspaceId)), runs: [], lastSeq: 0, usage: null }
  }
  error.value = ''
  if (isMobile.value) sidebarCollapsed.value = true
  await nextTick(); promptInput.value?.focus()
}
async function initialize() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadProfiles(), loadExternalAgents()])
    await loadWorkspaces()
    sessions.value = await api<Session[]>('/sessions')
    const stored = storageGet('harness.session')
    const id = sessions.value.find(session => session.id === stored)?.id || sessions.value[0]?.id
    if (id) await selectSession(id)
  } catch { error.value = '无法连接服务，请确认后端已启动后重试。' }
  finally { loading.value = false }
}
const externalAgents = ref<ExternalAgentCommand[]>([])
const draftAlias = computed(() => externalCommandAlias(prompt.value))
const draftAgent = computed(() => externalAgents.value.find(agent => agent.id === draftAlias.value))
const externalDraft = computed(() => !!draftAgent.value || ['codex', 'claude'].includes(draftAlias.value || ''))
const externalComposer = computed(() => externalDraft.value || current.value?.provider === 'external-acp')
const externalDraftTask = computed(() => prompt.value.replace(/^\/[a-z0-9][a-z0-9-]{0,31}(?=\s|$)/, '').trim())
const externalDraftProblem = computed<{ text: string; action?: 'agents' | 'workspace' } | null>(() => {
  if (!externalDraft.value) return null
  if (!draftAgent.value) return { text: `外部智能体 /${draftAlias.value} 尚未配置`, action: 'agents' }
  if (!draftAgent.value.enabled) return { text: `外部智能体 /${draftAgent.value.id} 已停用`, action: 'agents' }
  if (!snapshot.value?.session.workspace) return { text: '请先选择工作区，外部智能体需要工作目录', action: workspaces.value.length ? undefined : 'workspace' }
  if (draftAttachments.value.length > 0 || draftMentions.value.length > 0) return { text: '外部智能体不接收附件或文件引用，请先移除' }
  if (!externalDraftTask.value) return { text: `请在 /${draftAgent.value.id} 后输入任务内容` }
  return null
})
const canUseDraftRoute = computed(() => externalDraft.value ? !externalDraftProblem.value : !!model.value?.configured)
/** Refresh the visible alias catalog after extension settings close. */
async function loadExternalAgents() {
  try { externalAgents.value = await api('/external-agents/commands') } catch { /* ordinary chat remains available */ }
}
function canSubmit() {
  if (submitting.value || !snapshot.value) return false
  return !!pending.value || (!attachmentsBlocked.value && canUseDraftRoute.value && !current.value && hasDraftContent.value)
}
// 只有引用卡片时用引用路径作正文，与在正文中写 @path 等效；只有附件时说明内容在附件里。
function draftText() {
  return prompt.value.trim() ? prompt.value
    : draftMentions.value.length ? draftMentions.value.map(entry => `@${mentionLabel(entry, multiRoot.value)}`).join(' ')
    : attachmentsOnlyPrompt(draftAttachments.value.map(item => item.name))
}
async function submit() {
  if (!pending.value && attachmentsBlocked.value) { error.value = '附件仍在上传或上传失败，请等待完成或移除后再发送'; return }
  if (!canSubmit()) return
  const mentions = draftMentions.value
  const text = draftText()
  const id = selectedId.value
  const version = revision
  const request = pending.value ?? { requestId: crypto.randomUUID(), prompt: text,
    scenario: 'NORMAL', modelProfileId: selectedProfileId.value, reasoningEffort: reasoningEffort.value, approvalMode: approvalMode.value,
    mentions, attachmentIds: draftAttachments.value.map(item => item.id), externalAgentId: draftAgent.value?.agentId }
  setPending(request)
  submitting.value = true
  error.value = ''
  try {
    const created = await api<Run>(`/sessions/${id}/runs`, request)
    if (version === revision) {
      if (!launching.value && !snapshot.value?.runs.some(run => run.id === created.id)) freshRunId.value = created.id
      setPending(null)
      prompt.value = ''
      clearComposerInputs()
      // 附件在提交返回前已绑定，强制重读，避免事件刷新抢先读到未绑定的列表。
      sentAttachmentsKey = ''
      await refresh(id, version)
      restartStream()
    }
  } catch (cause) {
    if (version === revision) {
      // 刷新页面后恢复的待重试消息只保留附件 ID，被拒绝后附件需要重新添加。
      const lost = cause instanceof ApiError && cause.status < 500 && !!request.attachmentIds?.length && !draftAttachments.value.length
      if (cause instanceof ApiError && cause.status < 500) setPending(null)
      error.value = (cause instanceof Error ? cause.message : '提交失败') + (lost ? '；原消息的附件需要重新添加' : '')
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
onMounted(() => {
  void initialize().finally(() => { disconnectTaskActivity = connectTaskActivity(task => { void openTask(task) }) })
  mobileLayout.addEventListener('change', onLayoutChange)
  window.addEventListener('focus', reloadSessions)
  document.addEventListener('visibilitychange', resumeStream)
  window.addEventListener('online', resumeStream)
})
onUnmounted(() => {
  disconnectTaskActivity()
  revision++
  source?.close()
  clearTimeout(reconnectTimer); clearTimeout(watchdogTimer); clearTimeout(refreshTimer)
  document.removeEventListener('visibilitychange', resumeStream)
  window.removeEventListener('online', resumeStream)
  mobileLayout.removeEventListener('change', onLayoutChange)
  window.removeEventListener('focus', reloadSessions)
})
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }" :style="{ '--sidebar-width': `${sidebarWidth}px` }">
    <TaskCenter :disabled="taskNavigationBlocked" @open-task="openTask" />
    <AppSidebar
      :sessions="sessions"
      :workspaces="workspaces"
      :selected-id="selectedId"
      :active-page="activePage"
      :waiting-flow-count="waitingFlowCount"
      v-model:collapsed="sidebarCollapsed"
      v-model:width="sidebarWidth"
      :is-mobile="isMobile"
      :show-settings="showSettings"
      :loading="loading"
      :submitting="submitting"
      :settings-busy="settingsBusy"
      :workspace-changing="workspaceChanging"
      :current-running="!!current"
      :has-pending="!!pending"
      @create-session="createSession"
      @create-workspace-session="createWorkspaceSession"
      @select-session="navigateSession"
      @open-automations="openAutomations"
      @open-memories="openMemories()"
      @open-groups="openGroups"
      @open-flows="openFlows"
      @open-settings="openSettings()"
      @session-renamed="sessionRenamed"
      @session-deleted="sessionDeleted"
    />
    <main v-show="!showSettings && !showAutomations && !showMemories && !showGroups && !showFlows" class="main-panel" :class="{ 'is-welcome': !snapshot?.runs.length && !launching, 'is-launching': launching }" :inert="isMobile && !sidebarCollapsed || undefined">
      <header class="workspace-header">
        <div class="workspace-title"><button v-if="sidebarCollapsed" class="icon-button" aria-label="展开侧栏" title="展开侧栏" @click="sidebarCollapsed = false"><UiIcon name="panel" /></button><span class="header-breadcrumb">工作台 <span aria-hidden="true">/</span></span><h1>{{ snapshot?.session.title || '新对话' }}</h1></div>
        <div class="header-meta"><span v-if="selectedId && !connected" class="connection" title="正在重新连接">连接中</span></div>
      </header>
      <div v-if="error" class="error-banner" role="alert">{{ error }} <button @click="selectedId ? selectSession(selectedId) : initialize()">重新连接</button></div>
      <div class="workbench-body"><div class="chat-panel">
      <div ref="timeline" class="timeline" aria-label="对话记录" :aria-busy="loading" @scroll.passive="trackTimeline">
        <div v-if="loading && !launching" class="empty-state"><span class="loading-indicator"></span><p>正在读取会话…</p></div>
        <div v-else-if="!snapshot?.runs.length && !launching" class="welcome">
          <div class="welcome-copy">
            <p class="welcome-eyebrow"><span aria-hidden="true"></span>{{ workspaces.find(value => value.id === snapshot?.session.workspaceId)?.name || '新的任务' }}</p>
            <h2>今天想推进什么？</h2>
          </div>
        </div>
        <article v-if="launching && !snapshot?.runs.length" class="run-card">
          <div class="user-message-wrap"><p class="user-message">{{ launching.text }}</p><ul v-if="launching.attachments.length" class="message-attachments" aria-label="消息附件"><li v-for="item in launching.attachments" :key="item.key"><a :title="item.name"><img v-if="isImage(item.mediaType)" :src="`/api/attachments/${item.id}/content`" :alt="item.name" /><template v-else><UiIcon name="file" :size="15" /><span>{{ item.name }}</span></template></a></li></ul></div>
          <section class="response" aria-label="等待回答"><div class="assistant-heading"><UiIcon v-if="launching.agentName" name="puzzle" :size="22" /><img v-else class="brand-icon" src="/harness-icon.svg" width="22" height="22" alt="" /><strong>{{ launching.agentName || 'Harness' }}</strong><span class="working-label"><span class="activity-dot"></span>排队中</span></div></section>
        </article>
        <ChatRunCard
          v-for="run in snapshot?.runs"
          :key="run.id"
          :run="run"
          :fresh="run.id === freshRunId"
          :attachments="attachmentsOf(run.id)"
          :subagents="snapshot?.subagents?.filter(value => value.parentRunId === run.id)"
          :profiles="profiles"
          :connected="connected"
          :session-workspace="snapshot?.session.workspace || ''"
          :session-locked="!!current || submitting || !!pending"
          :tool-revision="toolRevisions[run.id] ?? 0"
          :step-revision="stepRevisions[run.id] ?? 0"
          :change-revision="changeRevisions[run.id] ?? 0"
          :command-revision="commandRevisions[run.id] ?? 0"
          :question-revision="questionRevisions[run.id] ?? 0"
          @fresh-end="freshRunId = ''"
          @timeline-loaded="timelineLoaded"
        />
      </div>
      <div class="composer-dock">
        <button v-if="awayFromLatest && snapshot?.runs.length" type="button" class="latest-message" @click="goToLatest"><UiIcon name="down" :size="14" />回到最新</button>
        <form class="composer" @submit.prevent="send" @dragover="onComposerDragover" @drop="onComposerDrop">
          <label class="sr-only" for="prompt">输入问题</label>
          <PluginCommandPicker ref="pluginCommandPicker" :session-id="snapshot?.session.id" :disabled="optimizingPrompt || !!current || submitting || !!pending || loading || workspaceChanging || showSettings" :text="prompt" :input="promptInput" :agents="externalAgents" @select="prompt = $event" />
          <MentionPicker ref="mentionPicker" :session-id="snapshot?.session.id" :workspace-id="snapshot?.session.workspaceId" :workspace="!!snapshot?.session.workspace" :multi-root="multiRoot" :disabled="optimizingPrompt || submitting || !!pending || loading || workspaceChanging || showSettings" :text="prompt" :input="promptInput" @select="pickMention" />
          <ComposerAttachments :items="draftAttachments" :mentions="draftMentions" :multi-root="multiRoot" :disabled="optimizingPrompt || submitting || !!pending" @remove="removeAttachment" @remove-mention="removeMention" />
          <div class="composer-input">
            <textarea id="prompt" @input="resizePrompt" @paste="onPromptPaste" ref="promptInput" v-model="prompt" :placeholder="snapshot?.session.workspace ? '询问项目，@ 引用文件，/ 选择命令…' : '描述你的问题，可粘贴图片，/ 选择命令…'" :disabled="optimizingPrompt || !!pending || submitting || loading || workspaceChanging" :aria-describedby="optimizationError ? 'prompt-optimization-error' : undefined" rows="1" role="combobox" aria-autocomplete="list" aria-haspopup="listbox" :aria-expanded="pluginCommandPicker?.open || mentionPicker?.open || false" :aria-controls="pluginCommandPicker?.activeId ? 'plugin-command-options' : mentionPicker?.activeId ? 'file-mention-options' : undefined" :aria-activedescendant="pluginCommandPicker?.activeId || mentionPicker?.activeId" @keydown="onPromptKeydown" />
            <div v-if="optimizingPrompt" class="prompt-optimization-status" role="status"><span class="loading-indicator" aria-hidden="true"></span>正在优化提示词…</div>
          </div>
          <p v-if="optimizationError" id="prompt-optimization-error" class="prompt-optimization-error" role="alert">{{ optimizationError }}</p>
          <div v-if="pending" v-show="!submitting" class="pending-note">上次提交结果待确认，重试不会重复创建任务。<template v-if="pending.attachmentIds?.length || pending.mentions?.length">重试将带上 {{ pending.attachmentIds?.length || 0 }} 个附件、{{ pending.mentions?.length || 0 }} 个引用。</template></div>
          <p v-else-if="externalDraftProblem" class="configuration-hint"><span>{{ externalDraftProblem.text }}</span><button v-if="externalDraftProblem.action === 'agents'" type="button" @click="openSettings('agents')">前往配置 <UiIcon name="chevron" :size="12" /></button><button v-else-if="externalDraftProblem.action === 'workspace'" type="button" @click="browseWorkspace()">新建工作区 <UiIcon name="chevron" :size="12" /></button></p>
          <p v-else-if="!externalComposer && !model?.configured && !loading" class="configuration-hint"><span>连接一个模型，即可开始对话</span><button type="button" @click="openSettings('models')">配置模型 <UiIcon name="chevron" :size="12" /></button></p>
          <div class="composer-actions">
            <ComposerAddMenu :disabled="optimizingPrompt || submitting || !!pending || loading || workspaceChanging" :workspace="!!snapshot?.session.workspace" @files="addFiles" @mention="startMention" @command="startCommand" />
            <ComposerSelectors :profiles="profiles" :model-id="selectedProfileId" :effort="reasoningEffort" :workspace-id="snapshot?.session.workspaceId || ''" :workspaces="workspaces" :disabled="submitting || !!pending || loading || workspaceChanging" :workspace-disabled="!!current" :workspace-only="externalComposer" :approval-mode="approvalMode" @model="chooseProfile" @effort="reasoningEffort = $event" @approval="approvalMode = $event" @settings="openSettings('models')" @workspace="chooseWorkspace" @browse="browseWorkspace()" @edit="browseWorkspace(workspaces.find(value => value.id === snapshot?.session.workspaceId))" />
            <TokenUsageRing v-if="!externalComposer" :usage="snapshot?.usage" :estimated="latestContext?.tokens ?? 0" :max-tokens="latestContext?.maxTokens ?? 0" />
            <PromptOptimizer v-if="!externalComposer" :text="prompt" :session-id="snapshot?.session.id || ''" :workspace="snapshot?.session.workspace || ''" :workspace-id="snapshot?.session.workspaceId || ''" :model-profile-id="selectedProfileId" :reasoning-effort="reasoningEffort" :mentions="draftMentions" :attachment-ids="draftAttachments.map(item => item.id)" :disabled="!model?.configured || !!current || submitting || !!pending || loading || workspaceChanging || attachmentsBlocked || showSettings || showAutomations || showMemories || showGroups" @busy="optimizingPrompt = $event" @error="optimizationError = $event" @apply="prompt = $event; nextTick(() => promptInput?.focus())" />
            <button v-if="current" type="button" class="send-button stop-button" :disabled="cancelling || current.status === 'CANCELLING'" :aria-label="current.status === 'CANCELLING' ? '正在停止' : '停止任务'" title="停止任务" @click="cancel"><UiIcon name="stop" :size="15" /></button>
            <button v-else type="submit" class="send-button" :class="{ 'is-ready': sendReady }" :disabled="optimizingPrompt || loading || submitting || workspaceChanging || (!pending && (!canUseDraftRoute || !hasDraftContent || attachmentsBlocked || (!!selectedId && !snapshot)))" :aria-label="pending && !submitting ? '重试提交' : '发送消息'" :title="pending && !submitting ? '重试提交' : sendShortcutHint"><UiIcon :name="pending && !submitting ? 'refresh' : 'arrow'" :size="18" /></button>
          </div>
        </form>
        <div class="composer-caption"><span>{{ sendShortcutHint }}</span></div>
        <div v-if="!snapshot?.runs.length && !loading && !launching" class="suggestion-list"><button v-for="item in suggestions" :key="item.title" :disabled="optimizingPrompt || submitting || !!pending" @click="useSuggestion(item.prompt)"><UiIcon :name="item.icon" :size="18" /><span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span><UiIcon class="suggestion-arrow" name="chevron" :size="13" /></button></div>
      </div>
      </div>
      </div>
    </main>
    <AutomationsPage v-if="showAutomations" v-show="!showSettings" :workspaces="workspaces" :profiles="profiles" :sidebar-collapsed="sidebarCollapsed" :default-workspace-id="snapshot?.session.workspaceId || ''" :default-profile-id="selectedProfileId" :inert="isMobile && !sidebarCollapsed || undefined" @open-session="openAutomationSession" @open-flow="openFlowTask" @expand-sidebar="sidebarCollapsed = false" />
    <MemoriesPage v-if="showMemories" v-show="!showSettings" :workspaces="workspaces" :sidebar-collapsed="sidebarCollapsed" :default-workspace-id="snapshot?.session.workspaceId || ''" :inert="isMobile && !sidebarCollapsed || undefined" @open-session="openAutomationSession" @expand-sidebar="sidebarCollapsed = false" @open-settings="openSettings('memory')" />
    <GroupChatsPage v-if="showGroups" ref="groupPage" v-show="!showSettings" :workspaces="workspaces" :profiles="profiles" :sidebar-collapsed="sidebarCollapsed" :default-profile-id="selectedProfileId" :inert="isMobile && !sidebarCollapsed || undefined" @expand-sidebar="sidebarCollapsed = false" @studio="setGroupStudio" />
    <FlowsPage v-if="showFlows" ref="flowPage" v-show="!showSettings" :workspaces="workspaces" :profiles="profiles" :sidebar-collapsed="sidebarCollapsed" :default-workspace-id="snapshot?.session.workspaceId || ''" :default-profile-id="selectedProfileId" :inert="isMobile && !sidebarCollapsed || undefined" @open-session="openAutomationSession" @expand-sidebar="sidebarCollapsed = false" />
    <DesktopUpdateNotice @details="openSettings('updates')" />
    <dialog ref="workspaceDialog" class="workspace-dialog settings-page" aria-labelledby="workspace-dialog-title" @cancel="workspaceChanging && $event.preventDefault()" @close="browsingWorkspace = false">
      <header class="inspector-header"><h2 id="workspace-dialog-title">{{ editingWorkspace ? '编辑工作区' : '新建工作区' }}</h2><button type="button" class="icon-button" :disabled="workspaceChanging" aria-label="关闭工作区设置" @click="closeWorkspaceDialog"><UiIcon name="close" :size="20" /></button></header>
      <WorkspaceSettings v-if="browsingWorkspace" :session-id="snapshot?.session.id" initial-path="" :initial-workspace="editingWorkspace" :disabled="workspaceSyncing || !!current || submitting || !!pending || loading" @busy="workspaceSaving = $event" @saved="workspacePicked" @cancel="closeWorkspaceDialog" />
    </dialog>
    <SettingsPage v-if="showSettings" :initial-profile-id="selectedProfileId" :initial-section="settingsSection" :session="snapshot?.session" :workspaces="workspaces" :creating-workspace="creatingWorkspace" :workspace-disabled="!!current || submitting || !!pending || loading" @close="closeSettings" @busy="settingsBusy = $event" @saved="settingsSaved" @deleted="profileDeleted" @changed="loadProfiles().catch(() => {})" @workspace-saved="workspacePicked" />
  </div>
</template>

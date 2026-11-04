<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import RunTimeline from './RunTimeline.vue'
import FileChanges from './FileChanges.vue'
import ArtifactCards from './ArtifactCards.vue'
import Commands from './Commands.vue'
import Questions from './Questions.vue'
import PluginRunDetails from './PluginRunDetails.vue'
import PluginCommandPicker from './PluginCommandPicker.vue'
import MentionPicker from './MentionPicker.vue'
import ComposerAddMenu from './ComposerAddMenu.vue'
import ComposerAttachments from './ComposerAttachments.vue'
import SettingsPage from './SettingsPage.vue'
import AutomationsPage from './AutomationsPage.vue'
import MemoriesPage from './MemoriesPage.vue'
import GroupChatsPage from './GroupChatsPage.vue'
import MemoryUsage from './MemoryUsage.vue'
import ComposerSelectors from './ComposerSelectors.vue'
import TokenUsageRing from './TokenUsageRing.vue'
import WorkspaceSettings from './WorkspaceSettings.vue'
import SidebarResize from './SidebarResize.vue'
import SessionRenameDialog from './SessionRenameDialog.vue'
import SessionDeleteDialog from './SessionDeleteDialog.vue'
import UiIcon from './UiIcon.vue'
import { preferences, shouldSendPrompt } from './preferences'
import { api, ApiError, isActive, effortShortLabel, normalizeReasoningDepth, upload, type Run, type RunEvent, type RunStatus,
  type Session, type Snapshot, type Submission, type ModelProfile, type Workspace, type ApprovalMode } from './api'
import { addMention, attachmentProblem, droppedFiles, isImage, mentionLabel, MAX_ATTACHMENTS, MAX_MENTIONS, removeFileMentionQuery,
  type Attachment, type DraftAttachment, type FileEntry } from './composer'
import { workspaceSummary } from './workspaces'

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
const settingsSection = ref<'general' | 'models' | 'workspace' | 'approval' | 'memory'>('general')
const fontSize = computed(() => ({ small: 13, standard: 14, large: 16 })[preferences.value.chatFontSize])
// 根字号控制全局文字，辅助文字仍保留最小可读尺寸。
watch(fontSize, value => {
  document.documentElement.style.setProperty('--app-font-size', `${value}px`)
}, { immediate: true })
const sendShortcutHint = computed(() => preferences.value.sendShortcut === 'enter' ? 'Enter 发送，Shift + Enter 换行' : 'Ctrl / ⌘ + Enter 发送')
const creatingWorkspace = ref(false)
const settingsBusy = ref(false)
const workspaceSaving = ref(false)
const workspaceSyncing = ref(false)
const workspaceChanging = computed(() => workspaceSaving.value || workspaceSyncing.value)
const workspaceDialog = ref<HTMLDialogElement>()
const browsingWorkspace = ref(false)
const collapsedGroups = ref(new Set<string>())
const sidebarCollapsed = ref(window.innerWidth <= 780)
const savedSidebarWidth = Number(storageGet('harness.sidebar.width'))
const sidebarWidth = ref(Number.isFinite(savedSidebarWidth) && savedSidebarWidth > 0 ? Math.max(200, Math.min(400, savedSidebarWidth)) : 248)
watch(sidebarWidth, value => storageSet('harness.sidebar.width', String(value)))
const sessionMenuId = ref('')
const sessionMenuElement = ref<HTMLElement>()
const sessionMenuPosition = ref<Record<string, string>>({})
const sessionMenuSession = computed(() => sessions.value.find(session => session.id === sessionMenuId.value))
let sessionMenuTrigger: HTMLElement | null = null
function closeSessionMenu(restoreFocus = false) {
  const trigger = sessionMenuTrigger
  sessionMenuId.value = ''
  sessionMenuTrigger = null
  if (restoreFocus) trigger?.focus()
}
async function toggleSessionMenu(session: Session, event: MouseEvent) {
  if (sessionMenuId.value === session.id) { closeSessionMenu(true); return }
  const trigger = event.currentTarget as HTMLElement
  const bounds = trigger.getBoundingClientRect()
  const width = 144
  const below = window.innerHeight - bounds.bottom - 12
  const above = bounds.top - 12
  const upwards = below < 88 && above > below
  sessionMenuTrigger = trigger
  sessionMenuPosition.value = {
    left: `${Math.max(8, Math.min(bounds.right - width, window.innerWidth - width - 8))}px`,
    maxHeight: `${Math.max(80, upwards ? above : below)}px`,
    ...(upwards ? { bottom: `${window.innerHeight - bounds.top + 4}px` } : { top: `${bounds.bottom + 4}px` }),
  }
  sessionMenuId.value = session.id
  await nextTick()
  sessionMenuElement.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
}
function outsideSessionMenu(event: PointerEvent) {
  if (!sessionMenuId.value) return
  const target = event.target as Node
  if (!sessionMenuTrigger?.contains(target) && !sessionMenuElement.value?.contains(target)) closeSessionMenu()
}
function focusOutsideSessionMenu(event: FocusEvent) {
  if (!sessionMenuId.value) return
  const target = event.target as Node
  if (!sessionMenuTrigger?.contains(target) && !sessionMenuElement.value?.contains(target)) closeSessionMenu()
}
function scrollSessionMenu(event: Event) {
  if (sessionMenuId.value && !sessionMenuElement.value?.contains(event.target as Node)) closeSessionMenu()
}
function resizeSessionMenu() { closeSessionMenu() }
function navigateSessionMenu(event: KeyboardEvent) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const options = [...(sessionMenuElement.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || [])]
  if (!options.length) return
  event.preventDefault()
  const index = options.indexOf(event.target as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
    : event.key === 'ArrowDown' ? (index + 1) % options.length : (index <= 0 ? options.length : index) - 1
  options[next]?.focus()
}
const renamingSession = ref<Session | null>(null)
let renameTrigger: HTMLElement | null = null
let titleRevision = 0
// 记录重命名入口，关闭弹窗后恢复键盘焦点。
function openRename(session: Session, event: MouseEvent) {
  renameTrigger = sessionMenuTrigger || (event.currentTarget as HTMLElement)
  closeSessionMenu()
  renamingSession.value = session
}
// 仅同步标题，保留可能同时刷新的工作区和运行记录。
function sessionRenamed(session: Session) {
  titleRevision++
  sessions.value = sessions.value.map(value => value.id === session.id ? { ...value, title: session.title } : value)
  if (snapshot.value?.session.id === session.id) snapshot.value.session.title = session.title
}
// 弹窗取消和保存后都返回原入口。
async function closeRename() {
  const trigger = renameTrigger
  renamingSession.value = null; renameTrigger = null
  await nextTick()
  if (trigger?.isConnected) trigger.focus()
}
const deletingSession = ref<Session | null>(null)
let deleteTrigger: HTMLElement | null = null
// 记录删除入口，关闭弹窗后恢复键盘焦点。
function openDelete(session: Session, event: MouseEvent) {
  deleteTrigger = sessionMenuTrigger || (event.currentTarget as HTMLElement)
  closeSessionMenu()
  deletingSession.value = session
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
  toolRevisions.value = {}; stepRevisions.value = {}; answers.value = {}; changeRevisions.value = {}; commandRevisions.value = {}; commandActivity.value = {}; pluginActivity.value = {}; questionRevisions.value = {}; questionActivity.value = {}
  error.value = ''
  storageSet('harness.session', null)
}
// 弹窗取消和删除后都返回原入口；入口已随会话移除时，焦点落到"新对话"。
async function closeDelete() {
  const trigger = deleteTrigger
  deletingSession.value = null; deleteTrigger = null
  await nextTick()
  if (trigger?.isConnected) trigger.focus()
  else (document.querySelector('.new-session') as HTMLElement | null)?.focus()
}
const mobileLayout = window.matchMedia('(max-width: 780px)')
const isMobile = ref(mobileLayout.matches)
function onLayoutChange() { isMobile.value = mobileLayout.matches; if (isMobile.value) sidebarCollapsed.value = true }
function closeSidebarOnEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || event.defaultPrevented) return
  if (sessionMenuId.value) { event.preventDefault(); closeSessionMenu(true); return }
  if (isMobile.value) sidebarCollapsed.value = true
}
const sessionSearch = ref('')
const promptInput = ref<HTMLTextAreaElement>()
const pluginCommandPicker = ref<InstanceType<typeof PluginCommandPicker>>()
const mentionPicker = ref<InstanceType<typeof MentionPicker>>()
const draftAttachments = ref<DraftAttachment[]>([])
const draftMentions = ref<FileEntry[]>([])
const sentAttachments = ref<Attachment[]>([])
let sentAttachmentsKey = ''
const multiRoot = computed(() => (workspaces.value.find(value => value.id === snapshot.value?.session.workspaceId)?.directories.length || 0) > 1)
// 上传中或上传失败的附件需要处理后才能发送。
const attachmentsBlocked = computed(() => draftAttachments.value.some(item => !item.id || item.error))
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
  if (submitting.value || pending.value || loading.value || workspaceChanging.value) return
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
  if (prompt.value.length + inserted.length > 4000) { error.value = '消息已达 4000 字符上限'; return }
  prompt.value = before + inserted + prompt.value.slice(caret)
  await nextTick(); input?.setSelectionRange(caret + inserted.length, caret + inserted.length); input?.focus()
}
// 已有正文时不改写，命令只能写在开头，空输入框才插入 /。
async function startCommand() {
  if (prompt.value && !prompt.value.startsWith('/')) { error.value = '插件命令需写在消息开头，请先清空输入框或在开头输入 /'; return }
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
const copiedRun = ref('')
const copyError = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined
const sessionGroups = computed(() => {
  const groups = new Map<string, { label: string; icon: string; color: string; directories: string[]; sessions: Session[] }>()
  for (const workspace of workspaces.value) {
    if (!sessionSearch.value || `${workspace.name} ${workspace.directories.join(' ')}`.toLowerCase().includes(sessionSearch.value.toLowerCase()))
      groups.set(workspace.id, { label: workspace.name, icon: workspace.icon, color: workspace.color, directories: workspace.directories, sessions: [] })
  }
  for (const session of sessions.value) {
    const workspace = workspaces.value.find(value => value.id === session.workspaceId)
    if (!`${session.title} ${workspace?.name || ''} ${session.workspace}`.toLowerCase().includes(sessionSearch.value.toLowerCase())) continue
    const key = session.workspaceId || ''
    if (!groups.has(key)) groups.set(key, { label: workspace?.name || '最近会话', icon: workspace?.icon || 'folder', color: workspace?.color || '', directories: workspace?.directories || [], sessions: [] })
    groups.get(key)!.sessions.push(session)
  }
  return [...groups.entries()]
})
const suggestions = [
  { icon: 'folder', title: '了解项目', description: '从结构到入口，建立全局视图', prompt: '查看项目结构，读取构建配置，并结合源码解释程序入口。' },
  { icon: 'scan', title: '定位问题', description: '从现象出发，找到问题所在', prompt: '请帮我定位一个问题，先告诉我需要提供哪些错误信息和复现步骤。' },
  { icon: 'branch', title: '梳理调用链', description: '连接模块，理清执行脉络', prompt: '分析项目的核心模块和调用关系，标明关键类与方法。' },
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
watch([fontSize, showSettings, showAutomations, showMemories, showGroups], () => { if (!showSettings.value && !showAutomations.value && !showMemories.value && !showGroups.value) void nextTick(resizePrompt) })
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
    const session = snapshot.value
      ? await api<Session>(`/sessions/${selectedId.value}/workspace`, { workspaceId })
      : await api<Session>('/sessions', { title: '新会话', workspaceId })
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
  try { await navigator.clipboard.writeText(answerOf(run)); copiedRun.value = run.id; copyError.value = ''; clearTimeout(copyTimer); copyTimer = setTimeout(() => { copiedRun.value = '' }, 2000) }
  catch { copyError.value = '复制失败，请选择回答内容手动复制。' }
}
function openSettings(section: 'general' | 'models' | 'workspace' | 'approval' | 'memory' = 'general', creating = false) {
  if (workspaceChanging.value) return
  settingsSection.value = section
  creatingWorkspace.value = creating
  showSettings.value = true
}
function closeSettings() { showSettings.value = false; settingsBusy.value = false }
function navigateSession(id: string) {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = false
  showMemories.value = false
  showGroups.value = false
  if (id === selectedId.value && snapshot.value && !loading.value) closeSettings()
  else { clearComposerInputs(); void selectSession(id) }
}
const toolRevisions = ref<Record<string, number>>({})
const stepRevisions = ref<Record<string, number>>({})
const changeRevisions = ref<Record<string, number>>({})
const commandRevisions = ref<Record<string, number>>({})
const questionRevisions = ref<Record<string, number>>({})
const questionActivity = ref<Record<string, string>>({})
const commandActivity = ref<Record<string, string>>({})
const pluginActivity = ref<Record<string, string>>({})
const activityLabel = (run: Run) => run.status === 'RUNNING' ? questionActivity.value[run.id] || pluginActivity.value[run.id] || commandActivity.value[run.id] || labels[run.status] : labels[run.status]
// 上下文字符数随每个新步骤变化，SSE 静默失效时快照轮询仍能触发时间线刷新。
const timelineRevision = (run: Run) => `${run.status}:${run.context?.characters ?? 0}:${toolRevisions.value[run.id] ?? 0}:${stepRevisions.value[run.id] ?? 0}:${connected.value}`
// 时间线拆分出的最终回答，复制和空回答提示以它为准。
const answers = ref<Record<string, string>>({})
const answerOf = (run: Run) => answers.value[run.id] ?? run.output
// 时间线异步加载会改变高度，跟随最新时重新贴底。
function timelineLoaded() {
  if (!preferences.value.autoScroll || awayFromLatest.value) return
  timeline.value?.scrollTo({ top: timeline.value.scrollHeight, behavior: 'instant' })
  trackTimeline()
}
// 输入框用量圆环以最近一次带 token 预算的请求为准。
const latestContext = computed(() => [...(snapshot.value?.runs ?? [])].reverse().find(run => !!run.context?.maxTokens)?.context ?? null)
// 与输入框模型选择器的显示规则一致；配置已删除时退回运行记录中的模型 ID。
function runModelLabel(run: Run) {
  const profile = profiles.value.find(item => item.id === run.modelProfileId)
  return profile?.displayName || profile?.modelName || run.model
}
// 上下文与工作区说明放在模型信息的悬浮提示中。
function contextTitle(run: Run) {
  const context = run.context
  const compacted = context?.compactedTurns ?? 0
  const parts = context ? [`携带 ${context.includedTurns} 轮历史`, compacted ? `已压缩 ${compacted} 轮为摘要` : '', context.droppedTurns > compacted ? `已裁剪 ${context.droppedTurns - compacted} 轮` : '', context.excludedTurns ? `已跳过 ${context.excludedTurns} 条` : ''] : []
  if (run.workspace) parts.push(`工作区：${workspaceSummary(run.workspace)}`)
  return parts.filter(Boolean).join('，')
}
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
const labels: Record<RunStatus, string> = {
  QUEUED: '排队中', RUNNING: '正在执行', CANCELLING: '正在停止', COMPLETED: '已完成',
  CANCELLED: '已停止', FAILED: '执行失败', TIMED_OUT: '已超时', INTERRUPTED: '已中断',
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
async function refresh(id: string, version: number) {
  const titles = titleRevision
  const value = await api<Snapshot>(`/sessions/${id}`)
  if (version !== revision || value.lastSeq < (snapshot.value?.lastSeq ?? 0)) return
  // 重命名完成前发出的刷新可能仍携带旧标题。
  if (titles !== titleRevision) value.session.title = sessions.value.find(session => session.id === id)?.title ?? value.session.title
  const node = timeline.value
  const initialLoad = !snapshot.value
  const scroll = initialLoad || (preferences.value.autoScroll && (!node || node.scrollHeight - node.scrollTop - node.clientHeight < 100))
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
async function selectSession(id: string) {
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
  snapshot.value = null
  toolRevisions.value = {}
  stepRevisions.value = {}; answers.value = {}
  changeRevisions.value = {}
  commandRevisions.value = {}
  commandActivity.value = {}; pluginActivity.value = {}
  questionRevisions.value = {}; questionActivity.value = {}
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
// 首页直接发送时沿用已选审批模式；新建空白会话从手动审批开始，完全访问需重新确认。
async function addSession(title: string, workspaceId: string, keepApproval = false) {
  const profile = selectedProfileId.value
  const effort = reasoningEffort.value
  const approval = keepApproval ? approvalMode.value : 'MANUAL'
  const session = await api<Session>('/sessions', { title, workspaceId })
  sessions.value = [session, ...sessions.value]
  showAutomations.value = false
  showMemories.value = false
  showGroups.value = false
  await selectSession(session.id)
  selectedProfileId.value = profile; reasoningEffort.value = effort; approvalMode.value = approval
  if (isMobile.value) sidebarCollapsed.value = true
  await nextTick()
  promptInput.value?.focus()
  return snapshot.value?.session.id === session.id
}
async function createSession() {
  if (loading.value || submitting.value || workspaceChanging.value) return
  loading.value = true; error.value = ''; clearComposerInputs()
  try { await addSession('新会话', snapshot.value?.session.workspaceId || '') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建会话失败' }
  finally { loading.value = false }
}
async function send() {
  if (workspaceChanging.value) return
  if (snapshot.value) { await submit(); return }
  if (attachmentsBlocked.value) { error.value = '附件仍在上传或上传失败，请等待完成或移除后再发送'; return }
  if (loading.value || submitting.value || selectedId.value || !model.value?.configured || !prompt.value.trim()) return
  const draft = prompt.value
  let ready = false
  submitting.value = true; error.value = ''
  try { ready = await addSession(draft.trim().slice(0, 40), '', true) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建会话失败' }
  finally { prompt.value = draft; submitting.value = false }
  if (ready) { saveDraft('', ''); await submit() }
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
}
// 图谱作为独立工作台打开，原会话的草稿和实时连接继续保留。
function openMemories() {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = false
  showMemories.value = true
  showGroups.value = false
}
// 群聊作为独立页面打开，原会话的草稿和实时连接继续保留。
function openGroups() {
  if (isMobile.value) sidebarCollapsed.value = true
  showAutomations.value = false
  showMemories.value = false
  showGroups.value = true
}
async function openAutomationSession(id: string) {
  // 与侧栏会话入口的禁用条件一致，避免打断进行中的提交或切换。
  if (submitting.value || loading.value || settingsBusy.value || workspaceChanging.value) return
  await reloadSessions()
  navigateSession(id)
}
// 从侧栏工作区入口创建绑定该工作区的新会话。
async function createWorkspaceSession(workspaceId: string) {
  if (loading.value || submitting.value || workspaceChanging.value) return
  loading.value = true; error.value = ''; clearComposerInputs()
  try { await addSession('新会话', workspaceId) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '创建会话失败' }
  finally { loading.value = false }
}
async function initialize() {
  loading.value = true
  error.value = ''
  try {
    await loadProfiles()
    await loadWorkspaces()
    sessions.value = await api<Session[]>('/sessions')
    const stored = storageGet('harness.session')
    const id = sessions.value.find(session => session.id === stored)?.id || sessions.value[0]?.id
    if (id) await selectSession(id)
  } catch { error.value = '无法连接服务，请确认后端已启动后重试。' }
  finally { loading.value = false }
}
async function submit() {
  if (!pending.value && attachmentsBlocked.value) { error.value = '附件仍在上传或上传失败，请等待完成或移除后再发送'; return }
  if (submitting.value || !snapshot.value || (!pending.value && (!model.value?.configured || current.value || (!prompt.value.trim() && !draftMentions.value.length)))) return
  const mentions = draftMentions.value
  // 只有引用卡片时用引用路径作正文，与在正文中写 @path 等效。
  const text = prompt.value.trim() ? prompt.value : mentions.map(entry => `@${mentionLabel(entry, multiRoot.value)}`).join(' ')
  const id = selectedId.value
  const version = revision
  const request = pending.value ?? { requestId: crypto.randomUUID(), prompt: text,
    scenario: 'NORMAL', modelProfileId: selectedProfileId.value, reasoningEffort: reasoningEffort.value, approvalMode: approvalMode.value,
    mentions, attachmentIds: draftAttachments.value.map(item => item.id) }
  setPending(request)
  submitting.value = true
  error.value = ''
  try {
    await api<Run>(`/sessions/${id}/runs`, request)
    if (version === revision) {
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
  void initialize()
  mobileLayout.addEventListener('change', onLayoutChange)
  window.addEventListener('focus', reloadSessions)
  document.addEventListener('keydown', closeSidebarOnEscape)
  document.addEventListener('pointerdown', outsideSessionMenu)
  document.addEventListener('focusin', focusOutsideSessionMenu)
  document.addEventListener('scroll', scrollSessionMenu, true)
  window.addEventListener('resize', resizeSessionMenu)
  document.addEventListener('visibilitychange', resumeStream)
  window.addEventListener('online', resumeStream)
})
onUnmounted(() => {
  revision++
  source?.close()
  clearTimeout(reconnectTimer); clearTimeout(watchdogTimer); clearTimeout(refreshTimer); clearTimeout(copyTimer)
  document.removeEventListener('visibilitychange', resumeStream)
  window.removeEventListener('online', resumeStream)
  mobileLayout.removeEventListener('change', onLayoutChange)
  window.removeEventListener('focus', reloadSessions)
  document.removeEventListener('keydown', closeSidebarOnEscape)
  document.removeEventListener('pointerdown', outsideSessionMenu)
  document.removeEventListener('focusin', focusOutsideSessionMenu)
  document.removeEventListener('scroll', scrollSessionMenu, true)
  window.removeEventListener('resize', resizeSessionMenu)
})
</script>

<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }" :style="{ '--sidebar-width': `${sidebarWidth}px` }">
    <button v-if="!sidebarCollapsed && !showSettings" class="sidebar-scrim" aria-label="关闭会话侧栏" @click="sidebarCollapsed = true"></button>
    <aside v-show="!showSettings" class="sidebar" :inert="sidebarCollapsed || undefined">
      <div class="sidebar-primary"><button class="new-session" :disabled="loading || submitting || settingsBusy || workspaceChanging" @click="createSession"><UiIcon name="plus" :size="17" /><span>新的任务</span></button><button class="icon-button collapse-sidebar" aria-label="收起侧栏" title="收起侧栏" @click="sidebarCollapsed = true"><UiIcon name="panel" :size="16" /></button></div>
      <nav class="sidebar-links" aria-label="功能"><button class="sidebar-link" :aria-current="showAutomations ? 'page' : undefined" :disabled="settingsBusy || workspaceChanging" @click="openAutomations"><UiIcon name="clock" :size="16" /><span>自动化</span></button><button class="sidebar-link" :aria-current="showMemories ? 'page' : undefined" :disabled="settingsBusy || workspaceChanging" @click="openMemories()"><UiIcon name="branch" :size="16" /><span>记忆</span></button><button class="sidebar-link" :aria-current="showGroups ? 'page' : undefined" :disabled="settingsBusy || workspaceChanging" @click="openGroups"><UiIcon name="users" :size="16" /><span>群聊</span></button></nav>
      <label class="session-search"><UiIcon name="search" :size="16" /><input v-model="sessionSearch" placeholder="搜索" aria-label="搜索会话" /><button v-if="sessionSearch" class="icon-button" aria-label="清空搜索" @click="sessionSearch = ''"><UiIcon name="close" :size="14" /></button></label>
      <nav class="session-navigation" aria-label="会话列表">
        <div v-if="sessionGroups.length" class="session-section-label">工作区</div>
        <section v-for="[path, group] in sessionGroups" :key="path" class="session-group" :class="{ collapsed: collapsedGroups.has(path) && !sessionSearch }">
          <h2 class="workspace-group-heading"><button class="group-toggle" :title="group.directories.join('\n') || group.label" :aria-expanded="!collapsedGroups.has(path) || !!sessionSearch" @click="toggleGroup(path)"><UiIcon v-if="path" :name="group.icon" :style="{ color: group.color }" :size="16" /><span>{{ group.label }}</span></button><button v-if="path" class="icon-button workspace-add" :disabled="loading || submitting || workspaceChanging" :aria-label="`在 ${group.label} 新建会话`" title="新建会话" @click="createWorkspaceSession(path)"><UiIcon name="plus" :size="14" /></button></h2>
          <div v-for="session in group.sessions" v-show="!collapsedGroups.has(path) || !!sessionSearch" :key="session.id" class="session-row" :class="{ selected: session.id === selectedId && !showAutomations && !showMemories && !showGroups, 'menu-open': sessionMenuId === session.id }">
            <button class="session-item" :disabled="submitting || loading || settingsBusy || workspaceChanging" :aria-current="session.id === selectedId && !showAutomations && !showMemories && !showGroups ? 'page' : undefined" :title="session.title" @click="navigateSession(session.id)"><span>{{ session.title }}</span><span v-if="session.id === selectedId && current" class="activity-dot" aria-label="任务运行中"></span></button>
            <button class="icon-button session-more" :disabled="loading || workspaceChanging" :aria-label="`更多操作：${session.title}`" title="更多操作" aria-haspopup="menu" :aria-expanded="sessionMenuId === session.id" :aria-controls="sessionMenuId === session.id ? `session-menu-${session.id}` : undefined" @click="toggleSessionMenu(session, $event)"><UiIcon name="more" :size="16" /></button>
          </div>
        </section>
        <div v-if="!sessionGroups.length && sessionSearch" class="sidebar-empty"><UiIcon name="search" :size="22" /><p>没有匹配的会话</p></div>
      </nav>
      <div class="sidebar-footer"><button class="settings-entry" :disabled="settingsBusy || workspaceChanging" @click="openSettings()" title="设置与偏好"><img class="sidebar-avatar" src="/harness-icon.svg" width="20" height="20" alt="" /><span>本地工作台</span><UiIcon name="settings" :size="16" /></button></div>
      <SidebarResize v-if="!isMobile && !sidebarCollapsed && !showSettings" v-model:width="sidebarWidth" />
    </aside>
    <Teleport to="body">
      <div v-if="sessionMenuSession" :id="`session-menu-${sessionMenuSession.id}`" ref="sessionMenuElement" class="session-menu" :style="sessionMenuPosition" role="menu" :aria-label="`${sessionMenuSession.title} 的操作`" @keydown="navigateSessionMenu">
        <button type="button" role="menuitem" tabindex="-1" :disabled="loading || workspaceChanging" @click="openRename(sessionMenuSession, $event)"><UiIcon name="edit" :size="13" /><span>重命名</span></button>
        <button type="button" class="danger" role="menuitem" tabindex="-1" :disabled="loading || workspaceChanging || submitting || (sessionMenuSession.id === selectedId && (!!current || !!pending))" @click="openDelete(sessionMenuSession, $event)"><UiIcon name="trash" :size="15" /><span>删除</span></button>
      </div>
    </Teleport>
    <SessionRenameDialog v-if="renamingSession" :session="renamingSession" @saved="sessionRenamed" @close="closeRename" />
    <SessionDeleteDialog v-if="deletingSession" :session="deletingSession" @deleted="sessionDeleted" @close="closeDelete" />
    <main v-show="!showSettings && !showAutomations && !showMemories && !showGroups" class="main-panel" :class="{ 'is-welcome': !snapshot?.runs.length }" :inert="isMobile && !sidebarCollapsed || undefined">
      <header class="workspace-header">
        <div class="workspace-title"><button v-if="sidebarCollapsed" class="icon-button" aria-label="展开侧栏" title="展开侧栏" @click="sidebarCollapsed = false"><UiIcon name="panel" /></button><span class="header-breadcrumb">工作台 <span aria-hidden="true">/</span></span><h1>{{ snapshot?.session.title || '新对话' }}</h1></div>
        <div class="header-meta"><span v-if="selectedId && !connected" class="connection" title="正在重新连接">连接中</span></div>
      </header>
      <div v-if="error" class="error-banner" role="alert">{{ error }} <button @click="selectedId ? selectSession(selectedId) : initialize()">重新连接</button></div>
      <div class="workbench-body"><div class="chat-panel">
      <div ref="timeline" class="timeline" aria-label="对话记录" :aria-busy="loading" @scroll.passive="trackTimeline">
        <div v-if="loading" class="empty-state"><span class="loading-indicator"></span><p>正在读取会话…</p></div>
        <div v-else-if="!snapshot?.runs.length" class="welcome">
          <div class="welcome-copy">
            <p class="welcome-eyebrow"><span aria-hidden="true"></span>{{ workspaces.find(value => value.id === snapshot?.session.workspaceId)?.name || '新的任务' }}</p>
            <h2>今天想推进什么？</h2>
          </div>
        </div>
        <article v-for="run in snapshot?.runs" :key="run.id" class="run-card">
          <div class="user-message-wrap"><p class="user-message">{{ run.prompt }}</p><ul v-if="attachmentsOf(run.id).length" class="message-attachments" aria-label="消息附件"><li v-for="item in attachmentsOf(run.id)" :key="item.id"><a :href="`/api/attachments/${item.id}/content`" target="_blank" rel="noopener" :title="item.name"><img v-if="isImage(item.mediaType)" :src="`/api/attachments/${item.id}/content`" :alt="item.name" loading="lazy" /><template v-else><UiIcon name="file" :size="15" /><span>{{ item.name }}</span></template></a></li></ul></div>
          <section class="response" :aria-label="run.model + ' 的回答'">
            <div class="assistant-heading"><img class="brand-icon" src="/harness-icon.svg" width="22" height="22" alt="" /><strong>Harness</strong><span v-if="isActive(run)" class="working-label"><span class="activity-dot"></span>{{ activityLabel(run) }}</span></div>
            <RunTimeline :key="run.id" :run="run" :revision="timelineRevision(run)" :subagents="snapshot?.subagents?.filter(value => value.parentRunId === run.id)" @answer="answers[run.id] = $event" @loaded="timelineLoaded" />
            <MemoryUsage :run-id="run.id" :revision="`${run.status}:${run.context ? 'prepared' : 'pending'}`" />
            <p v-if="isActive(run) ? !run.output && (questionActivity[run.id] || pluginActivity[run.id] || commandActivity[run.id]) : !answerOf(run)" class="output-placeholder">{{ isActive(run) ? `${activityLabel(run)}，请查看下方操作卡片。` : '本次没有生成回答。' }}</p>
            <p v-if="run.detail" class="run-notice" :class="{ 'has-error': ['FAILED', 'TIMED_OUT', 'INTERRUPTED'].includes(run.status) }">{{ run.detail }}</p>
            <ArtifactCards v-if="run.workspace" :run-id="run.id" :workspace="run.workspace" :revision="`${run.status}:${changeRevisions[run.id] || 0}:${connected}`" />
            <FileChanges v-if="run.workspace" :run-id="run.id" :revision="`${run.status}:${changeRevisions[run.id] || 0}:${connected}`" :can-apply="run.status === 'COMPLETED' && !current && !submitting && !pending && snapshot?.session.workspace === run.workspace" :can-revert="!isActive(run) && !current && !submitting && !pending && snapshot?.session.workspace === run.workspace" />
            <Questions :run-id="run.id" :revision="`${run.status}:${questionRevisions[run.id] || 0}:${connected}`" :active="run.status === 'RUNNING'" @activity="questionActivity[run.id] = $event" />
            <Commands v-if="run.workspace" :run-id="run.id" :revision="`${run.status}:${commandRevisions[run.id] || 0}:${connected}`" :active="run.status === 'RUNNING'" @activity="commandActivity[run.id] = $event" />
            <PluginRunDetails :key="run.id" :run-id="run.id" :revision="`${run.status}:${commandRevisions[run.id] || 0}:${connected}`" :active="run.status === 'RUNNING'" @activity="pluginActivity[run.id] = $event" />
            <div v-if="!isActive(run)" class="response-footer"><button class="icon-button" :disabled="!answerOf(run)" :aria-label="copiedRun === run.id ? '已复制回答' : '复制回答'" :title="copiedRun === run.id ? '已复制' : '复制回答'" @click="copyAnswer(run)"><UiIcon :name="copiedRun === run.id ? 'check' : 'copy'" :size="15" /></button><span :title="contextTitle(run) || undefined">{{ runModelLabel(run) }} · {{ effortShortLabel(run.reasoningEffort) }}</span><time>{{ new Date(run.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</time></div>
          </section>
        </article>
        <p v-if="copyError" role="status" class="run-notice">{{ copyError }}</p>
      </div>
      <div class="composer-dock">
        <button v-if="awayFromLatest && snapshot?.runs.length" type="button" class="latest-message" @click="goToLatest"><UiIcon name="down" :size="14" />回到最新</button>
        <form class="composer" @submit.prevent="send" @dragover="onComposerDragover" @drop="onComposerDrop">
          <label class="sr-only" for="prompt">输入问题</label>
          <PluginCommandPicker ref="pluginCommandPicker" :session-id="snapshot?.session.id" :disabled="!!current || submitting || !!pending || loading || workspaceChanging || showSettings" :text="prompt" :input="promptInput" @select="prompt = $event" />
          <MentionPicker ref="mentionPicker" :session-id="snapshot?.session.id" :workspace="!!snapshot?.session.workspace" :multi-root="multiRoot" :disabled="submitting || !!pending || loading || workspaceChanging || showSettings" :text="prompt" :input="promptInput" @select="pickMention" />
          <ComposerAttachments :items="draftAttachments" :mentions="draftMentions" :multi-root="multiRoot" :disabled="submitting || !!pending" @remove="removeAttachment" @remove-mention="removeMention" />
          <textarea id="prompt" @input="resizePrompt" @paste="onPromptPaste" ref="promptInput" v-model="prompt" :placeholder="snapshot?.session.workspace ? '询问这个项目中的任何问题，@ 引用文件，/ 选择插件命令…' : '描述你的问题，可粘贴图片，/ 选择插件命令…'" maxlength="4000" :disabled="!!pending || submitting || loading || workspaceChanging" rows="2" role="combobox" aria-autocomplete="list" aria-haspopup="listbox" :aria-expanded="pluginCommandPicker?.open || mentionPicker?.open || false" :aria-controls="pluginCommandPicker?.activeId ? 'plugin-command-options' : mentionPicker?.activeId ? 'file-mention-options' : undefined" :aria-activedescendant="pluginCommandPicker?.activeId || mentionPicker?.activeId" @keydown="onPromptKeydown" />
          <div v-if="pending" v-show="!submitting" class="pending-note">上次提交结果待确认，重试不会重复创建任务。<template v-if="pending.attachmentIds?.length || pending.mentions?.length">重试将带上 {{ pending.attachmentIds?.length || 0 }} 个附件、{{ pending.mentions?.length || 0 }} 个引用。</template></div>
          <p v-else-if="!model?.configured && !loading" class="configuration-hint"><span>连接一个模型，即可开始对话</span><button type="button" @click="openSettings('models')">配置模型 <UiIcon name="chevron" :size="12" /></button></p>
          <div class="composer-actions">
            <ComposerAddMenu :disabled="submitting || !!pending || loading || workspaceChanging" :workspace="!!snapshot?.session.workspace" @files="addFiles" @mention="startMention" @command="startCommand" />
            <ComposerSelectors :profiles="profiles" :model-id="selectedProfileId" :effort="reasoningEffort" :workspace-id="snapshot?.session.workspaceId || ''" :workspaces="workspaces" :disabled="submitting || !!pending || loading || workspaceChanging" :workspace-disabled="!!current" :approval-mode="approvalMode" @model="chooseProfile" @effort="reasoningEffort = $event" @approval="approvalMode = $event" @settings="openSettings('models')" @workspace="chooseWorkspace" @browse="browseWorkspace()" @edit="browseWorkspace(workspaces.find(value => value.id === snapshot?.session.workspaceId))" />
            <TokenUsageRing :usage="snapshot?.usage" :estimated="latestContext?.tokens ?? 0" :max-tokens="latestContext?.maxTokens ?? 0" />
            <button v-if="current" type="button" class="send-button stop-button" :disabled="cancelling || current.status === 'CANCELLING'" :aria-label="current.status === 'CANCELLING' ? '正在停止' : '停止任务'" title="停止任务" @click="cancel"><UiIcon name="stop" :size="15" /></button>
            <button v-else type="submit" class="send-button" :disabled="loading || submitting || workspaceChanging || (!pending && (!model?.configured || (!prompt.trim() && !draftMentions.length) || attachmentsBlocked || (!!selectedId && !snapshot)))" :aria-label="pending ? '重试提交' : '发送消息'" :title="pending ? '重试提交' : sendShortcutHint"><UiIcon :name="pending ? 'refresh' : 'arrow'" :size="18" /></button>
          </div>
        </form>
        <div v-if="!snapshot?.runs.length && !loading" class="suggestion-list"><button v-for="item in suggestions" :key="item.title" :disabled="submitting || !!pending" @click="useSuggestion(item.prompt)"><UiIcon :name="item.icon" :size="18" /><span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span><UiIcon class="suggestion-arrow" name="chevron" :size="13" /></button></div>
        <div class="composer-caption"><span v-if="prompt.length > 3500">{{ prompt.length }} / 4000</span><span v-else>{{ sendShortcutHint }}</span></div>
      </div>
      </div>
      </div>
    </main>
    <AutomationsPage v-if="showAutomations" v-show="!showSettings" :workspaces="workspaces" :profiles="profiles" :sidebar-collapsed="sidebarCollapsed" :default-workspace-id="snapshot?.session.workspaceId || ''" :default-profile-id="selectedProfileId" :inert="isMobile && !sidebarCollapsed || undefined" @open-session="openAutomationSession" @expand-sidebar="sidebarCollapsed = false" />
    <MemoriesPage v-if="showMemories" v-show="!showSettings" :workspaces="workspaces" :sidebar-collapsed="sidebarCollapsed" :default-workspace-id="snapshot?.session.workspaceId || ''" :inert="isMobile && !sidebarCollapsed || undefined" @open-session="openAutomationSession" @expand-sidebar="sidebarCollapsed = false" @open-settings="openSettings('memory')" />
    <GroupChatsPage v-if="showGroups" v-show="!showSettings" :workspaces="workspaces" :profiles="profiles" :sidebar-collapsed="sidebarCollapsed" :default-profile-id="selectedProfileId" :inert="isMobile && !sidebarCollapsed || undefined" @expand-sidebar="sidebarCollapsed = false" />
    <dialog ref="workspaceDialog" class="workspace-dialog settings-page" aria-labelledby="workspace-dialog-title" @cancel="workspaceChanging && $event.preventDefault()" @close="browsingWorkspace = false">
      <header class="inspector-header"><h2 id="workspace-dialog-title">{{ editingWorkspace ? '编辑工作区' : '新建工作区' }}</h2><button type="button" class="icon-button" :disabled="workspaceChanging" aria-label="关闭工作区设置" @click="closeWorkspaceDialog"><UiIcon name="close" :size="17" /></button></header>
      <WorkspaceSettings v-if="browsingWorkspace" :session-id="snapshot?.session.id" initial-path="" :initial-workspace="editingWorkspace" :disabled="workspaceSyncing || !!current || submitting || !!pending || loading" @busy="workspaceSaving = $event" @saved="workspacePicked" @cancel="closeWorkspaceDialog" />
    </dialog>
    <SettingsPage v-if="showSettings" :initial-profile-id="selectedProfileId" :initial-section="settingsSection" :session="snapshot?.session" :workspaces="workspaces" :creating-workspace="creatingWorkspace" :workspace-disabled="!!current || submitting || !!pending || loading" @close="closeSettings" @busy="settingsBusy = $event" @saved="settingsSaved" @deleted="profileDeleted" @changed="loadProfiles().catch(() => {})" @workspace-saved="workspacePicked" />
  </div>
</template>

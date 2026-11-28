<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import MarkdownContent from '../MarkdownContent.vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from '../bots/BotLookAvatar.vue'
import BotMarkdownContent from '../bots/BotMarkdownContent.vue'
import BotSkillFilesModal from '../bots/BotSkillFilesModal.vue'
import BotSkillOfferCard from '../bots/BotSkillOfferCard.vue'
import {
  botStatusLabel,
  extractBotApps,
  formatElapsed,
  formatRoutineSchedule,
  formatTimeDivider,
  isImageMediaType,
  miniAppIconName,
  parseAttachments,
  parseSuggestions,
  shouldShowTimeDivider,
  type BotCard,
  type BotEntry,
  type BotFile,
  type BotFileContent,
  type BotLanDevice,
  type BotMiniApp,
  type BotMiniAppBundle,
  type BotMiniAppVersion,
  type BotSkillFileContent,
  type BotSkillFileView,
  type BotSkillOffer,
  type BotSkillView,
  type BotThreadSnapshot,
  type BotWorkspaceSnapshot,
  type IslandNotice,
  type PendingBotApproval,
  type StreamingReply,
  type UploadedAttachment,
} from '../bots/api'
import {
  lanApi,
  LanUnauthorizedError,
  normalizeLanAvatarUrl,
  type LanPairRequest,
} from './lanApi'

const OPEN_BOT_KEY = 'harness.phone.openBotId'

type Stage = 'loading' | 'pairing' | 'ready' | 'removed'
type ConnState = 'online' | 'reconnecting' | 'offline'
type SheetTab = 'activity' | 'apps' | 'files' | 'playbooks'

const stage = ref<Stage>('loading')
const wasPaired = ref(false)
const connectionState = ref<ConnState>('online')
const device = ref<BotLanDevice | null>(null)
const allowRemoteApproval = ref(false)
const pairInfo = ref<LanPairRequest | null>(null)
const pairCountdownSec = ref(0)
const pairError = ref('')

const workspace = ref<BotWorkspaceSnapshot | null>(null)
const openBotId = ref<string>(readSavedBotId())
const thread = ref<BotThreadSnapshot | null>(null)
const approvals = ref<PendingBotApproval[]>([])
const streamingByBot = ref<Record<string, StreamingReply>>({})

// Search & hidden bots section
const searchQuery = ref('')
const showHiddenBots = ref(false)

// Chat state
const timelineRef = ref<HTMLElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const composerText = ref('')
const quoteEntry = ref<BotEntry | null>(null)
const pendingAttachments = ref<UploadedAttachment[]>([])
const uploadingFiles = ref(false)
const sending = ref(false)
const confirmingDeleteSeq = ref<number | null>(null)
const copiedSeq = ref<number | null>(null)
const viewingMiniApp = ref<{ id: string; name: string } | null>(null)
const nowMs = ref(Date.now())
const errorToast = ref('')
let errorToastTimer: ReturnType<typeof setTimeout> | null = null

// Cross-bot reply toast
const replyToast = ref<{ id: number; botId: string; botName: string; text: string } | null>(null)
let replyToastTimer: ReturnType<typeof setTimeout> | null = null

// Bottom sheet drawer
const sheetOpen = ref(false)
const sheetTab = ref<SheetTab>('activity')
const fileFilter = ref<'all' | 'image' | 'doc'>('all')
const previewFile = ref<{ file: BotFile; isImage: boolean; content?: BotFileContent; rawUrl?: string; loading: boolean; error?: string } | null>(null)
const highlightedPlaybookId = ref<string | null>(null)

// Full-screen Mobile Mini App Viewer
const openAppBundle = ref<BotMiniAppBundle | null>(null)
const previewAppBundle = ref<BotMiniAppBundle | null>(null)
const appVersionsOpen = ref(false)
const appFrameRef = ref<HTMLIFrameElement | null>(null)
const appReloadNonce = ref(0)
const appPageError = ref('')
const appFlash = ref('')
const appLatestData = ref<unknown>({})

// Lightbox & Skill Files Modal
const lightboxSrc = ref('')
const lightboxAlt = ref('')
const phoneAutoScroll = ref(true)
const skillFilesModal = ref<{
  title: string
  subtitle: string
  loadFiles: () => Promise<BotSkillFileView[]>
  loadFile: (path: string) => Promise<BotSkillFileContent>
} | null>(null)

// Theme (3-way: system -> light -> dark)
type ThemePref = 'system' | 'light' | 'dark'
function readThemePref(): ThemePref {
  const api = (window as unknown as { harnessTheme?: { getPreference?: () => ThemePref } }).harnessTheme
  return api?.getPreference?.() ?? 'system'
}
const themePref = ref<ThemePref>(readThemePref())

// Timers & EventSource handles
let pairPollTimer: ReturnType<typeof setTimeout> | null = null
let countdownTimer: ReturnType<typeof setInterval> | null = null
let tickTimer: ReturnType<typeof setInterval> | null = null
let eventSource: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectDelayMs = 1000
let refreshInFlight = false
let refreshPending = false

function readSavedBotId(): string {
  try {
    return window.localStorage.getItem(OPEN_BOT_KEY) || ''
  } catch {
    return ''
  }
}

function saveOpenBotId(id: string) {
  try {
    if (id) window.localStorage.setItem(OPEN_BOT_KEY, id)
    else window.localStorage.removeItem(OPEN_BOT_KEY)
  } catch {
    // Ignore private browsing storage errors.
  }
}

function showError(message: string) {
  errorToast.value = message
  if (errorToastTimer) clearTimeout(errorToastTimer)
  errorToastTimer = setTimeout(() => {
    errorToast.value = ''
  }, 3600)
}

function flashApp(message: string) {
  appFlash.value = message
  setTimeout(() => {
    if (appFlash.value === message) appFlash.value = ''
  }, 2000)
}

function toggleTheme() {
  const api = (window as unknown as {
    harnessTheme?: { getPreference?: () => ThemePref; setPreference: (v: ThemePref) => void }
  }).harnessTheme
  if (!api) return
  const order: ThemePref[] = ['system', 'light', 'dark']
  const cur = api.getPreference?.() ?? themePref.value
  const next = order[(order.indexOf(cur) + 1) % order.length]
  api.setPreference(next)
  themePref.value = next
}

const themeIconName = computed(() => (themePref.value === 'system' ? 'monitor' : themePref.value === 'dark' ? 'moon' : 'sun'))
const themeTitle = computed(() =>
  themePref.value === 'system'
    ? '外观：跟随系统（点击切到浅色）'
    : themePref.value === 'light'
      ? '外观：浅色（点击切到深色）'
      : '外观：深色（点击切到跟随系统）',
)

const phoneCanSend = computed(() =>
  !sending.value && !uploadingFiles.value && (!!composerText.value.trim() || pendingAttachments.value.length > 0),
)
const showPhoneStop = computed(() => !!activeStream.value && !phoneCanSend.value)

function onPhoneStopClick(event: MouseEvent) {
  if (event.detail >= 2) return
  void stopReply()
}

function onPhoneTimelineScroll() {
  const el = timelineRef.value
  if (!el) return
  phoneAutoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

function jumpPhoneLatest() {
  phoneAutoScroll.value = true
  scrollToBottom()
}

function parseSkillOffer(raw: string): BotSkillOffer | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<BotSkillOffer>
    if (!parsed || typeof parsed.offerId !== 'string' || !Array.isArray(parsed.skills)) return null
    return {
      offerId: parsed.offerId,
      targetBotId: parsed.targetBotId || '',
      targetBotName: parsed.targetBotName || '',
      skills: parsed.skills,
      skipped: Array.isArray(parsed.skipped) ? parsed.skipped : [],
      installed: Array.isArray(parsed.installed) ? parsed.installed : null,
    }
  } catch {
    return null
  }
}

function openInstalledSkillFiles(botId: string, skillName: string) {
  skillFilesModal.value = {
    title: skillName,
    subtitle: '已安装技能包（只读）',
    loadFiles: () => lanApi.installedSkillFiles(botId, skillName),
    loadFile: path => lanApi.installedSkillFile(botId, skillName, path),
  }
}

function openSkillFromChat(target: string) {
  const trimmed = target.trim()
  const slash = trimmed.indexOf('/')
  const ownerId = slash > 0 ? trimmed.slice(0, slash) : openBotId.value
  const skillName = slash > 0 ? trimmed.slice(slash + 1) : trimmed
  if (ownerId && skillName) {
    openInstalledSkillFiles(ownerId, skillName)
  } else {
    sheetTab.value = 'playbooks'
    sheetOpen.value = true
  }
}

async function openLibraryFileByPath(relativePath: string) {
  const found = (thread.value?.files ?? []).find(f => f.relativePath === relativePath)
  const synthetic: BotFile = found ?? {
    id: relativePath,
    botId: openBotId.value,
    taskId: '',
    relativePath,
    sizeBytes: 0,
    updatedAt: '',
  }
  sheetTab.value = 'files'
  sheetOpen.value = true
  await openLibraryFile(synthetic)
}

// ── Derived Data ────────────────────────────────────────────────
const allBots = computed<BotCard[]>(() => workspace.value?.bots ?? [])
const botMap = computed(() => {
  const map = new Map<string, BotCard>()
  for (const card of allBots.value) map.set(card.bot.id, card)
  return map
})

const currentCard = computed<BotCard | null>(() => {
  if (!openBotId.value) return null
  return thread.value?.card ?? botMap.value.get(openBotId.value) ?? null
})

const filteredBots = computed<BotCard[]>(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return allBots.value.filter(c => !c.bot.hidden)
  return allBots.value.filter(c =>
    c.bot.name.toLowerCase().includes(q)
    || c.bot.roleSummary.toLowerCase().includes(q)
    || c.preview.toLowerCase().includes(q),
  )
})

const pinnedBots = computed(() =>
  filteredBots.value.filter(c => c.bot.pinned).sort((a, b) => a.bot.ordinal - b.bot.ordinal),
)

const sectionedGroups = computed(() => {
  const sections = [...(workspace.value?.sections ?? [])].sort((a, b) => a.ordinal - b.ordinal)
  const nonPinned = filteredBots.value.filter(c => !c.bot.pinned)
  const groups: { id: string; name: string; bots: BotCard[] }[] = []
  const knownIds = new Set(sections.map(s => s.id))
  for (const sec of sections) {
    const list = nonPinned.filter(c => c.bot.sectionId === sec.id).sort((a, b) => a.bot.ordinal - b.bot.ordinal)
    if (list.length) groups.push({ id: sec.id, name: sec.name, bots: list })
  }
  const ungrouped = nonPinned
    .filter(c => !c.bot.sectionId || !knownIds.has(c.bot.sectionId))
    .sort((a, b) => a.bot.ordinal - b.bot.ordinal)
  if (ungrouped.length) groups.push({ id: '__ungrouped__', name: groups.length ? '其他成员' : '全部成员', bots: ungrouped })
  return groups
})

const hiddenBots = computed(() =>
  searchQuery.value.trim() ? [] : allBots.value.filter(c => c.bot.hidden),
)

const visibleEntries = computed<BotEntry[]>(() =>
  (thread.value?.entries ?? []).filter(e => !e.deleted),
)

const activeStream = computed<StreamingReply | null>(() => {
  if (!openBotId.value) return null
  return streamingByBot.value[openBotId.value] ?? thread.value?.streaming ?? null
})

const activeTasks = computed(() =>
  (thread.value?.tasks ?? []).filter(t => t.status === 'RUNNING' || t.status === 'QUEUED'),
)

const currentBotApprovals = computed<PendingBotApproval[]>(() => {
  if (!openBotId.value) return []
  const fromThread = thread.value?.pendingApprovals ?? []
  if (fromThread.length) return fromThread
  return approvals.value.filter(a => a.botId === openBotId.value || a.originBotId === openBotId.value)
})

const latestSuggestions = computed<string[]>(() => {
  if (activeStream.value) return []
  for (let i = visibleEntries.value.length - 1; i >= 0; i--) {
    const entry = visibleEntries.value[i]
    if (entry.kind === 'ASSISTANT' || entry.kind === 'TASK_RESULT') {
      return parseSuggestions(entry.suggestionsJson)
    }
    if (entry.kind === 'USER') return []
  }
  return []
})

const appMap = computed(() => {
  const map = new Map<string, BotMiniApp>()
  for (const app of thread.value?.apps ?? []) map.set(app.id, app)
  return map
})

const filteredFiles = computed(() => {
  const files = thread.value?.files ?? []
  if (fileFilter.value === 'all') return files
  return files.filter(f => {
    const img = isFileImage(f.relativePath)
    return fileFilter.value === 'image' ? img : !img
  })
})

const activeAppBundle = computed(() => previewAppBundle.value ?? openAppBundle.value)
const shownAppVersion = computed(() => activeAppBundle.value?.shownVersion ?? activeAppBundle.value?.app.version ?? 1)
const isPreviewingOldApp = computed(() =>
  !!previewAppBundle.value && !!openAppBundle.value && shownAppVersion.value !== openAppBundle.value.app.version,
)
const sortedAppVersions = computed<BotMiniAppVersion[]>(() =>
  [...(openAppBundle.value?.versions ?? [])].sort((a, b) => b.version - a.version),
)

const pairDigits = computed(() => {
  const code = pairInfo.value?.code || '----'
  return code.padEnd(4, '-').slice(0, 4).split('')
})

const formattedCountdown = computed(() => {
  const sec = Math.max(0, pairCountdownSec.value)
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}:${String(s).padStart(2, '0')}`
})

// ── Auth & Pairing Flow ─────────────────────────────────────────
async function boot() {
  stage.value = 'loading'
  pairError.value = ''
  try {
    const me = await lanApi.me()
    device.value = me.device
    allowRemoteApproval.value = me.allowRemoteApproval
    wasPaired.value = true
    stage.value = 'ready'
    connectionState.value = navigator.onLine ? 'online' : 'offline'
    await refreshAll(true)
    connectSse()
  } catch (err) {
    if (err instanceof LanUnauthorizedError) {
      if (wasPaired.value) {
        enterRemovedState()
      } else {
        await startPairing()
      }
    } else {
      pairError.value = err instanceof Error ? err.message : '无法连接到电脑端，请确认处于同一局域网'
      stage.value = 'pairing'
    }
  }
}

function enterRemovedState() {
  disconnectSse()
  stopPairPolling()
  stage.value = 'removed'
  openAppBundle.value = null
  sheetOpen.value = false
}

async function startPairing() {
  disconnectSse()
  stopPairPolling()
  pairError.value = ''
  stage.value = 'pairing'
  try {
    pairInfo.value = await lanApi.requestPair()
    updatePairCountdown()
    schedulePairPoll()
  } catch (err) {
    pairError.value = err instanceof Error ? err.message : '获取配对码失败'
  }
}

function stopPairPolling() {
  if (pairPollTimer) {
    clearTimeout(pairPollTimer)
    pairPollTimer = null
  }
}

function updatePairCountdown() {
  if (!pairInfo.value) {
    pairCountdownSec.value = 0
    return
  }
  const left = Math.ceil((new Date(pairInfo.value.expiresAt).getTime() - Date.now()) / 1000)
  pairCountdownSec.value = Math.max(0, left)
}

function schedulePairPoll() {
  stopPairPolling()
  pairPollTimer = setTimeout(async () => {
    if (stage.value !== 'pairing' || !pairInfo.value) return
    try {
      const result = await lanApi.pollPair(pairInfo.value.requestId)
      if (result.status === 'PAIRED') {
        pairInfo.value = null
        await boot()
        return
      }
      if (result.status === 'EXPIRED') {
        pairInfo.value = null
        pairCountdownSec.value = 0
        pairError.value = '配对码已过期，请重新获取'
        return
      }
    } catch {
      // Transient LAN drop during pairing poll; keep retrying.
    }
    schedulePairPoll()
  }, 1500)
}

// ── SSE & Refresh ───────────────────────────────────────────────
function handleAuthError(err: unknown): boolean {
  if (err instanceof LanUnauthorizedError) {
    enterRemovedState()
    return true
  }
  return false
}

async function refreshAll(scrollAfter = false) {
  if (stage.value !== 'ready') return
  if (refreshInFlight) {
    refreshPending = true
    return
  }
  refreshInFlight = true
  try {
    const ws = await lanApi.workspace()
    workspace.value = ws
    if (openBotId.value && !ws.bots.some(b => b.bot.id === openBotId.value)) {
      openBotId.value = ''
      saveOpenBotId('')
      thread.value = null
    }
    const tasks: Promise<unknown>[] = []
    if (openBotId.value) {
      const targetId = openBotId.value
      tasks.push(
        lanApi.thread(targetId).then(snap => {
          if (openBotId.value === targetId) {
            const prevLen = thread.value?.entries.length ?? 0
            thread.value = snap
            if (!snap.streaming && streamingByBot.value[targetId]) {
              const copy = { ...streamingByBot.value }
              delete copy[targetId]
              streamingByBot.value = copy
            }
            if (scrollAfter || snap.entries.length > prevLen) {
              void nextTick(() => scrollToBottom())
            }
          }
        }),
      )
    }
    if (allowRemoteApproval.value) {
      tasks.push(
        lanApi.approvals().then(list => {
          approvals.value = list
        }),
      )
    }
    await Promise.all(tasks)
    if (openAppBundle.value) {
      const latestApp = thread.value?.apps.find(a => a.id === openAppBundle.value?.app.id)
      if (latestApp && latestApp.updatedAt !== openAppBundle.value.app.updatedAt) {
        const refreshed = await lanApi.miniApp(latestApp.id)
        openAppBundle.value = refreshed
        appLatestData.value = refreshed.data
        pushDataToAppFrame(refreshed.data)
      }
    }
    connectionState.value = 'online'
  } catch (err) {
    if (!handleAuthError(err)) {
      connectionState.value = navigator.onLine ? 'reconnecting' : 'offline'
    }
  } finally {
    refreshInFlight = false
    if (refreshPending) {
      refreshPending = false
      void refreshAll(false)
    }
  }
}

function disconnectSse() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

function connectSse() {
  disconnectSse()
  if (stage.value !== 'ready') return
  const es = new EventSource(lanApi.eventsUrl())
  eventSource = es

  es.onopen = () => {
    reconnectDelayMs = 1000
    connectionState.value = 'online'
  }

  es.addEventListener('ping', () => {
    connectionState.value = 'online'
  })

  es.addEventListener('revoked', () => {
    enterRemovedState()
  })

  es.addEventListener('bot-stream', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data) as { botId: string; runId: string; status: string; output: string }
      if (!data?.botId) return
      streamingByBot.value = {
        ...streamingByBot.value,
        [data.botId]: { runId: data.runId, status: data.status, output: data.output },
      }
      if (data.botId === openBotId.value) {
        void nextTick(() => scrollToBottomIfNear())
      }
    } catch {
      // Ignore malformed chunk.
    }
  })

  es.addEventListener('bot-event', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data) as { notices?: IslandNotice[] }
      if (Array.isArray(data.notices)) {
        for (const notice of data.notices) {
          if (notice.kind === 'BOT_REPLIED' && notice.botId && notice.botId !== openBotId.value) {
            showReplyToast(notice)
          }
        }
      }
    } catch {
      // Ignore malformed notice.
    }
    void refreshAll(false)
  })

  es.onerror = () => {
    es.close()
    if (eventSource === es) eventSource = null
    if (stage.value !== 'ready') return
    connectionState.value = navigator.onLine ? 'reconnecting' : 'offline'
    const delay = reconnectDelayMs
    reconnectDelayMs = Math.min(8000, reconnectDelayMs * 2)
    reconnectTimer = setTimeout(async () => {
      try {
        const me = await lanApi.me()
        allowRemoteApproval.value = me.allowRemoteApproval
        await refreshAll(false)
        connectSse()
      } catch (err) {
        if (!handleAuthError(err)) {
          connectSse()
        }
      }
    }, delay)
  }
}

function showReplyToast(notice: IslandNotice) {
  replyToast.value = {
    id: notice.id,
    botId: notice.botId,
    botName: notice.botName || botMap.value.get(notice.botId)?.bot.name || 'Bot',
    text: notice.detail || notice.title || '有新回复',
  }
  if (replyToastTimer) clearTimeout(replyToastTimer)
  replyToastTimer = setTimeout(() => {
    replyToast.value = null
  }, 6000)
}

// ── Navigation & Chat Actions ───────────────────────────────────
async function openBot(botId: string) {
  if (!botId) return
  openBotId.value = botId
  saveOpenBotId(botId)
  sheetOpen.value = false
  previewFile.value = null
  quoteEntry.value = null
  viewingMiniApp.value = null
  replyToast.value = null
  confirmingDeleteSeq.value = null
  try {
    await lanApi.markSeen(botId)
    await refreshAll(true)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '加载对话失败')
  }
}

async function backToList() {
  openBotId.value = ''
  saveOpenBotId('')
  thread.value = null
  sheetOpen.value = false
  viewingMiniApp.value = null
  quoteEntry.value = null
  await refreshAll(false)
}

function scrollToBottom() {
  const el = timelineRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function scrollToBottomIfNear() {
  const el = timelineRef.value
  if (!el) return
  const dist = el.scrollHeight - el.scrollTop - el.clientHeight
  if (dist < 140) el.scrollTop = el.scrollHeight
}

async function onPickFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  uploadingFiles.value = true
  try {
    for (const file of files) {
      if (pendingAttachments.value.length >= 8) {
        showError('单条消息最多附带 8 个附件')
        break
      }
      const uploaded = await lanApi.uploadAttachment(file)
      pendingAttachments.value = [...pendingAttachments.value, uploaded]
    }
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '附件上传失败')
  } finally {
    uploadingFiles.value = false
  }
}

function removePendingAttachment(id: string) {
  pendingAttachments.value = pendingAttachments.value.filter(a => a.id !== id)
}

async function sendMessage(overrideText?: string, opts?: { fromMiniApp?: boolean; appId?: string }) {
  const botId = openBotId.value
  if (!botId) return
  const text = (overrideText ?? composerText.value).trim()
  const attachmentIds = overrideText === undefined ? pendingAttachments.value.map(a => a.id) : []
  if (!text && !attachmentIds.length) return
  if (sending.value) return
  sending.value = true
  try {
    await lanApi.postMessage(botId, {
      requestId: crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`,
      content: text,
      quoteSeq: overrideText === undefined ? (quoteEntry.value?.seq ?? null) : null,
      attachmentIds: attachmentIds.length ? attachmentIds : undefined,
      viewingMiniAppId: opts?.appId ?? viewingMiniApp.value?.id ?? undefined,
      fromMiniApp: opts?.fromMiniApp,
    })
    if (overrideText === undefined) {
      composerText.value = ''
      quoteEntry.value = null
      pendingAttachments.value = []
    }
    await refreshAll(true)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function stopReply() {
  if (!openBotId.value) return
  try {
    await lanApi.stop(openBotId.value)
    await refreshAll(false)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '停止失败')
  }
}

async function copyEntry(entry: BotEntry) {
  try {
    await navigator.clipboard.writeText(entry.content)
    copiedSeq.value = entry.seq
    setTimeout(() => {
      if (copiedSeq.value === entry.seq) copiedSeq.value = null
    }, 1500)
  } catch {
    showError('当前浏览器不允许自动复制')
  }
}

async function deleteEntry(seq: number) {
  if (!openBotId.value) return
  try {
    await lanApi.deleteEntries(openBotId.value, [seq])
    confirmingDeleteSeq.value = null
    await refreshAll(false)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '删除失败')
  }
}

// ── Approvals, Tasks, Routines, Files ───────────────────────────
async function handleApproval(commandId: string, approve: boolean) {
  try {
    if (approve) await lanApi.approveCommand(commandId)
    else await lanApi.rejectCommand(commandId)
    await refreshAll(false)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '审批操作失败')
  }
}

async function cancelTask(taskId: string) {
  try {
    await lanApi.cancelTask(taskId)
    await refreshAll(false)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '停止任务失败')
  }
}

async function toggleRoutine(routineId: string, enabled: boolean) {
  try {
    await lanApi.toggleRoutine(routineId, enabled)
    await refreshAll(false)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '切换例行任务失败')
  }
}

async function runRoutine(routineId: string) {
  try {
    await lanApi.runRoutine(routineId)
    await refreshAll(false)
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '触发例行任务失败')
  }
}

function isFileImage(path: string): boolean {
  return /\.(png|jpe?g|gif|webp|bmp)$/i.test(path)
}

function isFileMarkdown(path: string): boolean {
  return /\.(md|markdown)$/i.test(path)
}

async function openLibraryFile(file: BotFile) {
  if (!openBotId.value) return
  const isImage = isFileImage(file.relativePath)
  if (isImage) {
    const rawUrl = lanApi.rawFileUrl(openBotId.value, file.relativePath)
    previewFile.value = { file, isImage: true, rawUrl, loading: false }
    return
  }
  previewFile.value = { file, isImage: false, loading: true }
  try {
    const content = await lanApi.fileContent(openBotId.value, file.relativePath)
    previewFile.value = { file, isImage: false, content, loading: false }
  } catch (err) {
    if (!handleAuthError(err)) {
      previewFile.value = {
        file,
        isImage: false,
        loading: false,
        error: err instanceof Error ? err.message : '读取文件失败',
      }
    }
  }
}

function openPlaybookFromChat(flowId: string) {
  highlightedPlaybookId.value = flowId
  sheetTab.value = 'playbooks'
  sheetOpen.value = true
}

// ── Mobile Mini App Viewer & Bridge ─────────────────────────────
async function openMiniApp(appId: string) {
  try {
    const bundle = await lanApi.miniApp(appId)
    openAppBundle.value = bundle
    previewAppBundle.value = null
    appVersionsOpen.value = false
    appPageError.value = ''
    appLatestData.value = bundle.data
    viewingMiniApp.value = { id: bundle.app.id, name: bundle.app.name }
    sheetOpen.value = false
  } catch (err) {
    if (!handleAuthError(err)) showError(err instanceof Error ? err.message : '打开小程序失败')
  }
}

function closeMiniApp() {
  openAppBundle.value = null
  previewAppBundle.value = null
  appVersionsOpen.value = false
  appPageError.value = ''
}

async function selectAppVersion(version: number) {
  if (!openAppBundle.value) return
  appVersionsOpen.value = false
  appPageError.value = ''
  if (version === openAppBundle.value.app.version) {
    previewAppBundle.value = null
    return
  }
  try {
    previewAppBundle.value = await lanApi.miniAppVersion(openAppBundle.value.app.id, version)
  } catch (err) {
    if (!handleAuthError(err)) flashApp(err instanceof Error ? err.message : '加载版本失败')
  }
}

async function restorePreviewAppVersion() {
  if (!openAppBundle.value || !previewAppBundle.value) return
  try {
    await lanApi.restoreMiniApp(openAppBundle.value.app.id, shownAppVersion.value)
    previewAppBundle.value = null
    const fresh = await lanApi.miniApp(openAppBundle.value.app.id)
    openAppBundle.value = fresh
    appLatestData.value = fresh.data
    flashApp('已恢复为新版本')
    await refreshAll(false)
  } catch (err) {
    if (!handleAuthError(err)) flashApp(err instanceof Error ? err.message : '恢复失败')
  }
}

function editMiniAppInChat() {
  if (!openAppBundle.value) return
  const app = openAppBundle.value.app
  viewingMiniApp.value = { id: app.id, name: app.name }
  composerText.value = `帮我调整一下小程序《${app.name}》：`
  closeMiniApp()
}

async function askBotToFixMiniApp() {
  if (!openAppBundle.value) return
  const app = openAppBundle.value.app
  const err = appPageError.value
  appPageError.value = ''
  const prompt = `我当前打开的小程序《${app.name}》v${shownAppVersion.value} 页面出现脚本报错：${err}。请帮我排查并修复后发布新版本。`
  await sendMessage(prompt, { fromMiniApp: true, appId: app.id })
  flashApp('已请求 Bot 修复')
}

function pushDataToAppFrame(data: unknown) {
  appFrameRef.value?.contentWindow?.postMessage({ source: 'harness-host', type: 'data', data }, '*')
}

async function onWindowMessage(event: MessageEvent) {
  if (!appFrameRef.value || event.source !== appFrameRef.value.contentWindow || !openAppBundle.value) return
  const msg = event.data as { source?: string; id?: string; action?: string; payload?: unknown } | null
  if (!msg || msg.source !== 'harness-mini-app') return

  if (msg.action === 'error') {
    appPageError.value = String(msg.payload || '脚本错误').slice(0, 260)
    return
  }
  if (!msg.id) return
  const reply = (result?: unknown, error?: string) => {
    appFrameRef.value?.contentWindow?.postMessage({ source: 'harness-host', id: msg.id, result, error }, '*')
  }
  try {
    if (msg.action === 'getData') {
      reply(appLatestData.value)
    } else if (msg.action === 'setData') {
      await lanApi.saveMiniAppData(openAppBundle.value.app.id, msg.payload ?? {})
      appLatestData.value = msg.payload ?? {}
      flashApp('数据已保存')
      reply({ ok: true })
    } else if (msg.action === 'sendMessage') {
      const text = typeof msg.payload === 'string' ? msg.payload.trim() : ''
      if (!text) {
        reply(undefined, '消息不能为空')
        return
      }
      await lanApi.postMessage(openAppBundle.value.app.botId, {
        requestId: crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`,
        content: text,
        viewingMiniAppId: openAppBundle.value.app.id,
        fromMiniApp: true,
      })
      flashApp('已发给 Bot')
      reply({ ok: true })
      void refreshAll(false)
    } else {
      reply(undefined, '不支持的操作')
    }
  } catch (err) {
    if (handleAuthError(err)) {
      reply(undefined, '设备连接已失效')
    } else {
      reply(undefined, err instanceof Error ? err.message : '执行失败')
    }
  }
}

function formatTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function onOnlineChange() {
  if (!navigator.onLine) {
    connectionState.value = 'offline'
  } else if (stage.value === 'ready') {
    connectionState.value = 'reconnecting'
    void refreshAll(false)
    connectSse()
  }
}

watch(openBotId, id => {
  if (!id) return
  void nextTick(() => scrollToBottom())
})

onMounted(() => {
  window.addEventListener('message', onWindowMessage)
  window.addEventListener('online', onOnlineChange)
  window.addEventListener('offline', onOnlineChange)
  countdownTimer = setInterval(() => {
    if (stage.value === 'pairing' && pairInfo.value) updatePairCountdown()
  }, 1000)
  tickTimer = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
  void boot()
})

onUnmounted(() => {
  disconnectSse()
  stopPairPolling()
  if (countdownTimer) clearInterval(countdownTimer)
  if (tickTimer) clearInterval(tickTimer)
  if (errorToastTimer) clearTimeout(errorToastTimer)
  if (replyToastTimer) clearTimeout(replyToastTimer)
  window.removeEventListener('message', onWindowMessage)
  window.removeEventListener('online', onOnlineChange)
  window.removeEventListener('offline', onOnlineChange)
})
</script>

<template>
  <div class="phone-shell">
    <!-- Connection Status Banner -->
    <div
      v-if="stage === 'ready' && connectionState !== 'online'"
      class="phone-conn-banner"
      :class="{ 'is-offline': connectionState === 'offline' }"
      role="status"
    >
      <span class="phone-conn-dot"></span>
      <span>{{ connectionState === 'offline' ? '网络已断开，等待恢复连接…' : '与电脑端连接波动，正在自动重连…' }}</span>
    </div>

    <!-- Loading Screen -->
    <main v-if="stage === 'loading'" class="phone-center-screen">
      <div class="phone-pair-card">
        <div class="phone-pair-logo">
          <span class="loading-indicator"></span>
        </div>
        <h1>正在连接电脑端…</h1>
        <p class="phone-pair-desc">正在校验设备配对状态与 Bot 团队列表</p>
      </div>
    </main>

    <!-- Pairing Screen -->
    <main v-else-if="stage === 'pairing'" class="phone-center-screen">
      <div class="phone-pair-card">
        <div class="phone-pair-logo">
          <UiIcon name="shield" :size="24" />
        </div>
        <h1>配对电脑上的 Bot 团队</h1>
        <p class="phone-pair-desc">
          请在电脑端打开 <strong>设置 → 移动伴侣设备</strong>，输入下方 4 位配对码完成安全绑定：
        </p>
        <div class="phone-pair-digits" aria-label="4 位配对码">
          <span v-for="(digit, idx) in pairDigits" :key="idx" class="phone-pair-digit">{{ digit }}</span>
        </div>
        <div v-if="pairInfo && pairCountdownSec > 0" class="phone-pair-meta">
          <UiIcon name="clock" :size="13" />
          <span>有效期剩余 {{ formattedCountdown }} · 等待电脑端确认…</span>
        </div>
        <p v-if="pairError" class="dialog-error" style="width: 100%; margin: 0;">{{ pairError }}</p>
        <button type="button" class="primary phone-pair-btn" @click="startPairing">
          {{ pairInfo && pairCountdownSec > 0 ? '刷新配对码' : '重新获取配对码' }}
        </button>
      </div>
    </main>

    <!-- Removed / Revoked Screen -->
    <main v-else-if="stage === 'removed'" class="phone-center-screen">
      <div class="phone-pair-card">
        <div class="phone-pair-logo" style="background: var(--danger-bg); color: var(--danger);">
          <UiIcon name="warning" :size="24" />
        </div>
        <h1>设备连接已断开</h1>
        <p class="phone-pair-desc">
          当前设备令牌已失效或已在电脑端被移除。如需继续在手机上使用 Bot 团队，请重新配对。
        </p>
        <button type="button" class="primary phone-pair-btn" @click="startPairing">重新配对</button>
      </div>
    </main>

    <!-- Ready: Mobile Bot List -->
    <template v-else-if="!openBotId">
      <header class="phone-header">
        <div class="phone-header-title">
          <div class="phone-header-copy">
            <h1>Bot 团队</h1>
            <small>{{ allBots.length }} 位成员 · 仅查看对话与任务</small>
          </div>
        </div>
        <span v-if="device" class="phone-device-pill" :title="device.name">{{ device.name }}</span>
        <button type="button" class="phone-icon-btn" :title="themeTitle" @click="toggleTheme">
          <UiIcon :name="themeIconName" :size="16" />
        </button>
      </header>

      <main class="phone-list-scroll">
        <!-- Pending Remote Approvals -->
        <section v-if="allowRemoteApproval && approvals.length" class="phone-approvals-box">
          <div class="phone-approvals-head">
            <UiIcon name="shield" :size="15" />
            <span>待确认命令（{{ approvals.length }}）</span>
          </div>
          <div v-for="item in approvals" :key="item.commandId" class="phone-approval-item">
            <div class="phone-card-row">
              <strong>{{ item.taskTitle || '后台任务命令' }}</strong>
              <small style="color: var(--muted)">{{ botMap.get(item.botId)?.bot.name || 'Bot' }}</small>
            </div>
            <pre class="phone-approval-cmd">{{ item.commandText }}</pre>
            <small v-if="item.purpose" style="color: var(--muted)">{{ item.purpose }}</small>
            <div class="phone-approval-actions">
              <button type="button" class="primary" @click="handleApproval(item.commandId, true)">批准执行</button>
              <button type="button" @click="handleApproval(item.commandId, false)">拒绝</button>
            </div>
          </div>
        </section>

        <!-- Search -->
        <div class="phone-search-box">
          <UiIcon name="search" :size="15" />
          <input v-model="searchQuery" type="search" placeholder="搜索 Bot 名称或职责…" />
          <button v-if="searchQuery" type="button" class="icon-button" @click="searchQuery = ''">
            <UiIcon name="close" :size="13" />
          </button>
        </div>

        <!-- Pinned Bots -->
        <section v-if="pinnedBots.length">
          <div class="phone-section-label"><span>置顶</span><span>{{ pinnedBots.length }}</span></div>
          <div class="phone-bot-group">
            <button
              v-for="card in pinnedBots"
              :key="card.bot.id"
              type="button"
              class="phone-bot-row"
              @click="openBot(card.bot.id)"
            >
              <BotLookAvatar
                :shape="card.bot.shape"
                :color="card.bot.color"
                :avatar-url="normalizeLanAvatarUrl(card.avatarUrl)"
                :outfit-json="card.bot.outfitJson"
                :size="44"
                :status="card.status"
                :progress="card.experience.progress"
                show-ring
              />
              <div class="phone-bot-info">
                <div class="phone-bot-top">
                  <strong>{{ card.bot.name }}</strong>
                  <span class="phone-lvl-chip">Lv.{{ card.experience.level }}</span>
                </div>
                <span
                  class="phone-bot-preview"
                  :class="{ 'is-live': card.status === 'WORKING' || card.status === 'REPLYING' }"
                >
                  {{ card.activeTasks?.[0]?.statusText || card.preview || card.bot.roleSummary || '随时待命' }}
                </span>
              </div>
              <div class="phone-bot-right">
                <span v-if="card.unreadCount > 0" class="phone-unread-badge">
                  {{ card.unreadCount > 99 ? '99+' : card.unreadCount }}
                </span>
                <span
                  v-else-if="card.status !== 'IDLE'"
                  class="phone-status-pill"
                  :class="{ 'is-working': card.status === 'WORKING' || card.status === 'REPLYING', 'is-failed': card.status === 'FAILED' }"
                >
                  {{ botStatusLabel(card.status) }}
                </span>
                <UiIcon name="chevron" :size="15" style="color: var(--faint)" />
              </div>
            </button>
          </div>
        </section>

        <!-- Sectioned & Ungrouped Bots -->
        <section v-for="group in sectionedGroups" :key="group.id">
          <div class="phone-section-label"><span>{{ group.name }}</span><span>{{ group.bots.length }}</span></div>
          <div class="phone-bot-group">
            <button
              v-for="card in group.bots"
              :key="card.bot.id"
              type="button"
              class="phone-bot-row"
              @click="openBot(card.bot.id)"
            >
              <BotLookAvatar
                :shape="card.bot.shape"
                :color="card.bot.color"
                :avatar-url="normalizeLanAvatarUrl(card.avatarUrl)"
                :outfit-json="card.bot.outfitJson"
                :size="44"
                :status="card.status"
                :progress="card.experience.progress"
                show-ring
              />
              <div class="phone-bot-info">
                <div class="phone-bot-top">
                  <strong>{{ card.bot.name }}</strong>
                  <span class="phone-lvl-chip">Lv.{{ card.experience.level }}</span>
                </div>
                <span
                  class="phone-bot-preview"
                  :class="{ 'is-live': card.status === 'WORKING' || card.status === 'REPLYING' }"
                >
                  {{ card.activeTasks?.[0]?.statusText || card.preview || card.bot.roleSummary || '随时待命' }}
                </span>
              </div>
              <div class="phone-bot-right">
                <span v-if="card.unreadCount > 0" class="phone-unread-badge">
                  {{ card.unreadCount > 99 ? '99+' : card.unreadCount }}
                </span>
                <span
                  v-else-if="card.status !== 'IDLE'"
                  class="phone-status-pill"
                  :class="{ 'is-working': card.status === 'WORKING' || card.status === 'REPLYING', 'is-failed': card.status === 'FAILED' }"
                >
                  {{ botStatusLabel(card.status) }}
                </span>
                <UiIcon name="chevron" :size="15" style="color: var(--faint)" />
              </div>
            </button>
          </div>
        </section>

        <!-- Hidden Bots Collapsible -->
        <section v-if="hiddenBots.length">
          <button
            type="button"
            class="phone-section-label"
            style="width: 100%; border: 0; background: transparent; padding: 6px 4px;"
            @click="showHiddenBots = !showHiddenBots"
          >
            <span>已隐藏（{{ hiddenBots.length }}）</span>
            <UiIcon :name="showHiddenBots ? 'down' : 'chevron'" :size="14" />
          </button>
          <div v-if="showHiddenBots" class="phone-bot-group">
            <button
              v-for="card in hiddenBots"
              :key="card.bot.id"
              type="button"
              class="phone-bot-row"
              style="opacity: 0.85;"
              @click="openBot(card.bot.id)"
            >
              <BotLookAvatar
                :shape="card.bot.shape"
                :color="card.bot.color"
                :avatar-url="normalizeLanAvatarUrl(card.avatarUrl)"
                :outfit-json="card.bot.outfitJson"
                :size="40"
                :status="card.status"
              />
              <div class="phone-bot-info">
                <div class="phone-bot-top">
                  <strong>{{ card.bot.name }}</strong>
                  <span class="phone-lvl-chip">Lv.{{ card.experience.level }}</span>
                </div>
                <span class="phone-bot-preview">{{ card.preview || card.bot.roleSummary || '已隐藏' }}</span>
              </div>
              <UiIcon name="chevron" :size="15" style="color: var(--faint)" />
            </button>
          </div>
        </section>
      </main>
    </template>

    <!-- Ready: Mobile Chat View -->
    <template v-else>
      <header class="phone-header">
        <button type="button" class="phone-icon-btn" aria-label="返回列表" @click="backToList">
          <UiIcon name="back" :size="17" />
        </button>
        <div class="phone-header-title">
          <BotLookAvatar
            v-if="currentCard"
            :shape="currentCard.bot.shape"
            :color="currentCard.bot.color"
            :avatar-url="normalizeLanAvatarUrl(currentCard.avatarUrl)"
            :outfit-json="currentCard.bot.outfitJson"
            :size="36"
            :status="currentCard.status"
          />
          <div class="phone-header-copy">
            <strong>{{ currentCard?.bot.name || '加载中…' }}</strong>
            <small>
              <template v-if="activeStream">正在回复…</template>
              <template v-else-if="activeTasks[0]?.statusText">{{ activeTasks[0].statusText }}</template>
              <template v-else-if="currentCard">{{ botStatusLabel(currentCard.status) }} · Lv.{{ currentCard.experience.level }}</template>
            </small>
          </div>
        </div>
        <button type="button" class="phone-icon-btn" aria-label="打开 Bot 面板" @click="sheetOpen = true">
          <UiIcon name="panel" :size="17" />
        </button>
      </header>

      <!-- Cross-Bot Reply Toast -->
      <div v-if="replyToast" class="phone-reply-toast" role="status">
        <BotLookAvatar
          v-if="botMap.get(replyToast.botId)"
          :shape="botMap.get(replyToast.botId)!.bot.shape"
          :color="botMap.get(replyToast.botId)!.bot.color"
          :avatar-url="normalizeLanAvatarUrl(botMap.get(replyToast.botId)!.avatarUrl)"
          :outfit-json="botMap.get(replyToast.botId)!.bot.outfitJson"
          :size="34"
        />
        <div class="phone-reply-toast-body">
          <strong>{{ replyToast.botName }} 回复了</strong>
          <small>{{ replyToast.text }}</small>
        </div>
        <button type="button" class="primary" style="min-height: 30px; padding: 4px 10px; font-size: 12px;" @click="openBot(replyToast.botId)">
          查看
        </button>
        <button type="button" class="icon-button" aria-label="关闭提醒" @click="replyToast = null">
          <UiIcon name="close" :size="13" />
        </button>
      </div>

      <!-- Chat Timeline -->
      <main ref="timelineRef" class="phone-chat-scroll" @scroll="onPhoneTimelineScroll">
        <div v-if="!visibleEntries.length && !activeStream" class="phone-empty-chat">
          <BotLookAvatar
            v-if="currentCard"
            :shape="currentCard.bot.shape"
            :color="currentCard.bot.color"
            :avatar-url="normalizeLanAvatarUrl(currentCard.avatarUrl)"
            :outfit-json="currentCard.bot.outfitJson"
            :size="58"
            :progress="currentCard.experience.progress"
            show-ring
          />
          <strong style="color: var(--text); font-size: 15px;">{{ currentCard?.bot.name }}</strong>
          <span>{{ currentCard?.bot.roleSummary || '在下方发消息开始对话，支持图片与文件附件。' }}</span>
        </div>

        <template v-for="(entry, idx) in visibleEntries" :key="entry.id">
          <div
            v-if="shouldShowTimeDivider(idx > 0 ? visibleEntries[idx - 1].createdAt : null, entry.createdAt)"
            class="phone-time-divider"
          >
            <span>{{ formatTimeDivider(entry.createdAt) }}</span>
          </div>

          <article
            class="phone-msg-row"
            :class="{
              'is-user': entry.kind === 'USER',
              'is-bot': entry.kind === 'ASSISTANT' || entry.kind === 'TASK_RESULT' || entry.kind === 'SKILL_OFFER',
              'is-task-result': entry.kind === 'TASK_RESULT',
              'is-system': entry.kind === 'SYSTEM',
            }"
          >
            <div v-if="entry.quotePreview" class="phone-msg-quote">回复：{{ entry.quotePreview }}</div>

            <div v-if="entry.kind === 'SKILL_OFFER'" class="phone-inline-offer">
              <BotSkillOfferCard
                v-if="parseSkillOffer(entry.attachmentsJson)"
                :offer="parseSkillOffer(entry.attachmentsJson)!"
                :current-bot-id="openBotId"
                read-only
                @answer="() => {}"
                @view-files="() => {}"
              />
              <div v-else class="phone-msg-bubble">{{ entry.content }}</div>
            </div>

            <div v-else class="phone-msg-bubble">
              <template v-if="entry.kind === 'USER' || entry.kind === 'SYSTEM'">
                {{ entry.content }}
              </template>
              <BotMarkdownContent
                v-else
                :content="entry.content"
                :bot-id="openBotId"
                :bots="allBots"
                :playbooks="thread?.playbooks || []"
                :apps="thread?.apps || []"
                :raw-file-url="lanApi.rawFileUrl"
                @open-bot="openBot"
                @open-flow="openPlaybookFromChat"
                @open-skill="openSkillFromChat"
                @open-app="openMiniApp"
                @open-file="openLibraryFileByPath"
                @open-image="(url, name) => { lightboxSrc = url; lightboxAlt = name }"
              />
            </div>

            <!-- Attachments -->
            <div v-if="entry.kind !== 'SKILL_OFFER' && parseAttachments(entry.attachmentsJson).length" class="phone-msg-attachments">
              <template v-for="att in parseAttachments(entry.attachmentsJson)" :key="att.id">
                <img
                  v-if="isImageMediaType(att.mediaType)"
                  :src="lanApi.attachmentUrl(att.id)"
                  :alt="att.name"
                  class="phone-msg-img"
                  @click="lightboxSrc = lanApi.attachmentUrl(att.id); lightboxAlt = att.name"
                />
                <a
                  v-else
                  :href="lanApi.attachmentUrl(att.id)"
                  target="_blank"
                  rel="noopener"
                  class="phone-msg-file-chip"
                >
                  <UiIcon name="file" :size="13" />
                  <span>{{ att.name }}</span>
                </a>
              </template>
            </div>

            <!-- Mini App Cards (capped at 2) -->
            <button
              v-for="appRef in entry.kind === 'SKILL_OFFER' ? [] : extractBotApps(entry.content).slice(0, 2)"
              :key="`app-${appRef.appId}`"
              type="button"
              class="phone-miniapp-card"
              @click="openMiniApp(appRef.appId)"
            >
              <span
                class="phone-miniapp-badge"
                :style="{ background: appMap.get(appRef.appId)?.color || '#2d5d9c' }"
              >
                <UiIcon :name="miniAppIconName(appMap.get(appRef.appId)?.icon)" :size="18" />
              </span>
              <div class="phone-miniapp-meta">
                <strong>{{ appMap.get(appRef.appId)?.name || appRef.name }}</strong>
                <small>
                  v{{ appMap.get(appRef.appId)?.version || 1 }} · {{ appMap.get(appRef.appId)?.description || '点击打开交互式小程序' }}
                </small>
              </div>
              <UiIcon name="chevron" :size="16" style="color: var(--faint)" />
            </button>

            <!-- Meta & Actions -->
            <div v-if="entry.kind !== 'SYSTEM' && entry.kind !== 'SKILL_OFFER'" class="phone-msg-meta">
              <span>{{ formatTime(entry.createdAt) }}</span>
              <span v-if="entry.modelLabel">{{ entry.modelLabel }}</span>
              <button type="button" @click="quoteEntry = entry">引用</button>
              <button type="button" @click="copyEntry(entry)">{{ copiedSeq === entry.seq ? '已复制' : '复制' }}</button>
              <template v-if="confirmingDeleteSeq === entry.seq">
                <button type="button" class="is-danger" @click="deleteEntry(entry.seq)">确认删</button>
                <button type="button" @click="confirmingDeleteSeq = null">取消</button>
              </template>
              <button v-else type="button" @click="confirmingDeleteSeq = entry.seq">删除</button>
            </div>
          </article>
        </template>

        <!-- Real-time SSE Streaming Bubble -->
        <article v-if="activeStream" class="phone-msg-row is-bot">
          <div class="phone-msg-bubble">
            <BotMarkdownContent
              v-if="activeStream.output"
              :content="activeStream.output"
              :bot-id="openBotId"
              :bots="allBots"
              :playbooks="thread?.playbooks || []"
              :apps="thread?.apps || []"
              :raw-file-url="lanApi.rawFileUrl"
              @open-bot="openBot"
              @open-flow="openPlaybookFromChat"
              @open-skill="openSkillFromChat"
              @open-app="openMiniApp"
              @open-file="openLibraryFileByPath"
              @open-image="(url, name) => { lightboxSrc = url; lightboxAlt = name }"
            />
            <span v-else class="bot-typing-dots" aria-label="正在回复">
              <span></span><span></span><span></span>
            </span>
            <span v-if="activeStream.output" class="bot-stream-cursor"></span>
          </div>
          <div class="phone-msg-meta">
            <span>实时生成中…</span>
          </div>
        </article>

        <!-- Active Background Tasks Inline -->
        <div v-for="task in activeTasks" :key="task.id" class="phone-task-inline">
          <span class="activity-dot"></span>
          <div class="phone-task-inline-body">
            <strong>{{ task.title }}</strong>
            <small>
              {{ task.statusText || (task.status === 'QUEUED' ? '排队等待执行…' : '后台处理中…') }}
              · {{ formatElapsed(task.createdAt, nowMs) }}
            </small>
          </div>
          <button type="button" style="min-height: 30px; padding: 3px 10px; font-size: 12px;" @click="cancelTask(task.id)">
            停止
          </button>
        </div>

        <!-- Inline Pending Approvals for This Bot -->
        <section v-if="currentBotApprovals.length" class="phone-approvals-box">
          <div class="phone-approvals-head">
            <UiIcon name="shield" :size="15" />
            <span>后台任务请求执行命令（{{ currentBotApprovals.length }}）</span>
          </div>
          <div v-for="item in currentBotApprovals" :key="item.commandId" class="phone-approval-item">
            <strong style="font-size: 13px;">{{ item.taskTitle || '命令审批' }}</strong>
            <pre class="phone-approval-cmd">{{ item.commandText }}</pre>
            <small v-if="item.purpose" style="color: var(--muted)">{{ item.purpose }}</small>
            <div v-if="allowRemoteApproval" class="phone-approval-actions">
              <button type="button" class="primary" @click="handleApproval(item.commandId, true)">批准</button>
              <button type="button" @click="handleApproval(item.commandId, false)">拒绝</button>
            </div>
            <small v-else style="color: var(--warning)">电脑端未开启远程审批，请在电脑端确认此命令。</small>
          </div>
        </section>

        <!-- Suggestions -->
        <div v-if="latestSuggestions.length" class="phone-suggestions">
          <button
            v-for="(sug, idx) in latestSuggestions"
            :key="idx"
            type="button"
            @click="sendMessage(sug)"
          >
            {{ sug }}
          </button>
        </div>
      </main>

      <!-- Mobile Composer -->
      <footer class="phone-composer-wrap">
        <button
          v-if="!phoneAutoScroll && visibleEntries.length"
          type="button"
          class="phone-scroll-latest"
          @click="jumpPhoneLatest"
        >
          回到最新 ↓
        </button>

        <div v-if="viewingMiniApp" class="phone-composer-strip">
          <span>📱 正在查看《{{ viewingMiniApp.name }}》</span>
          <button type="button" aria-label="清除上下文" @click="viewingMiniApp = null">
            <UiIcon name="close" :size="13" />
          </button>
        </div>

        <div v-if="quoteEntry" class="phone-composer-strip">
          <span>引用：{{ quoteEntry.content.slice(0, 60) }}</span>
          <button type="button" aria-label="取消引用" @click="quoteEntry = null">
            <UiIcon name="close" :size="13" />
          </button>
        </div>

        <div v-if="pendingAttachments.length" class="phone-pending-files">
          <span v-for="att in pendingAttachments" :key="att.id" class="phone-pending-file">
            <UiIcon :name="isImageMediaType(att.mediaType) ? 'image' : 'file'" :size="13" />
            <span>{{ att.name }}</span>
            <button type="button" aria-label="移除附件" @click="removePendingAttachment(att.id)">
              <UiIcon name="close" :size="12" />
            </button>
          </span>
        </div>

        <form class="phone-composer-row" @submit.prevent="sendMessage()">
          <input
            ref="fileInputRef"
            type="file"
            multiple
            accept="image/*,.txt,.md,.json,.csv,.js,.ts,.py,.java,.html,.css,.xml,.yaml,.yml,.log"
            class="sr-only"
            @change="onPickFiles"
          />
          <button
            type="button"
            class="phone-icon-btn"
            :disabled="uploadingFiles"
            aria-label="添加图片或文件"
            @click="fileInputRef?.click()"
          >
            <span v-if="uploadingFiles" class="loading-indicator"></span>
            <UiIcon v-else name="paperclip" :size="17" />
          </button>
          <textarea
            v-model="composerText"
            rows="1"
            :placeholder="activeStream ? '输入内容可排队发送，留空可点停止…' : `给 ${currentCard?.bot.name || 'Bot'} 发消息…`"
          ></textarea>
          <button
            v-if="showPhoneStop"
            type="button"
            class="phone-send-btn primary danger"
            aria-label="停止回复"
            @click="onPhoneStopClick"
          >
            <UiIcon name="stop" :size="15" />
          </button>
          <button
            v-else
            type="submit"
            class="phone-send-btn primary"
            :disabled="!phoneCanSend"
            :aria-label="activeStream ? '排队发送' : '发送消息'"
          >
            <UiIcon name="arrow" :size="17" />
          </button>
        </form>
      </footer>
    </template>

    <!-- Bot Detail Bottom Sheet -->
    <div v-if="sheetOpen && thread" class="phone-sheet-scrim" @click.self="sheetOpen = false">
      <section class="phone-sheet" role="dialog" :aria-label="`${thread.card.bot.name} 面板`">
        <div class="phone-sheet-handle"></div>
        <header class="phone-sheet-head">
          <div class="phone-header-title">
            <BotLookAvatar
              :shape="thread.card.bot.shape"
              :color="thread.card.bot.color"
              :avatar-url="normalizeLanAvatarUrl(thread.card.avatarUrl)"
              :outfit-json="thread.card.bot.outfitJson"
              :size="34"
              :progress="thread.card.experience.progress"
              show-ring
            />
            <div class="phone-header-copy">
              <strong>{{ thread.card.bot.name }}</strong>
              <small>Lv.{{ thread.card.experience.level }} · {{ thread.card.bot.roleSummary || '成员详情' }}</small>
            </div>
          </div>
          <button type="button" class="phone-icon-btn" aria-label="关闭面板" @click="sheetOpen = false">
            <UiIcon name="close" :size="16" />
          </button>
        </header>

        <nav class="phone-sheet-tabs">
          <button type="button" :class="{ 'is-active': sheetTab === 'activity' }" @click="sheetTab = 'activity'">
            动态与例行（{{ thread.tasks.length + thread.routines.length }}）
          </button>
          <button type="button" :class="{ 'is-active': sheetTab === 'apps' }" @click="sheetTab = 'apps'">
            小程序（{{ thread.apps.length }}）
          </button>
          <button type="button" :class="{ 'is-active': sheetTab === 'files' }" @click="sheetTab = 'files'; previewFile = null">
            资料库（{{ thread.files.length }}）
          </button>
          <button type="button" :class="{ 'is-active': sheetTab === 'playbooks' }" @click="sheetTab = 'playbooks'">
            能力与记忆（{{ thread.playbooks.length + (thread.skills?.length || 0) }}）
          </button>
        </nav>

        <div class="phone-sheet-body">
          <!-- Tab 1: Activity & Routines -->
          <template v-if="sheetTab === 'activity'">
            <div class="phone-section-label"><span>例行任务</span><span>{{ thread.routines.length }}</span></div>
            <div v-if="!thread.routines.length" class="phone-card" style="color: var(--muted)">暂无例行任务</div>
            <div v-for="rt in thread.routines" :key="rt.id" class="phone-card">
              <div class="phone-card-row">
                <strong>{{ rt.name }}</strong>
                <div style="display: flex; gap: 6px;">
                  <button type="button" style="min-height: 28px; padding: 2px 9px; font-size: 12px;" @click="runRoutine(rt.id)">
                    运行
                  </button>
                  <button
                    type="button"
                    style="min-height: 28px; padding: 2px 9px; font-size: 12px;"
                    @click="toggleRoutine(rt.id, !rt.enabled)"
                  >
                    {{ rt.enabled ? '暂停' : '启用' }}
                  </button>
                </div>
              </div>
              <small style="color: var(--muted)">{{ formatRoutineSchedule(rt) }} · {{ rt.enabled ? '已开启' : '已暂停' }}</small>
            </div>

            <div class="phone-section-label" style="margin-top: 6px;"><span>最近后台任务</span><span>{{ thread.tasks.length }}</span></div>
            <div v-if="!thread.tasks.length" class="phone-card" style="color: var(--muted)">暂无后台任务记录</div>
            <div v-for="tk in thread.tasks.slice(0, 10)" :key="tk.id" class="phone-card">
              <div class="phone-card-row">
                <strong>{{ tk.title }}</strong>
                <button
                  v-if="tk.status === 'RUNNING' || tk.status === 'QUEUED'"
                  type="button"
                  style="min-height: 28px; padding: 2px 9px; font-size: 12px; color: var(--danger);"
                  @click="cancelTask(tk.id)"
                >
                  停止
                </button>
                <span v-else class="phone-status-pill" :class="{ 'is-failed': tk.status === 'FAILED' }">{{ tk.status }}</span>
              </div>
              <small v-if="tk.statusText || tk.summary || tk.errorDetail" style="color: var(--muted)">
                {{ tk.statusText || tk.summary || tk.errorDetail }}
              </small>
            </div>
          </template>

          <!-- Tab 2: Mini Apps -->
          <template v-else-if="sheetTab === 'apps'">
            <div v-if="!thread.apps.length" class="phone-card" style="color: var(--muted)">
              该 Bot 还没有生成小程序，可以在聊天里让它制作互动小工具或看板。
            </div>
            <button
              v-for="app in thread.apps"
              :key="app.id"
              type="button"
              class="phone-miniapp-card"
              style="width: 100%;"
              @click="openMiniApp(app.id)"
            >
              <span class="phone-miniapp-badge" :style="{ background: app.color || '#2d5d9c' }">
                <UiIcon :name="miniAppIconName(app.icon)" :size="18" />
              </span>
              <div class="phone-miniapp-meta">
                <strong>{{ app.name }}</strong>
                <small>v{{ app.version }} · {{ app.description || '交互式小程序' }}</small>
              </div>
              <span class="phone-ref-chip">打开</span>
            </button>
          </template>

          <!-- Tab 3: Library Files -->
          <template v-else-if="sheetTab === 'files'">
            <template v-if="previewFile">
              <div class="phone-card-row">
                <button type="button" style="min-height: 30px; padding: 3px 10px; font-size: 12px;" @click="previewFile = null">
                  ← 返回文件列表
                </button>
                <small style="color: var(--muted); overflow: hidden; text-overflow: ellipsis;">{{ previewFile.file.relativePath }}</small>
              </div>
              <div v-if="previewFile.loading" class="phone-card">加载文件内容中…</div>
              <div v-else-if="previewFile.error" class="dialog-error">{{ previewFile.error }}</div>
              <div v-else-if="previewFile.isImage && previewFile.rawUrl" class="phone-file-preview" style="text-align: center;">
                <img
                  :src="previewFile.rawUrl"
                  :alt="previewFile.file.relativePath"
                  style="max-width: 100%; border-radius: 10px; cursor: pointer;"
                  @click="lightboxSrc = previewFile.rawUrl!; lightboxAlt = previewFile.file.relativePath"
                />
              </div>
              <div v-else-if="previewFile.content" class="phone-file-preview">
                <MarkdownContent
                  v-if="isFileMarkdown(previewFile.file.relativePath)"
                  :content="previewFile.content.content"
                />
                <pre v-else>{{ previewFile.content.content }}</pre>
              </div>
            </template>
            <template v-else>
              <div style="display: flex; gap: 6px;">
                <button type="button" class="phone-ref-chip" :style="{ opacity: fileFilter === 'all' ? 1 : 0.6 }" @click="fileFilter = 'all'">全部</button>
                <button type="button" class="phone-ref-chip" :style="{ opacity: fileFilter === 'image' ? 1 : 0.6 }" @click="fileFilter = 'image'">图片</button>
                <button type="button" class="phone-ref-chip" :style="{ opacity: fileFilter === 'doc' ? 1 : 0.6 }" @click="fileFilter = 'doc'">文档</button>
              </div>
              <div v-if="!filteredFiles.length" class="phone-card" style="color: var(--muted)">暂无资料库文件</div>
              <button
                v-for="file in filteredFiles"
                :key="file.id"
                type="button"
                class="phone-card"
                style="text-align: left;"
                @click="openLibraryFile(file)"
              >
                <div class="phone-card-row">
                  <span style="display: inline-flex; align-items: center; gap: 7px; min-width: 0;">
                    <UiIcon :name="isFileImage(file.relativePath) ? 'image' : 'file'" :size="15" />
                    <strong style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ file.relativePath }}</strong>
                  </span>
                  <small style="color: var(--muted); flex-shrink: 0;">{{ formatSize(file.sizeBytes) }}</small>
                </div>
              </button>
            </template>
          </template>

          <!-- Tab 4: Skills, Playbooks & Facts -->
          <template v-else>
            <div class="phone-section-label"><span>已装技能包（只读）</span><span>{{ thread.skills?.length || 0 }}</span></div>
            <div v-if="!thread.skills?.length" class="phone-card" style="color: var(--muted)">
              暂无已装技能包（可在电脑端安装 SKILL.md 技能包）
            </div>
            <button
              v-for="sk in (thread.skills || [] as BotSkillView[])"
              :key="sk.id"
              type="button"
              class="phone-card"
              style="text-align: left;"
              @click="openInstalledSkillFiles(sk.botId, sk.name)"
            >
              <div class="phone-card-row">
                <span style="display: inline-flex; align-items: center; gap: 6px; min-width: 0;">
                  <UiIcon name="skill" :size="14" />
                  <strong>{{ sk.name }}</strong>
                  <span v-if="sk.version" class="phone-lvl-chip">v{{ sk.version }}</span>
                </span>
                <small style="color: var(--muted)">{{ sk.fileCount }} 文件</small>
              </div>
              <small style="color: var(--muted)">{{ sk.description }}</small>
            </button>

            <div class="phone-section-label" style="margin-top: 8px;"><span>做法清单（Playbooks）</span><span>{{ thread.playbooks.length }}</span></div>
            <div v-if="!thread.playbooks.length" class="phone-card" style="color: var(--muted)">暂无做法清单</div>
            <div
              v-for="pb in thread.playbooks"
              :key="pb.id"
              class="phone-card"
              :style="highlightedPlaybookId === pb.id ? { borderColor: 'var(--accent)' } : undefined"
            >
              <strong>{{ pb.name }}</strong>
              <small v-if="pb.whenToUse" style="color: var(--muted)">适用：{{ pb.whenToUse }}</small>
              <div v-if="pb.steps" style="white-space: pre-wrap; font-size: 12.5px;">{{ pb.steps }}</div>
            </div>

            <div class="phone-section-label" style="margin-top: 8px;"><span>已记住的偏好与事实</span><span>{{ thread.userFacts.length + thread.botFacts.length }}</span></div>
            <div v-if="!thread.userFacts.length && !thread.botFacts.length" class="phone-card" style="color: var(--muted)">暂无记忆条目</div>
            <div v-for="fact in [...thread.userFacts, ...thread.botFacts]" :key="fact.id" class="phone-card">
              <div class="phone-card-row">
                <span class="phone-lvl-chip">{{ fact.scope === 'USER' ? '关于你' : '团队/职责' }}</span>
                <small style="color: var(--faint)">{{ formatTime(fact.updatedAt) }}</small>
              </div>
              <span>{{ fact.content }}</span>
            </div>
          </template>
        </div>
      </section>
    </div>

    <BotSkillFilesModal
      v-if="skillFilesModal"
      :title="skillFilesModal.title"
      :subtitle="skillFilesModal.subtitle"
      :load-files="skillFilesModal.loadFiles"
      :load-file="skillFilesModal.loadFile"
      @close="skillFilesModal = null"
    />

    <!-- Full-Screen Mobile Mini App Viewer -->
    <div v-if="openAppBundle && activeAppBundle" class="phone-app-modal" role="dialog" :aria-label="openAppBundle.app.name">
      <header class="phone-header">
        <button type="button" class="phone-icon-btn" aria-label="关闭小程序" @click="closeMiniApp">
          <UiIcon name="close" :size="17" />
        </button>
        <div class="phone-header-title">
          <div class="phone-header-copy">
            <strong>{{ openAppBundle.app.name }}</strong>
            <small>
              v{{ shownAppVersion }}
              <template v-if="appFlash"> · {{ appFlash }}</template>
              <template v-else-if="isPreviewingOldApp"> · 正在预览历史版本</template>
            </small>
          </div>
        </div>
        <button type="button" class="phone-icon-btn" title="在聊天中让 Bot 改版" @click="editMiniAppInChat">
          <UiIcon name="chat" :size="16" />
        </button>
        <button type="button" class="phone-icon-btn" title="版本历史" @click="appVersionsOpen = !appVersionsOpen">
          <UiIcon name="clock" :size="16" />
        </button>
        <button type="button" class="phone-icon-btn" title="重新加载" @click="appReloadNonce += 1; appPageError = ''">
          <UiIcon name="refresh" :size="16" />
        </button>
        <a
          class="phone-icon-btn"
          :href="lanApi.miniAppExportUrl(openAppBundle.app.id, shownAppVersion)"
          :download="`${openAppBundle.app.name}-v${shownAppVersion}.html`"
          title="导出离线 HTML"
        >
          <UiIcon name="download" :size="16" />
        </a>
      </header>

      <div v-if="isPreviewingOldApp" class="miniapp-preview-bar">
        <span>历史版本 v{{ shownAppVersion }}</span>
        <div class="miniapp-preview-actions">
          <button type="button" class="is-primary" @click="restorePreviewAppVersion">恢复此版</button>
          <button type="button" @click="previewAppBundle = null">返回最新</button>
        </div>
      </div>

      <div class="phone-app-stage">
        <iframe
          :key="`${openAppBundle.app.id}-${shownAppVersion}-${appReloadNonce}`"
          ref="appFrameRef"
          class="phone-app-frame"
          sandbox="allow-scripts"
          referrerpolicy="no-referrer"
          :title="openAppBundle.app.name"
          :srcdoc="activeAppBundle.html"
          @load="pushDataToAppFrame(appLatestData)"
        ></iframe>

        <div v-if="appPageError" class="miniapp-error-banner" role="alert">
          <span class="miniapp-error-text">{{ appPageError }}</span>
          <button type="button" class="miniapp-error-fix" @click="askBotToFixMiniApp">让 Bot 修复</button>
          <button type="button" class="miniapp-error-close" aria-label="忽略" @click="appPageError = ''">
            <UiIcon name="close" :size="12" />
          </button>
        </div>

        <div v-if="appVersionsOpen" class="miniapp-sheet-scrim" @click.self="appVersionsOpen = false">
          <div class="miniapp-sheet">
            <header class="miniapp-sheet-head">
              <strong>历史版本（共 {{ Math.max(1, sortedAppVersions.length) }} 版）</strong>
              <button type="button" class="icon-button" @click="appVersionsOpen = false">
                <UiIcon name="close" :size="13" />
              </button>
            </header>
            <div class="miniapp-versions-list">
              <button
                v-for="ver in sortedAppVersions"
                :key="ver.version"
                type="button"
                class="miniapp-version-row"
                :class="{ 'is-current': ver.version === shownAppVersion }"
                @click="selectAppVersion(ver.version)"
              >
                <span class="miniapp-ver">v{{ ver.version }}</span>
                <div class="miniapp-version-meta">
                  <strong>{{ ver.changeSummary || `版本 v${ver.version}` }}</strong>
                  <small>{{ formatTime(ver.createdAt) }}</small>
                </div>
                <span v-if="ver.version === openAppBundle.app.version" class="miniapp-latest-tag">最新</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Image Lightbox -->
    <div v-if="lightboxSrc" class="phone-lightbox" @click.self="lightboxSrc = ''">
      <button type="button" class="phone-lightbox-close" aria-label="关闭预览" @click="lightboxSrc = ''">
        <UiIcon name="close" :size="20" />
      </button>
      <img :src="lightboxSrc" :alt="lightboxAlt" />
    </div>

    <!-- Error Toast -->
    <div v-if="errorToast" class="phone-error-toast" role="alert">{{ errorToast }}</div>
  </div>
</template>

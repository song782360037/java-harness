import { api, type ApprovalMode, type Snapshot } from '../api'
export {
  BOT_AVATARS,
  BOT_AVATAR_CATEGORIES,
  BOT_COLORS,
  BOT_SHAPES,
  buildBotAvatarSvg,
  getBotAvatar,
  renderBotAvatarInner,
  type BotAvatarCategory,
  type BotAvatarCategoryInfo,
  type BotAvatarSpec,
  type BotShape,
} from './avatars'
export {
  OUTFIT_PIECES,
  OUTFIT_SLOTS,
  isOutfitUnlocked,
  nextUnlockLevel,
  parseOutfitJson,
  renderOutfitOverlays,
  takeOffSlot,
  toggleOutfitPiece,
  unlockedAtLevel,
  wornInSlot,
  type OutfitId,
  type OutfitPiece,
  type OutfitSlot,
} from './outfits'
export {
  activeMentionQuery,
  botStatusLabel,
  botTierLabel,
  deskIdleActivity,
  extractBotApps,
  extractBotFlows,
  extractBotMentions,
  extractBotSkills,
  formatElapsed,
  formatRoutineSchedule,
  formatTimeDivider,
  isImageMediaType,
  miniAppIconName,
  parseAttachments,
  parseSuggestions,
  renderBotMarkdownContent,
  shouldShowTimeDivider,
  type BotAppMatch,
  type BotAttachmentMeta,
  type BotExperience,
  type BotFlowMatch,
  type BotMentionMatch,
  type BotSkillMatch,
  type BotStatus,
  type DeskIdleActivity,
} from './model'
import type { BotShape } from './avatars'
import type { BotExperience, BotStatus } from './model'

export interface BotMeta {
  onboarded: boolean
  userDisplayName: string
  updatedAt: string
}

export interface BotSection {
  id: string
  name: string
  ordinal: number
  collapsed: boolean
  createdAt: string
}

export interface BotRecord {
  id: string
  sectionId: string
  name: string
  roleSummary: string
  systemPrompt: string
  main: boolean
  shape: BotShape
  color: string
  avatarPath: string
  outfitJson: string
  hidden: boolean
  pinned: boolean
  ordinal: number
  workspaceId: string
  chatProfileId: string
  chatReasoningEffort: string | null
  taskProfileId: string
  taskReasoningEffort: string | null
  externalAgentId: string
  approvalMode: ApprovalMode
  chatSessionId: string
  lastSeenAt: string
  createdAt: string
  archived: boolean
  revision: number
  lastSeq: number
}

export interface BotTask {
  id: string
  botId: string
  originBotId: string
  delegatedByEntryId: string
  routineId: string
  playbookId: string
  title: string
  instruction: string
  sessionId: string
  runId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED'
  statusText: string
  summary: string
  errorDetail: string
  modelLabel: string
  fallbackFrom: string
  silentTargetChat: boolean
  createdAt: string
  updatedAt: string
  finishedAt: string | null
}

export interface BotCard {
  bot: BotRecord
  status: BotStatus
  preview: string
  failedReason: string
  activeTaskCount: number
  activeTasks: BotTask[]
  routineCount: number
  playbookCount: number
  skillCount: number
  queuedCount: number
  pendingApprovalCount: number
  unreadCount: number
  avatarUrl: string
  experience: BotExperience
}

export interface BotFact {
  id: string
  scope: 'USER' | 'BOT'
  botId: string
  content: string
  createdAt: string
  updatedAt: string
}

export interface PendingBotApproval {
  commandId: string
  runId: string
  sessionId: string
  botId: string
  originBotId: string
  taskId: string
  taskTitle: string
  commandText: string
  directory: string
  purpose: string
  status: 'PENDING' | 'REVIEWING' | string
  createdAt: string
}

export interface UploadedAttachment {
  id: string
  name: string
  mediaType: string
  size: number
}

export interface BotWebSearchSettings {
  provider: 'NONE' | 'DUCKDUCKGO' | 'DEEPSEEK' | 'TAVILY' | 'BRAVE' | 'CUSTOM' | string
  endpointUrl: string
  apiKeyConfigured: boolean
  apiKeyHint: string
  deepSeekKeyAvailable: boolean
  fallbackFreeEnabled: boolean
  maxResults: number
  timeoutSeconds: number
  revision: number
}

export interface BotLimits {
  maxConcurrentTasks: number
  taskTimeoutMinutes: number
  commandTimeoutSeconds: number
  maxFactsPerScope: number
  modelSilenceSeconds: number
  revision: number
  updatedAt: string
}

export interface BotRoleTemplate {
  key: string
  name: string
  roleSummary: string
  systemPrompt: string
  shape: BotShape
  color: string
}

export interface IslandNotice {
  id: number
  kind: 'TASK_DONE' | 'TASK_FAILED' | 'BOT_REPLIED' | 'BOT_CREATED' | 'PLAYBOOK_SAVED' | 'LEVEL_UP' | string
  botId: string
  botName: string
  title: string
  detail: string
  createdAt: string
}

export interface BotWorkspaceSnapshot {
  meta: BotMeta
  sections: BotSection[]
  bots: BotCard[]
  templates: BotRoleTemplate[]
  notices: IslandNotice[]
  pendingApprovals: PendingBotApproval[]
  version: string
}

export interface BotEntry {
  botId: string
  seq: number
  id: string
  requestId: string | null
  kind: 'USER' | 'ASSISTANT' | 'TASK_RESULT' | 'SYSTEM' | 'SKILL_OFFER'
  content: string
  quoteSeq: number | null
  quotePreview: string
  attachmentsJson: string
  suggestionsJson: string
  taskId: string
  runId: string
  modelLabel: string
  fallbackFrom: string
  deleted: boolean
  createdAt: string
}

export interface QueuedMessage {
  id: string
  content: string
  quoteSeq: number | null
  quotePreview: string
  queuedAt: string
}

export interface BotPlaybook {
  id: string
  botId: string
  name: string
  whenToUse: string
  steps: string
  notes: string
  sourceSkillId: string
  useCount: number
  createdAt: string
  updatedAt: string
  revision: number
}

export interface BotOfferedSkill {
  name: string
  description: string
  version: string
  sourceKind: 'GITHUB' | 'LOCAL' | 'UPLOAD' | string
  sourceRepo: string
  sourcePath: string
  sourceRef: string
  fileCount: number
  scripts: string[]
  totalBytes: number
  userInvoked: boolean
  replaces: string
}

export interface BotSkippedSkill {
  folder: string
  reason: string
}

export interface BotSkillOffer {
  offerId: string
  targetBotId: string
  targetBotName: string
  skills: BotOfferedSkill[]
  skipped: BotSkippedSkill[]
  installed: string[] | null
}

export interface BotSkillView {
  id: string
  botId: string
  botName: string
  name: string
  description: string
  version: string
  sourceKind: 'GITHUB' | 'LOCAL' | 'UPLOAD' | string
  sourceRepo: string
  sourcePath: string
  sourceRef: string
  fileCount: number
  scripts: string[]
  totalBytes: number
  userInvoked: boolean
  useCount: number
  lastUsedAt: string | null
  installedAt: string
}

export interface BotSkillFileView {
  path: string
  bytes: number
  script: boolean
}

export interface BotSkillFileContent {
  path: string
  content: string
  binary: boolean
  tooLarge: boolean
  bytes: number
}

export interface BotUsageTotals {
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  calls: number
}

export interface BotUsageRow {
  botId: string
  botName: string
  shape: BotShape
  color: string
  avatarUrl: string
  totals: BotUsageTotals
  chat: BotUsageTotals
  task: BotUsageTotals
  routine: BotUsageTotals
}

export interface BotUsageReport {
  days: number
  totals: BotUsageTotals
  bySource: Record<string, BotUsageTotals>
  bots: BotUsageRow[]
}

export interface BotRoutine {
  id: string
  botId: string
  name: string
  instruction: string
  playbookId: string
  modelProfileId: string
  reasoningEffort: string | null
  scheduleKind: string
  scheduleTime: string
  scheduleWeekday: number
  cron: string
  timezone: string
  enabled: boolean
  nextRunAt: string | null
  lastRunAt: string | null
  lastTaskId: string
  lastStatus: string
  createdAt: string
  revision: number
}

export interface BotFile {
  id: string
  botId: string
  taskId: string
  relativePath: string
  sizeBytes: number
  updatedAt: string
}

export interface BotMiniApp {
  id: string
  botId: string
  name: string
  description: string
  icon: string
  color: string
  version: number
  htmlPath: string
  dataPath: string
  lastTaskId: string
  createdAt: string
  updatedAt: string
}

export interface BotMiniAppVersion {
  id: string
  appId: string
  version: number
  changeSummary: string
  htmlPath: string
  taskId: string
  createdAt: string
}

export interface BotMiniAppBundle {
  app: BotMiniApp
  data: unknown
  html: string
  versions: BotMiniAppVersion[]
  shownVersion: number
}

export interface BotDebugPromptSection {
  heading: string
  text: string
  estimatedTokens: number
}

export interface BotDebugContext {
  model: string
  windowTokens: number
  systemTokens: number
  transcriptTokens: number
  toolsTokens: number
  totalEstimatedTokens: number
  measured: { inputTokens: number; cachedTokens: number; outputTokens: number } | null
  sections: BotDebugPromptSection[]
  transcriptFromSeq: number
  transcriptToSeq: number
  transcriptCounts: Record<string, number>
  compactAbove: number
  keepRecent: number
  transcriptMessages: { seq: number; kind: string; text: string }[]
  tools: { name: string; description: string }[]
}

export interface BotDebugTraceCall {
  id: string
  name: string
  arguments: string
  status: string
  output: string
  outputChars: number
  error: boolean
}

export interface BotDebugTraceStep {
  step: number
  startedAt: string
  endedAt: string | null
  inputTokens: number | null
  cachedTokens: number | null
  outputTokens: number | null
  text: string
  reasoning: string
  calls: BotDebugTraceCall[]
}

export interface BotDebugTrace {
  id: string
  kind: 'CHAT' | 'TASK'
  title: string
  runId: string
  status: string
  model: string
  startedAt: string
  endedAt: string | null
  error: string
  maxSteps: number
  totalInputTokens: number
  totalCachedTokens: number
  totalOutputTokens: number
  steps: BotDebugTraceStep[]
}

export interface BotChange {
  id: string
  botId: string
  entrySeq: number
  toolName: string
  kind: string
  summary: string
  beforeText: string
  afterText: string
  createdAt: string
}

export interface StreamingReply {
  runId: string
  status: string
  output: string
}

export interface BotThreadSnapshot {
  card: BotCard
  entries: BotEntry[]
  streaming: StreamingReply | null
  queued: QueuedMessage[]
  tasks: BotTask[]
  playbooks: BotPlaybook[]
  skills: BotSkillView[]
  routines: BotRoutine[]
  apps: BotMiniApp[]
  files: BotFile[]
  userFacts: BotFact[]
  botFacts: BotFact[]
  pendingApprovals: PendingBotApproval[]
  version: string
}

export interface BotTaskDetail {
  task: BotTask
  sessionSnapshot: Snapshot
}

export interface BotDraft {
  sectionId?: string
  name?: string
  roleSummary?: string
  systemPrompt?: string
  shape?: BotShape
  color?: string
  outfit?: string[]
  hidden?: boolean
  pinned?: boolean
  workspaceId?: string
  chatProfileId?: string
  chatReasoningEffort?: string | null
  taskProfileId?: string
  taskReasoningEffort?: string | null
  externalAgentId?: string
  approvalMode?: ApprovalMode
  revision?: number
}

export interface BotLayoutItem {
  botId: string
  sectionId: string
  pinned: boolean
  ordinal: number
}

export interface BotSkillSummary {
  id: string
  title: string
  description: string
  category: string
  installed: boolean
}

export interface BotExternalAgent {
  id: string
  alias: string
  name: string
  enabled: boolean
}

export interface BotFileContent {
  relativePath: string
  sizeBytes: number
  content: string
  truncated: boolean
}

export interface BotRoutineDraft {
  id?: string
  name: string
  instruction: string
  playbookId?: string
  modelProfileId?: string
  scheduleKind: string
  scheduleTime?: string
  scheduleWeekday?: number
  cron?: string
  timezone?: string
  enabled?: boolean
  revision?: number
}

export interface BotLanSettings {
  enabled: boolean
  port: number
  allowRemoteApproval: boolean
  revision: number
  updatedAt: string
}

export interface BotLanDevice {
  id: string
  name: string
  userAgent: string
  createdAt: string
  lastSeenAt: string
}

export interface BotLanPendingPairing {
  requestId: string
  userAgent: string
  expiresAt: string
}

export interface BotLanStatus {
  settings: BotLanSettings
  running: boolean
  actualPort: number
  urls: string[]
  devices: BotLanDevice[]
  pendingPairing: BotLanPendingPairing | null
}

export const botApi = {
  workspace: () => api<BotWorkspaceSnapshot>('/bots/workspace'),
  onboard: (body: { mainBotName: string; userDisplayName: string; templateKeys: string[] }) =>
    api<BotWorkspaceSnapshot>('/bots/onboard', body),
  createBot: (body: BotDraft) => api<BotCard>('/bots', body),
  updateBot: (id: string, body: BotDraft) => api<BotCard>(`/bots/${id}`, body),
  archiveBot: (id: string) => api<{ archived: boolean }>(`/bots/${id}/archive`, {}),
  createSection: (name: string) => api<BotSection>('/bots/sections', { name }),
  updateSection: (id: string, body: { name?: string; collapsed?: boolean; ordinal?: number }) =>
    api<BotSection>(`/bots/sections/${id}`, body),
  deleteSection: (id: string) => api<{ deleted: boolean }>(`/bots/sections/${id}/delete`, {}),
  updateLayout: (items: BotLayoutItem[]) => api<{ saved: boolean }>('/bots/layout', { items }),
  thread: (id: string) => api<BotThreadSnapshot>(`/bots/${id}/thread`),
  postMessage: (
    id: string,
    body: {
      requestId: string
      content: string
      quoteSeq?: number | null
      attachmentIds?: string[]
      viewingMiniAppId?: string
      fromMiniApp?: boolean
    },
  ) =>
    api<{ queued: boolean; entry: BotEntry | null; queuedMessage: QueuedMessage | null }>(`/bots/${id}/messages`, body),
  uploadAttachment: async (file: File): Promise<UploadedAttachment> => {
    const form = new FormData()
    form.append('file', file)
    const response = await fetch('/api/attachments', { method: 'POST', body: form })
    if (!response.ok) {
      const error = await response.json().catch(() => ({})) as { message?: string }
      throw new Error(error.message || `上传附件失败（${response.status}）`)
    }
    return response.json() as Promise<UploadedAttachment>
  },
  attachmentUrl: (attachmentId: string) => `/api/attachments/${encodeURIComponent(attachmentId)}/content`,
  facts: (botId: string) => api<{ userFacts: BotFact[]; botFacts: BotFact[] }>(`/bots/${botId}/facts`),
  addFact: (botId: string, body: { scope: 'USER' | 'BOT'; content: string }) =>
    api<BotFact>(`/bots/${botId}/facts`, body),
  deleteFact: (factId: string) => api<{ deleted: boolean }>(`/bot-facts/${factId}/delete`, {}),
  approvals: (botId: string) => api<PendingBotApproval[]>(`/bots/${botId}/approvals`),
  approveCommand: (commandId: string) => api<unknown>(`/bot-approvals/${commandId}/approve`, {}),
  rejectCommand: (commandId: string) => api<unknown>(`/bot-approvals/${commandId}/reject`, {}),
  webSearchSettings: () => api<BotWebSearchSettings>('/bots/settings/search'),
  saveWebSearchSettings: (body: {
    provider: string
    endpointUrl: string
    apiKey: string
    clearApiKey: boolean
    fallbackFreeEnabled: boolean
    maxResults: number
    timeoutSeconds: number
    revision: number
  }) => api<BotWebSearchSettings>('/bots/settings/search', body),
  limits: () => api<BotLimits>('/bots/settings/limits'),
  saveLimits: (body: {
    maxConcurrentTasks: number
    taskTimeoutMinutes: number
    commandTimeoutSeconds: number
    maxFactsPerScope: number
    modelSilenceSeconds: number
    revision: number
  }) => api<BotLimits>('/bots/settings/limits', body),
  stop: (id: string) => api<{ stopping: boolean }>(`/bots/${id}/stop`, {}),
  markSeen: (id: string) => api<{ seen: boolean }>(`/bots/${id}/seen`, {}),
  markUnread: (id: string) => api<{ unread: boolean }>(`/bots/${id}/unread`, {}),
  markAllSeen: () => api<{ seenAll: boolean }>('/bots/seen-all', {}),
  searchEntries: (id: string, q: string, limit = 20) =>
    api<BotEntry[]>(`/bots/${encodeURIComponent(id)}/entries/search?q=${encodeURIComponent(q)}&limit=${limit}`),
  usage: (days = 7) => api<BotUsageReport>(`/bots/usage?days=${days}`),
  botSkills: (id: string) => api<BotSkillView[]>(`/bots/${encodeURIComponent(id)}/skills`),
  offerSkillFromSource: (id: string, source: string, targetBotId?: string) =>
    api<BotSkillOffer>(`/bots/${encodeURIComponent(id)}/skills/offer`, {
      source,
      targetBotId: targetBotId || '',
    }),
  uploadSkillArchive: async (id: string, file: File): Promise<BotSkillOffer> => {
    const form = new FormData()
    form.append('file', file)
    const response = await fetch(`/api/bots/${encodeURIComponent(id)}/skills/upload`, {
      method: 'POST',
      body: form,
    })
    if (!response.ok) {
      const error = await response.json().catch(() => ({})) as { message?: string }
      throw new Error(error.message || `上传技能包失败（${response.status}）`)
    }
    return response.json() as Promise<BotSkillOffer>
  },
  answerSkillOffer: (id: string, offerId: string, names: string[]) =>
    api<BotSkillOffer>(`/bots/${encodeURIComponent(id)}/skills/offers/${encodeURIComponent(offerId)}/answer`, {
      names,
    }),
  offerSkillFiles: (offerId: string, skillName: string) =>
    api<BotSkillFileView[]>(`/bot-skill-offers/${encodeURIComponent(offerId)}/${encodeURIComponent(skillName)}/files`),
  offerSkillFile: (offerId: string, skillName: string, path: string) =>
    api<BotSkillFileContent>(
      `/bot-skill-offers/${encodeURIComponent(offerId)}/${encodeURIComponent(skillName)}/file?path=${encodeURIComponent(path)}`,
    ),
  installedSkillFiles: (botId: string, skillName: string) =>
    api<BotSkillFileView[]>(`/bots/${encodeURIComponent(botId)}/skills/${encodeURIComponent(skillName)}/files`),
  installedSkillFile: (botId: string, skillName: string, path: string) =>
    api<BotSkillFileContent>(
      `/bots/${encodeURIComponent(botId)}/skills/${encodeURIComponent(skillName)}/file?path=${encodeURIComponent(path)}`,
    ),
  removeBotSkills: (id: string, names: string[], targetBotId?: string) =>
    api<{ removed: string[] }>(`/bots/${encodeURIComponent(id)}/skills/remove`, {
      names,
      targetBotId: targetBotId || '',
    }),
  uploadAvatar: async (id: string, file: Blob, filename = 'avatar.png'): Promise<BotCard> => {
    const form = new FormData()
    form.append('file', file, filename)
    const response = await fetch(`/api/bots/${encodeURIComponent(id)}/avatar`, { method: 'POST', body: form })
    if (!response.ok) {
      const error = await response.json().catch(() => ({})) as { message?: string }
      throw new Error(error.message || `上传头像失败（${response.status}）`)
    }
    return response.json() as Promise<BotCard>
  },
  deleteAvatar: (id: string) => api<BotCard>(`/bots/${id}/avatar/delete`, {}),
  deleteEntries: (id: string, seqs: number[]) => api<{ deleted: boolean }>(`/bots/${id}/entries/delete`, { seqs }),
  startTask: (
    id: string,
    body: { title: string; instruction: string; playbookId?: string; modelProfileId?: string; miniAppId?: string },
  ) =>
    api<BotTask>(`/bots/${id}/tasks`, body),
  taskDetail: (taskId: string) => api<BotTaskDetail>(`/bot-tasks/${taskId}`),
  cancelTask: (taskId: string) => api<BotTask>(`/bot-tasks/${taskId}/cancel`, {}),
  savePlaybook: (id: string, body: { id?: string; name: string; whenToUse: string; steps: string; notes: string }) =>
    api<BotPlaybook>(`/bots/${id}/playbooks`, body),
  deletePlaybook: (playbookId: string) => api<{ deleted: boolean }>(`/bot-playbooks/${playbookId}/delete`, {}),
  skills: () => api<BotSkillSummary[]>('/bots/skills'),
  externalAgents: () => api<BotExternalAgent[]>('/bots/external-agents'),
  importSkill: (botId: string, skillId: string) => api<BotPlaybook>(`/bots/${botId}/playbooks/import-skill`, { skillId }),
  fileContent: (botId: string, path: string) =>
    api<BotFileContent>(`/bots/${botId}/files/content?path=${encodeURIComponent(path)}`),
  rawFileUrl: (botId: string, path: string) =>
    `/api/bots/${encodeURIComponent(botId)}/files/raw?path=${encodeURIComponent(path)}`,
  debugContext: (botId: string) => api<BotDebugContext>(`/bots/${botId}/debug/context`),
  debugTraces: (botId: string) => api<BotDebugTrace[]>(`/bots/${botId}/debug/traces`),
  debugChanges: (botId: string) => api<BotChange[]>(`/bots/${botId}/debug/changes`),
  routines: (botId: string) => api<BotRoutine[]>(`/bots/${botId}/routines`),
  saveRoutine: (botId: string, body: BotRoutineDraft) => api<BotRoutine>(`/bots/${botId}/routines`, body),
  toggleRoutine: (routineId: string, enabled: boolean) =>
    api<BotRoutine>(`/bot-routines/${routineId}/toggle`, { enabled }),
  runRoutine: (routineId: string) => api<BotTask>(`/bot-routines/${routineId}/run`, {}),
  deleteRoutine: (routineId: string) => api<{ deleted: boolean }>(`/bot-routines/${routineId}/delete`, {}),
  miniApp: (appId: string) => api<BotMiniAppBundle>(`/bot-apps/${appId}`),
  miniAppVersions: (appId: string) => api<BotMiniAppVersion[]>(`/bot-apps/${appId}/versions`),
  miniAppVersion: (appId: string, version: number) => api<BotMiniAppBundle>(`/bot-apps/${appId}/versions/${version}`),
  restoreMiniApp: (appId: string, version: number) => api<BotMiniApp>(`/bot-apps/${appId}/restore`, { version }),
  miniAppExportUrl: (appId: string, version?: number) =>
    `/api/bot-apps/${encodeURIComponent(appId)}/export${version ? `?version=${version}` : ''}`,
  saveMiniAppData: (appId: string, data: unknown) => api<BotMiniApp>(`/bot-apps/${appId}/data`, data),
  deleteMiniApp: (appId: string) => api<{ deleted: boolean }>(`/bot-apps/${appId}/delete`, {}),
  lanStatus: () => api<BotLanStatus>('/bot-lan/status'),
  updateLanSettings: (body: { enabled: boolean; port: number; allowRemoteApproval: boolean; revision: number }) =>
    api<BotLanStatus>('/bot-lan/settings', body),
  confirmLanPairing: (body: { code: string; deviceName?: string }) =>
    api<BotLanDevice>('/bot-lan/pair/confirm', body),
  revokeLanDevice: (deviceId: string) => api<{ revoked: boolean }>(`/bot-lan/devices/${deviceId}/revoke`, {}),
}

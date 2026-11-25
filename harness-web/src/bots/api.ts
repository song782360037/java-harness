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
  activeMentionQuery,
  botStatusLabel,
  botTierLabel,
  deskIdleActivity,
  extractBotApps,
  extractBotMentions,
  formatRoutineSchedule,
  parseSuggestions,
  renderBotMarkdownContent,
  type BotAppMatch,
  type BotExperience,
  type BotMentionMatch,
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
  queuedCount: number
  experience: BotExperience
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
  version: string
}

export interface BotEntry {
  botId: string
  seq: number
  id: string
  requestId: string | null
  kind: 'USER' | 'ASSISTANT' | 'TASK_RESULT' | 'SYSTEM'
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
  version: number
  htmlPath: string
  dataPath: string
  lastTaskId: string
  createdAt: string
  updatedAt: string
}

export interface BotMiniAppBundle {
  app: BotMiniApp
  data: unknown
  html: string
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
  routines: BotRoutine[]
  apps: BotMiniApp[]
  files: BotFile[]
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
  postMessage: (id: string, body: { requestId: string; content: string; quoteSeq?: number | null }) =>
    api<{ queued: boolean; entry: BotEntry | null; queuedMessage: QueuedMessage | null }>(`/bots/${id}/messages`, body),
  stop: (id: string) => api<{ stopping: boolean }>(`/bots/${id}/stop`, {}),
  markSeen: (id: string) => api<{ seen: boolean }>(`/bots/${id}/seen`, {}),
  deleteEntries: (id: string, seqs: number[]) => api<{ deleted: boolean }>(`/bots/${id}/entries/delete`, { seqs }),
  startTask: (id: string, body: { title: string; instruction: string; playbookId?: string; modelProfileId?: string }) =>
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
  routines: (botId: string) => api<BotRoutine[]>(`/bots/${botId}/routines`),
  saveRoutine: (botId: string, body: BotRoutineDraft) => api<BotRoutine>(`/bots/${botId}/routines`, body),
  toggleRoutine: (routineId: string, enabled: boolean) =>
    api<BotRoutine>(`/bot-routines/${routineId}/toggle`, { enabled }),
  runRoutine: (routineId: string) => api<BotTask>(`/bot-routines/${routineId}/run`, {}),
  deleteRoutine: (routineId: string) => api<{ deleted: boolean }>(`/bot-routines/${routineId}/delete`, {}),
  miniApp: (appId: string) => api<BotMiniAppBundle>(`/bot-apps/${appId}`),
  saveMiniAppData: (appId: string, data: unknown) => api<BotMiniApp>(`/bot-apps/${appId}/data`, data),
  deleteMiniApp: (appId: string) => api<{ deleted: boolean }>(`/bot-apps/${appId}/delete`, {}),
  lanStatus: () => api<BotLanStatus>('/bot-lan/status'),
  updateLanSettings: (body: { enabled: boolean; port: number; allowRemoteApproval: boolean; revision: number }) =>
    api<BotLanStatus>('/bot-lan/settings', body),
  confirmLanPairing: (body: { code: string; deviceName?: string }) =>
    api<BotLanDevice>('/bot-lan/pair/confirm', body),
  revokeLanDevice: (deviceId: string) => api<{ revoked: boolean }>(`/bot-lan/devices/${deviceId}/revoke`, {}),
}

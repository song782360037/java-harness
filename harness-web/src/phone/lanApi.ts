import type {
  BotEntry,
  BotFileContent,
  BotLanDevice,
  BotMiniApp,
  BotMiniAppBundle,
  BotMiniAppVersion,
  BotRoutine,
  BotSkillFileContent,
  BotSkillFileView,
  BotSkillView,
  BotTask,
  BotThreadSnapshot,
  BotWorkspaceSnapshot,
  PendingBotApproval,
  QueuedMessage,
  UploadedAttachment,
} from '../bots/api'

export class LanUnauthorizedError extends Error {
  constructor(message = '设备未配对或已被电脑端移除') {
    super(message)
    this.name = 'LanUnauthorizedError'
  }
}

export interface LanMeResponse {
  device: BotLanDevice
  allowRemoteApproval: boolean
}

export interface LanPairRequest {
  requestId: string
  code: string
  expiresAt: string
}

export interface LanPairPoll {
  status: 'PENDING' | 'PAIRED' | 'EXPIRED'
  deviceName?: string
}

async function lanRequest<T>(path: string, body?: unknown): Promise<T> {
  const options: RequestInit = body === undefined
    ? { credentials: 'same-origin' }
    : {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }
  const response = await fetch(`/lan/api${path}`, options)
  if (response.status === 401) {
    throw new LanUnauthorizedError()
  }
  const text = await response.text()
  let parsed: unknown = null
  if (text) {
    try {
      parsed = JSON.parse(text)
    } catch {
      parsed = { message: text }
    }
  }
  if (!response.ok) {
    const message =
      parsed && typeof parsed === 'object' && 'message' in parsed && typeof (parsed as { message?: unknown }).message === 'string'
        ? (parsed as { message: string }).message
        : `请求失败（${response.status}）`
    throw new Error(message)
  }
  return parsed as T
}

export function normalizeLanAvatarUrl(url: string | null | undefined): string {
  if (!url) return ''
  if (url.startsWith('/api/bots/')) return `/lan${url}`
  return url
}

export const lanApi = {
  me: () => lanRequest<LanMeResponse>('/me'),
  requestPair: () => lanRequest<LanPairRequest>('/pair/request', {}),
  pollPair: (requestId: string) => lanRequest<LanPairPoll>(`/pair/poll?requestId=${encodeURIComponent(requestId)}`),
  workspace: () => lanRequest<BotWorkspaceSnapshot>('/bots/workspace'),
  thread: (botId: string) => lanRequest<BotThreadSnapshot>(`/bots/${encodeURIComponent(botId)}/thread`),
  postMessage: (
    botId: string,
    body: {
      requestId: string
      content: string
      quoteSeq?: number | null
      attachmentIds?: string[]
      viewingMiniAppId?: string
      fromMiniApp?: boolean
    },
  ) =>
    lanRequest<{ queued: boolean; entry: BotEntry | null; queuedMessage: QueuedMessage | null }>(
      `/bots/${encodeURIComponent(botId)}/messages`,
      body,
    ),
  stop: (botId: string) => lanRequest<{ stopping: boolean }>(`/bots/${encodeURIComponent(botId)}/stop`, {}),
  markSeen: (botId: string) => lanRequest<{ seen: boolean }>(`/bots/${encodeURIComponent(botId)}/seen`, {}),
  markAllSeen: () => lanRequest<{ seenAll: boolean }>('/bots/seen-all', {}),
  searchEntries: (botId: string, q: string) =>
    lanRequest<BotEntry[]>(`/bots/${encodeURIComponent(botId)}/entries/search?q=${encodeURIComponent(q)}`),
  botSkills: (botId: string) => lanRequest<BotSkillView[]>(`/bots/${encodeURIComponent(botId)}/skills`),
  installedSkillFiles: (botId: string, skillName: string) =>
    lanRequest<BotSkillFileView[]>(`/bots/${encodeURIComponent(botId)}/skills/${encodeURIComponent(skillName)}/files`),
  installedSkillFile: (botId: string, skillName: string, path: string) =>
    lanRequest<BotSkillFileContent>(
      `/bots/${encodeURIComponent(botId)}/skills/${encodeURIComponent(skillName)}/file?path=${encodeURIComponent(path)}`,
    ),
  deleteEntries: (botId: string, seqs: number[]) =>
    lanRequest<{ deleted: boolean }>(`/bots/${encodeURIComponent(botId)}/entries/delete`, { seqs }),
  uploadAttachment: async (file: File): Promise<UploadedAttachment> => {
    const form = new FormData()
    form.append('file', file)
    const response = await fetch('/lan/api/attachments', {
      method: 'POST',
      credentials: 'same-origin',
      body: form,
    })
    if (response.status === 401) throw new LanUnauthorizedError()
    const payload = await response.json().catch(() => ({})) as { message?: string }
    if (!response.ok) {
      throw new Error(payload.message || `上传附件失败（${response.status}）`)
    }
    return payload as UploadedAttachment
  },
  attachmentUrl: (attachmentId: string) => `/lan/api/attachments/${encodeURIComponent(attachmentId)}/content`,
  fileContent: (botId: string, path: string) =>
    lanRequest<BotFileContent>(`/bots/${encodeURIComponent(botId)}/files/content?path=${encodeURIComponent(path)}`),
  rawFileUrl: (botId: string, path: string) =>
    `/lan/api/bots/${encodeURIComponent(botId)}/files/raw?path=${encodeURIComponent(path)}`,
  cancelTask: (taskId: string) => lanRequest<BotTask>(`/bot-tasks/${encodeURIComponent(taskId)}/cancel`, {}),
  toggleRoutine: (routineId: string, enabled: boolean) =>
    lanRequest<BotRoutine>(`/bot-routines/${encodeURIComponent(routineId)}/toggle`, { enabled }),
  runRoutine: (routineId: string) => lanRequest<BotTask>(`/bot-routines/${encodeURIComponent(routineId)}/run`, {}),
  miniApp: (appId: string) => lanRequest<BotMiniAppBundle>(`/bot-apps/${encodeURIComponent(appId)}`),
  miniAppVersions: (appId: string) => lanRequest<BotMiniAppVersion[]>(`/bot-apps/${encodeURIComponent(appId)}/versions`),
  miniAppVersion: (appId: string, version: number) =>
    lanRequest<BotMiniAppBundle>(`/bot-apps/${encodeURIComponent(appId)}/versions/${version}`),
  restoreMiniApp: (appId: string, version: number) =>
    lanRequest<BotMiniApp>(`/bot-apps/${encodeURIComponent(appId)}/restore`, { version }),
  saveMiniAppData: (appId: string, data: unknown) =>
    lanRequest<BotMiniApp>(`/bot-apps/${encodeURIComponent(appId)}/data`, data),
  miniAppExportUrl: (appId: string, version?: number) =>
    `/lan/api/bot-apps/${encodeURIComponent(appId)}/export${version ? `?version=${version}` : ''}`,
  approvals: () => lanRequest<PendingBotApproval[]>('/approvals'),
  approveCommand: (commandId: string) => lanRequest<unknown>(`/approvals/${encodeURIComponent(commandId)}/approve`, {}),
  rejectCommand: (commandId: string) => lanRequest<unknown>(`/approvals/${encodeURIComponent(commandId)}/reject`, {}),
  eventsUrl: () => '/lan/api/bots/events',
}

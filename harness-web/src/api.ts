export type RunStatus = 'QUEUED' | 'RUNNING' | 'CANCELLING' | 'COMPLETED'
  | 'CANCELLED' | 'FAILED' | 'TIMED_OUT' | 'INTERRUPTED'
export interface Session { id: string; title: string; createdAt: string; workspace: string; workspaceId: string; modelProfileId: string; reasoningEffort: string }
export interface Workspace { id: string; name: string; directories: string[]; icon: string; color: string; revision: number }
export const reasoningOptions = [
  { value: '', label: '服务默认', short: '服务默认' }, { value: 'none', label: '关闭 · none', short: '关闭' },
  { value: 'minimal', label: '最低 · minimal', short: '最低' }, { value: 'low', label: '低 · low', short: '低' },
  { value: 'medium', label: '中 · medium', short: '中' }, { value: 'high', label: '高 · high', short: '高' },
  { value: 'xhigh', label: '极高 · xhigh', short: '极高' }, { value: 'max', label: '最高 · max', short: '最高' },
]
export const reasoningDepthOptions = reasoningOptions.filter(option => ['low', 'medium', 'high', 'xhigh', 'max'].includes(option.value))
// 聊天选择限定为五档，旧默认或关闭配置从低档开始。
export const normalizeReasoningDepth = (value: string) => reasoningDepthOptions.some(option => option.value === value) ? value : 'low'
export const effortLabel = (value: string) => reasoningOptions.find(option => option.value === value)?.label || '服务默认'
/** 空间有限的位置只显示档位中文名。 */
export const effortShortLabel = (value: string) => reasoningOptions.find(option => option.value === value)?.short || '服务默认'
export interface ModelProfile {
  id: string; displayName: string; reasoningEffort: string; revision: number; providerId: string; providerName: string
  modelName: string; runTimeoutSeconds: number; contextWindowTokens: number; maxOutputTokens: number; toolTimeoutSeconds: number; maxToolsPerRequest: number; model: ModelInfo
}
export interface ProviderView {
  id: string; revision: number; displayName: string; baseUrl: string; apiKeyConfigured: boolean; apiKeyRequired: boolean; connectTimeoutSeconds: number
}
export interface SettingsView {
  revision: number; baseUrl: string; modelName: string; apiKeyConfigured: boolean; apiKeyRequired: boolean
  connectTimeoutSeconds: number; runTimeoutSeconds: number; contextWindowTokens: number; maxOutputTokens: number; toolTimeoutSeconds: number; maxToolsPerRequest: number
  model: ModelInfo
}
export interface DirectoryListing { path: string; parent: string; directories: { name: string; path: string }[]; truncated: boolean }
export interface ModelInfo { provider: string; model: string; configured: boolean }
export interface WorkspaceInfo { configured: boolean; root: string; tools: string[] }
export interface ToolCall { id: string; name: string; arguments: string }
export interface ToolExecution {
  runId: string; step: number; callId: string; name: string; arguments: string; status: string; output: string
  startedAt: string | null; finishedAt: string | null
}
export interface RunStep { step: number; outputStart: number; reasoning: string; startedAt: string; reasoningAt: string | null; finishedAt: string | null }
export interface FileChange {
  id: string; runId: string; path: string; operation: 'replace' | 'create'; beforeContent: string | null
  afterContent: string; status: string; detail: string; createdAt: string
}
export interface CommandExecution {
  id: string; runId: string; command: string; directory: string; shell: string; purpose: string
  timeoutSeconds: number; status: string; stdout: string; stderr: string; exitCode: number | null; detail: string; createdAt: string
}
export interface ModelRequest {
  instructions: string; finalAnswerOnly: boolean; reasoningEffort: string
  step: number; workspace: string; tools: { name: string }[]
  messages: { role: string; content: string; toolCalls: ToolCall[]; toolCallId: string | null }[]
}
export interface ContextSummary {
  includedTurns: number; droppedTurns: number; excludedTurns: number; characters: number; maxCharacters: number
  tokens?: number; maxTokens?: number
}
export interface Run {
  id: string; sessionId: string; requestId: string; prompt: string; scenario: string
  status: RunStatus; output: string; detail: string; createdAt: string; provider: string; model: string
  context: ContextSummary | null; workspace: string; modelProfileId: string; reasoningEffort: string
}
export interface SessionUsage { inputTokens: number; outputTokens: number; cachedTokens: number; durationMillis: number; lastInputTokens: number }
export interface Snapshot { session: Session; runs: Run[]; lastSeq: number; usage: SessionUsage | null }
export interface Submission { requestId: string; prompt: string; scenario: string; modelProfileId?: string; reasoningEffort?: string }
export interface RunEvent { sessionId: string; seq: number; runId: string | null; type: string }
export const isActive = (run: Run) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(run.status)
export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message) }
}
export async function api<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string }
    throw new ApiError(error.message || `请求失败（${response.status}）`, response.status)
  }
  // 删除类接口返回 204 无内容。
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

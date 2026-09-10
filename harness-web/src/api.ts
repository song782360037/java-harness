export type RunStatus = 'QUEUED' | 'RUNNING' | 'CANCELLING' | 'COMPLETED'
  | 'CANCELLED' | 'FAILED' | 'TIMED_OUT' | 'INTERRUPTED'
export interface Session { id: string; title: string; createdAt: string }
export interface ModelInfo { provider: string; model: string; configured: boolean }
export interface WorkspaceInfo { configured: boolean; root: string; tools: string[] }
export interface ToolCall { id: string; name: string; arguments: string }
export interface ToolExecution { runId: string; step: number; callId: string; name: string; arguments: string; status: string; output: string }
export interface ModelRequest {
  instructions: string; finalAnswerOnly: boolean
  step: number; workspace: string; tools: { name: string }[]
  messages: { role: string; content: string; toolCalls: ToolCall[]; toolCallId: string | null }[]
}
export interface ContextSummary {
  includedTurns: number; droppedTurns: number; excludedTurns: number; characters: number; maxCharacters: number
}
export interface Run {
  id: string; sessionId: string; requestId: string; prompt: string; scenario: string
  status: RunStatus; output: string; detail: string; createdAt: string; provider: string; model: string
  context: ContextSummary | null; workspace: string
}
export interface Snapshot { session: Session; runs: Run[]; lastSeq: number }
export interface Submission { requestId: string; prompt: string; scenario: string }
export interface RunEvent { sessionId: string; seq: number; runId: string; type: string }
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
  return response.json() as Promise<T>
}

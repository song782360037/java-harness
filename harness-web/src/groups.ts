import type { ApprovalMode, RunStatus } from './api'

/** DISCUSS：成员自由发言；TASKS：主持人拆成任务计划后按依赖执行。 */
export type GroupMode = 'DISCUSS' | 'TASKS'
export interface Group {
  id: string; name: string; color: string; workspaceId: string; hostProfileId: string; hostReasoningEffort: string | null
  maxTurns: number; mode: GroupMode; createdAt: string; revision: number
}
export interface GroupMember {
  id: string; groupId: string; sessionId: string; name: string; template: string; color: string; focus: string
  modelProfileId: string; reasoningEffort: string | null; approvalMode: ApprovalMode; ordinal: number; enabled: boolean
}
export interface GroupMessage {
  id: string; seq: number; roundId: string | null; author: 'USER' | 'MEMBER' | 'SYSTEM'; memberId: string | null
  authorName: string; content: string; runId: string | null; createdAt: string
}
export type RoundStatus = 'RUNNING' | 'COMPLETED' | 'STOPPED' | 'SUPERSEDED' | 'FAILED' | 'INTERRUPTED'
export interface GroupRound { id: string; groupId: string; status: RoundStatus; turns: number; detail: string; createdAt: string }
/** 成员运行的展示状态；activity 变化时刷新该运行的审批卡片。 */
export interface GroupRunState { id: string; sessionId: string; status: RunStatus; workspace: string; activity: number }
export type TaskStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED' | 'CANCELLED' | 'INTERRUPTED'
export interface GroupTask {
  id: string; roundId: string; ordinal: number; key: string; title: string; instruction: string; memberId: string
  memberName: string; dependsOn: string[]; status: TaskStatus; attempts: number; runId: string | null; detail: string; createdAt: string
}
export interface GroupSnapshot {
  group: Group; members: GroupMember[]; messages: GroupMessage[]; round: GroupRound | null; tasks: GroupTask[]; busy: boolean
  runs: GroupRunState[]; version: string
}
export interface GroupSummary { group: Group; members: number; busy: boolean }
export interface GroupTemplate { key: string; name: string; color: string; focus: string }
export interface GroupDraft {
  name: string; color: string; workspaceId: string; hostProfileId: string; hostReasoningEffort: string | null
  maxTurns: number; mode: GroupMode; revision: number; templates: string[]
}
export interface MemberDraft {
  name: string; template: string; color: string; focus: string; modelProfileId: string; reasoningEffort: string | null
  approvalMode: ApprovalMode; enabled: boolean
}

/** 新建群聊时默认勾选的模板：覆盖需求、实现与验证的最小班子。 */
export const defaultTemplates = ['project-manager', 'frontend', 'backend', 'qa']

export function emptyGroupDraft(profileId = 'default'): GroupDraft {
  return { name: '', color: '#4b705b', workspaceId: '', hostProfileId: profileId, hostReasoningEffort: null, maxTurns: 12, mode: 'DISCUSS', revision: 0, templates: [...defaultTemplates] }
}

export function groupDraft(group: Group): GroupDraft {
  return { name: group.name, color: group.color, workspaceId: group.workspaceId, hostProfileId: group.hostProfileId,
    hostReasoningEffort: group.hostReasoningEffort, maxTurns: group.maxTurns, mode: group.mode, revision: group.revision, templates: [] }
}

export const modeOptions: { value: GroupMode; label: string; description: string }[] = [
  { value: 'DISCUSS', label: '讨论', description: '主持人安排下一位发言，成员之间可以互相 @，适合讨论方案。' },
  { value: 'TASKS', label: '任务编排', description: '主持人先把需求拆成任务计划，依赖完成后并行执行，失败的任务会重试一次，最后汇总答复。' },
]

const taskLabels: Record<TaskStatus, string> = {
  PENDING: '等待中', RUNNING: '执行中', DONE: '已完成', FAILED: '失败', SKIPPED: '已跳过', CANCELLED: '已取消', INTERRUPTED: '已中断',
}
export const taskLabel = (status: TaskStatus) => taskLabels[status]

/** Plan steps: tasks without dependencies are step 1, every other task follows its latest dependency. */
export function taskSteps(tasks: GroupTask[]): GroupTask[][] {
  const byKey = new Map(tasks.map(task => [task.key, task]))
  const depths = new Map<string, number>()
  const depth = (task: GroupTask, seen: Set<string>): number => {
    const known = depths.get(task.key)
    if (known !== undefined) return known
    // The server rejects cycles; a cycle here still ends instead of recursing forever.
    if (seen.has(task.key)) return 0
    seen.add(task.key)
    const value = Math.max(-1, ...task.dependsOn.map(key => byKey.get(key)).filter(item => item !== undefined).map(item => depth(item, seen))) + 1
    depths.set(task.key, value)
    return value
  }
  const steps: GroupTask[][] = []
  for (const task of tasks) (steps[depth(task, new Set())] ??= []).push(task)
  return steps.filter(step => step !== undefined)
}

export function emptyMemberDraft(profileId = 'default'): MemberDraft {
  return { name: '', template: '', color: '#4b705b', focus: '', modelProfileId: profileId, reasoningEffort: null, approvalMode: 'MANUAL', enabled: true }
}

export function memberDraft(member: GroupMember): MemberDraft {
  const { name, template, color, focus, modelProfileId, reasoningEffort, approvalMode, enabled } = member
  return { name, template, color, focus, modelProfileId, reasoningEffort, approvalMode, enabled }
}

/** The @mention being typed right before the caret, or null when the caret is not inside one. */
export function mentionQuery(text: string, caret: number): { start: number; query: string } | null {
  const match = /(^|[\s(（，,。：:])@([^\s@]*)$/.exec(text.slice(0, caret))
  return match ? { start: caret - match[2].length - 1, query: match[2] } : null
}

/** Replace the mention being typed with the chosen name followed by a space; returns the new text and caret. */
export function insertMention(text: string, caret: number, name: string): { text: string; caret: number } {
  const mention = mentionQuery(text, caret)
  if (!mention) return { text, caret }
  const inserted = `@${name} `
  return { text: text.slice(0, mention.start) + inserted + text.slice(caret), caret: mention.start + inserted.length }
}

/** Enabled members whose name contains the query, prefix matches first. */
export function mentionCandidates(members: GroupMember[], query: string): GroupMember[] {
  const enabled = members.filter(member => member.enabled && member.name.includes(query))
  return [...enabled.filter(member => member.name.startsWith(query)), ...enabled.filter(member => !member.name.startsWith(query))]
}

const roundLabels: Record<RoundStatus, string> = {
  RUNNING: '协作中', COMPLETED: '本轮已结束', STOPPED: '已停止', SUPERSEDED: '已转向新消息', FAILED: '协作中断', INTERRUPTED: '已中断',
}
export const roundLabel = (status: RoundStatus) => roundLabels[status]

/** A finished round worth a notice: failures, stops and limits; a quiet normal end shows nothing. */
export function roundNotice(round: GroupRound | null): { text: string; error: boolean } | null {
  if (!round || round.status === 'RUNNING' || round.status === 'SUPERSEDED') return null
  if (round.status === 'COMPLETED' && !round.detail) return null
  return { text: round.detail ? `${roundLabel(round.status)}：${round.detail}` : roundLabel(round.status), error: ['FAILED', 'INTERRUPTED'].includes(round.status) }
}

const activeStatuses: RunStatus[] = ['QUEUED', 'RUNNING', 'CANCELLING']

/** Members currently speaking, with the run each one is speaking through. */
export function speakingMembers(snapshot: GroupSnapshot): { member: GroupMember; run: GroupRunState }[] {
  return snapshot.runs
    .filter(run => activeStatuses.includes(run.status))
    .flatMap(run => {
      const member = snapshot.members.find(value => value.sessionId === run.sessionId)
      return member ? [{ member, run }] : []
    })
}

/** The avatar character: the first character of the name. */
export const avatarText = (name: string) => [...name][0] ?? '?'

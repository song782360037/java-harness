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
  { value: 'DISCUSS', label: '讨论', description: '主持人安排发言顺序，适合讨论方案。' },
  { value: 'TASKS', label: '任务编排', description: '主持人拆分任务并行执行，最后汇总答复。' },
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

export const activeStatuses: RunStatus[] = ['QUEUED', 'RUNNING', 'CANCELLING']

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

/** Statuses of a run's command and file-change records, read from the existing review endpoints. */
export interface RunApprovals { commands: string[]; changes: string[] }
export type MemberStateKind = 'approval' | 'reviewing' | 'stopping' | 'working' | 'queued' | 'finishing' | 'blocked'
  | 'ready' | 'review' | 'failed' | 'skipped' | 'done' | 'disabled' | 'idle'
/** What the studio shows at a member's desk; attention marks states that wait on the user. */
export interface MemberState {
  member: GroupMember; kind: MemberStateKind; label: string; task: GroupTask | null; run: GroupRunState | null; attention: boolean
}

const stateLabels: Record<MemberStateKind, string> = {
  approval: '等你批准命令', reviewing: '智能审批中', stopping: '正在停止', working: '工作中', queued: '排队中', finishing: '收尾上一轮',
  blocked: '等待上游', ready: '待开工', review: '成果待审核', failed: '未完成', skipped: '已跳过', done: '已完成', disabled: '已停用', idle: '空闲',
}

const runsOf = (snapshot: GroupSnapshot, member: GroupMember) => snapshot.runs.filter(run => run.sessionId === member.sessionId)

/**
 * Runs whose review records the studio needs: every active run and each member's latest run, when they have a workspace.
 * Snapshot runs exclude finished runs without a message (a silent reply), so such a run is not seen here.
 */
export function approvalTargets(snapshot: GroupSnapshot): GroupRunState[] {
  const targets = new Map<string, GroupRunState>()
  for (const member of snapshot.members) {
    const runs = runsOf(snapshot, member)
    for (const run of runs) if (activeStatuses.includes(run.status)) targets.set(run.id, run)
    const latest = runs.at(-1)
    if (latest) targets.set(latest.id, latest)
  }
  return [...targets.values()].filter(run => run.workspace)
}

/**
 * Desk state per member, most urgent first: a run waiting on a command approval beats every other signal, an active
 * run beats task bookkeeping, and a finished run with unreviewed file changes is not reported as done.
 */
export function memberStates(snapshot: GroupSnapshot, approvals: Record<string, RunApprovals>): MemberState[] {
  const byKey = new Map(snapshot.tasks.map(task => [task.key, task]))
  return snapshot.members.map(member => {
    const runs = runsOf(snapshot, member)
    const tasks = snapshot.tasks.filter(task => task.memberId === member.id)
    const state = (kind: MemberStateKind, task: GroupTask | null, run: GroupRunState | null, label = stateLabels[kind]): MemberState =>
      ({ member, kind, label, task, run, attention: kind === 'approval' || kind === 'review' })
    const active = runs.filter(run => activeStatuses.includes(run.status)).at(-1)
    if (active) {
      const task = snapshot.tasks.find(value => value.runId === active.id) ?? null
      const commands = approvals[active.id]?.commands ?? []
      if (commands.includes('PENDING')) return state('approval', task, active)
      if (commands.includes('REVIEWING')) return state('reviewing', task, active)
      if (active.status === 'CANCELLING') return state('stopping', task, active)
      // Tasks list only the latest round; an active run outside it is a superseded round still winding down.
      if (snapshot.group.mode === 'TASKS' && snapshot.tasks.length && !task) return state('finishing', null, active)
      return state(active.status === 'QUEUED' ? 'queued' : 'working', task, active)
    }
    if (!member.enabled) return state('disabled', null, null)
    const pending = snapshot.busy ? tasks.find(task => task.status === 'PENDING') : undefined
    if (pending) return state(pending.dependsOn.every(key => byKey.get(key)?.status === 'DONE') ? 'ready' : 'blocked', pending, null)
    const latest = runs.at(-1) ?? null
    const task = tasks.at(-1) ?? null
    if (latest && approvals[latest.id]?.changes.includes('PENDING')) return state('review', snapshot.tasks.find(value => value.runId === latest.id) ?? task, latest)
    if (task?.status === 'FAILED' || task?.status === 'INTERRUPTED') return state('failed', task, latest, taskLabel(task.status))
    if (task?.status === 'SKIPPED' || task?.status === 'CANCELLED') return state('skipped', task, latest, taskLabel(task.status))
    if (task?.status === 'DONE') return state('done', task, latest)
    return state('idle', null, latest)
  })
}

/** One-line team status under the studio command bar; attention is true when something waits on the user. */
export function studioSummary(snapshot: GroupSnapshot, states: MemberState[]): { text: string; attention: boolean } {
  const count = (...kinds: MemberStateKind[]) => states.filter(state => kinds.includes(state.kind)).length
  const parts = [
    [count('working', 'reviewing', 'finishing', 'queued', 'stopping'), '人工作中'],
    [count('approval'), '个命令待批准'],
    [count('review'), '位成员的修改待审核'],
    [count('failed'), '个任务未完成'],
  ].filter(([value]) => value).map(([value, text]) => `${value} ${text}`)
  const text = parts.join(' · ') || (snapshot.busy ? `主持人正在${hostLabel(snapshot)}` : '团队空闲')
  return { text, attention: count('approval', 'review') > 0 }
}

/** Active member count shared by the studio overview and its activity markers. */
export function isActiveMemberState(state: Pick<MemberState, 'kind'>): boolean {
  return ['working', 'reviewing', 'finishing', 'queued', 'stopping', 'approval'].includes(state.kind)
}

export function activeMemberCount(states: MemberState[]): number {
  return states.filter(isActiveMemberState).length
}

/** What the hidden host is doing, shown at its own desk. */
export function hostLabel(snapshot: GroupSnapshot): string {
  if (!snapshot.busy) return '空闲'
  const speaking = speakingMembers(snapshot).length > 0
  if (snapshot.group.mode === 'TASKS') return speaking ? '跟进任务' : snapshot.tasks.length ? '推进任务' : '拆分任务'
  return speaking ? '等待发言' : '安排发言'
}

/** Direct upstream and downstream task keys of the chosen task. */
export function taskRelations(tasks: GroupTask[], key: string): { upstream: Set<string>; downstream: Set<string> } {
  const task = tasks.find(value => value.key === key)
  return {
    upstream: new Set(task?.dependsOn ?? []),
    downstream: new Set(tasks.filter(value => value.dependsOn.includes(key)).map(value => value.key)),
  }
}

/** Member replies of the latest round in speaking order, for the discussion-mode relay strip. */
export function roundSpeakers(snapshot: GroupSnapshot): GroupMessage[] {
  const round = snapshot.round?.id
  return round ? snapshot.messages.filter(message => message.roundId === round && message.author === 'MEMBER') : []
}

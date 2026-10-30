export type ScheduleKind = 'HOURLY' | 'DAILY' | 'WEEKDAYS' | 'WEEKLY' | 'CUSTOM' | 'MANUAL'
export interface Automation {
  id: string; name: string; prompt: string; workspaceId: string; modelProfileId: string; reasoningEffort: string | null
  scheduleKind: ScheduleKind; scheduleTime: string; scheduleWeekday: number; cron: string
  allowCommands: boolean; enabled: boolean; nextRunAt: string | null; createdAt: string; revision: number
}
export interface AutomationExecution {
  id: string; automationId: string; trigger: 'SCHEDULED' | 'CATCH_UP' | 'MANUAL'; scheduledFor: string | null; startedAt: string
  status: 'STARTED' | 'SKIPPED' | 'FAILED'; detail: string; sessionId: string | null; runId: string | null; runStatus: string | null
}
export type AutomationDraft = Omit<Automation, 'id' | 'nextRunAt' | 'createdAt'>

export const scheduleOptions: { value: ScheduleKind; label: string }[] = [
  { value: 'DAILY', label: '每天' }, { value: 'WEEKDAYS', label: '工作日' }, { value: 'WEEKLY', label: '每周' },
  { value: 'HOURLY', label: '每小时' }, { value: 'CUSTOM', label: '自定义 cron' }, { value: 'MANUAL', label: '仅手动运行' },
]
export const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export function emptyDraft(workspaceId = '', modelProfileId = 'default'): AutomationDraft {
  return { revision: 0, name: '', prompt: '', workspaceId, modelProfileId, reasoningEffort: null, scheduleKind: 'DAILY',
    scheduleTime: '09:00', scheduleWeekday: 1, cron: '', allowCommands: false, enabled: true }
}

/** 列表与详情中展示的调度说明。 */
export function scheduleLabel(value: Pick<Automation, 'scheduleKind' | 'scheduleTime' | 'scheduleWeekday' | 'cron'>) {
  switch (value.scheduleKind) {
    case 'HOURLY': return `每小时第 ${Number(value.scheduleTime.slice(3))} 分`
    case 'DAILY': return `每天 ${value.scheduleTime}`
    case 'WEEKDAYS': return `工作日 ${value.scheduleTime}`
    case 'WEEKLY': return `每${weekdays[value.scheduleWeekday] ?? ''} ${value.scheduleTime}`
    case 'CUSTOM': return `cron ${value.cron}`
    default: return '仅手动运行'
  }
}

export const triggerLabel = (trigger: AutomationExecution['trigger']) => ({ SCHEDULED: '按计划', CATCH_UP: '补跑', MANUAL: '手动' })[trigger]

/** 合并触发状态与运行状态，返回展示文案与语气。 */
export function executionState(value: Pick<AutomationExecution, 'status' | 'runStatus'>): { label: string; tone: 'active' | 'success' | 'muted' | 'danger' } {
  if (value.status === 'SKIPPED') return { label: '已跳过', tone: 'muted' }
  if (value.status === 'FAILED') return { label: '触发失败', tone: 'danger' }
  switch (value.runStatus) {
    case 'QUEUED': case 'RUNNING': case 'CANCELLING': return { label: '运行中', tone: 'active' }
    case 'COMPLETED': return { label: '已完成', tone: 'success' }
    case 'CANCELLED': return { label: '已停止', tone: 'muted' }
    case 'TIMED_OUT': return { label: '已超时', tone: 'danger' }
    case 'FAILED': case 'INTERRUPTED': return { label: '运行失败', tone: 'danger' }
    default: return { label: '会话已删除', tone: 'muted' }
  }
}

export function formatTime(value: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

export interface TaskActivity {
  id: string; sessionId: string; groupId: string; title: string; source: 'SESSION' | 'AUTOMATION' | 'GROUP'
  status: string; attentionKey: string; createdAt: string; updatedAt: string
}
export interface TaskActivitySnapshot {
  active: TaskActivity[]; recent: TaskActivity[]; observedAt?: string
  revision?: number; error?: string; notificationsEnabled?: boolean
}
const labels: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '运行中', CANCELLING: '正在停止', WAITING_APPROVAL: '等待审批', WAITING_ANSWER: '等待回答',
  COMPLETED: '已完成', FAILED: '失败', TIMED_OUT: '超时', INTERRUPTED: '已中断', CANCELLED: '已取消', STOPPED: '已停止', SUPERSEDED: '已被新消息替代',
}
export const taskStatusLabel = (status: string) => labels[status] || status
export const taskSourceLabel = (source: string) => ({ SESSION: '会话', AUTOMATION: '自动化', GROUP: '群聊' })[source] || '任务'
/** 等待操作和错误有独立的视觉层级，取消中不等同于已经停止。 */
export const taskTone = (status: string) => status.startsWith('WAITING_') ? 'waiting'
  : ['FAILED', 'TIMED_OUT', 'INTERRUPTED'].includes(status) ? 'error' : status === 'COMPLETED' ? 'success' : 'neutral'

import { shallowRef } from 'vue'
import { api } from './api'
import type { TaskActivity, TaskActivitySnapshot } from './taskActivityModel'

export const taskActivity = shallowRef<TaskActivitySnapshot>({ active: [], recent: [] })
export const taskActivityError = shallowRef('')
export const taskNotificationsBusy = shallowRef(false)

/** 桌面由主进程持续监测；浏览器只轮询一个轻量接口。 */
export function connectTaskActivity(open: (task: TaskActivity) => void) {
  const bridge = window.harnessDesktop
  let disposed = false, timer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  const accept = (state: TaskActivitySnapshot) => {
    if (disposed || (state.revision ?? 0) < (taskActivity.value.revision ?? 0)) return
    taskActivity.value = state
    taskActivityError.value = state.error || ''
  }
  if (bridge?.getTaskActivity && bridge.onTaskActivity && bridge.onOpenTask) {
    const unsubscribe = bridge.onTaskActivity(accept)
    const stopNavigation = bridge.onOpenTask(task => { if (!disposed) open(task) })
    void bridge.getTaskActivity().then(accept).catch(() => { if (!disposed) taskActivityError.value = '任务状态暂不可用' })
    void bridge.takePendingTask?.().then(task => { if (!disposed && task) open(task) }).catch(() => {})
    return () => { disposed = true; unsubscribe(); stopNavigation() }
  }
  let loading = false
  const poll = async () => {
    if (loading || disposed) return
    loading = true
    clearTimeout(timer)
    controller = new AbortController()
    const timeout = setTimeout(() => controller?.abort(), 10000)
    try { accept(await api<TaskActivitySnapshot>('/runs/activity', undefined, controller.signal)) }
    catch { if (!disposed) taskActivityError.value = '任务状态暂不可用' }
    finally {
      clearTimeout(timeout)
      loading = false
      if (!disposed) timer = setTimeout(poll, document.hidden ? 10000 : 2000)
    }
  }
  const resume = () => { if (!document.hidden) void poll() }
  document.addEventListener('visibilitychange', resume)
  window.addEventListener('online', resume)
  void poll()
  return () => {
    disposed = true; controller?.abort(); clearTimeout(timer)
    document.removeEventListener('visibilitychange', resume)
    window.removeEventListener('online', resume)
  }
}

/** 通知开关由桌面主进程保存，托盘菜单与设置页共享同一值。 */
export async function setTaskNotifications(enabled: boolean) {
  if (!window.harnessDesktop?.setTaskNotifications || taskNotificationsBusy.value) return
  taskNotificationsBusy.value = true
  try {
    const state = await window.harnessDesktop.setTaskNotifications(enabled)
    if ((state.revision ?? 0) >= (taskActivity.value.revision ?? 0)) taskActivity.value = state
    taskActivityError.value = state.error || ''
  } catch { taskActivityError.value = '无法保存通知设置，请重试' }
  finally { taskNotificationsBusy.value = false }
}

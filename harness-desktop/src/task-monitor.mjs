/** 主进程持续读取任务快照；渲染窗口隐藏、刷新或断线都不影响监测。 */
export class TaskMonitor {
  constructor({ read, notify, publish, enabled = true, interval = 2000 }) {
    this.read = read
    this.notify = notify
    this.publish = publish
    this.interval = interval
    this.state = { revision: 0, active: [], recent: [], error: '', notificationsEnabled: enabled }
    this.seen = new Set()
    this.activeIds = new Set()
    this.baseline = null
    this.stopped = true
  }

  /** 偏好立即生效；关闭期间的事件仍记为已见，重新开启不补发旧通知。 */
  setEnabled(enabled) {
    if (typeof enabled !== 'boolean') throw new Error('无效的通知设置')
    this.state = { ...this.state, revision: this.state.revision + 1, notificationsEnabled: enabled }
    this.publish(this.state)
    return this.state
  }

  /** 首次快照只提示当前待处理事项，历史完成和失败保持静默。 */
  accept(snapshot) {
    const first = this.baseline === null
    if (first) this.baseline = Date.parse(snapshot.observedAt)
    for (const task of [...snapshot.active, ...snapshot.recent]) {
      const pending = task.status.startsWith('WAITING_')
      const terminal = ['COMPLETED', 'FAILED', 'TIMED_OUT', 'INTERRUPTED'].includes(task.status)
      const keys = pending ? task.attentionKey.split(',').filter(Boolean).map(id => `attention:${id}`)
        : terminal ? [`${task.id}:${task.status}`] : []
      const unseen = keys.some(key => !this.seen.has(key))
      keys.forEach(key => this.seen.add(key))
      if (unseen && this.state.notificationsEnabled && (pending || terminal && !first &&
        (this.activeIds.has(task.id) || Date.parse(task.updatedAt) >= this.baseline))) this.notify(task)
    }
    // 只保留近期去重信息，运行时间不会使集合无限增长。
    while (this.seen.size > 5000) this.seen.delete(this.seen.values().next().value)
    snapshot.active.forEach(task => this.activeIds.add(task.id))
    const visible = new Set([...snapshot.active, ...snapshot.recent].map(task => task.id))
    this.activeIds = new Set([...this.activeIds].filter(id => visible.has(id)))
    this.state = { ...snapshot, revision: this.state.revision + 1, error: '', notificationsEnabled: this.state.notificationsEnabled }
    this.publish(this.state)
  }

  /** 串行轮询，失败保留上次快照并显示连接异常。 */
  async poll() {
    try {
      const snapshot = await this.read()
      if (!this.stopped) this.accept(snapshot)
    } catch {
      if (!this.stopped) {
        this.state = { ...this.state, revision: this.state.revision + 1, error: '任务状态暂不可用' }
        this.publish(this.state)
      }
    } finally {
      if (!this.stopped) this.timer = setTimeout(() => this.poll(), this.interval)
    }
  }

  start() { if (this.stopped) { this.stopped = false; void this.poll() } }
  stop() { this.stopped = true; clearTimeout(this.timer) }
}

import { Menu, nativeImage, Notification, Tray } from 'electron'
import fs from 'node:fs'
import path from 'node:path'
import { TaskMonitor } from './task-monitor.mjs'

/** 托盘、通知与固定 IPC 的桌面适配层；页面不能提交任意通知或外部链接。 */
export function createTaskActivity({ app, ipcMain, url, getWindow, openTask, showWindow }) {
  const file = path.join(app.getPath('userData'), 'task-notifications.json')
  let enabled = true
  try { enabled = JSON.parse(fs.readFileSync(file, 'utf8')).enabled !== false } catch { /* 首次启动使用默认值。 */ }
  const notifications = new Set()
  const tray = new Tray(trayImage())
  const monitor = new TaskMonitor({
    enabled,
    read: async () => {
      const response = await fetch(`${url}/api/runs/activity`, { signal: AbortSignal.timeout(10_000) })
      if (!response.ok) throw new Error('无法读取任务状态')
      return response.json()
    },
    notify: task => {
      if (!Notification.isSupported()) return
      const labels = { COMPLETED: '任务已完成', FAILED: '任务失败', TIMED_OUT: '任务超时', INTERRUPTED: '任务已中断', WAITING_APPROVAL: '任务需要审批', WAITING_ANSWER: '任务等待回答' }
      const notification = new Notification({ title: labels[task.status], body: task.title, silent: true })
      notifications.add(notification)
      notification.on('click', () => openTask(task))
      notification.on('close', () => notifications.delete(notification))
      notification.on('failed', () => notifications.delete(notification))
      notification.show()
    },
    publish: state => {
      const window = getWindow()
      if (window && !window.isDestroyed()) window.webContents.send('tasks:changed', state)
      const waiting = state.active.filter(task => task.status.startsWith('WAITING_')).length
      tray.setImage(trayImage(state.error ? 'error' : waiting ? 'waiting' : state.active.length ? 'active' : 'idle'))
      tray.setToolTip(state.error ? `Java Harness：${state.error}` : `Java Harness：活动中 ${state.active.length}，待处理 ${waiting}`)
      tray.setContextMenu(Menu.buildFromTemplate([
        { label: '打开 Java Harness', click: showWindow },
        { label: state.error || `活动中 ${state.active.length}，待处理 ${waiting}`, enabled: false },
        { type: 'separator' },
        { label: '桌面任务通知', type: 'checkbox', checked: state.notificationsEnabled,
          click: item => { try { setEnabled(item.checked) } catch { /* 保留原设置，下次可重试。 */ } } },
        { type: 'separator' },
        { label: '退出 Java Harness', click: () => app.quit() },
      ]))
    },
  })
  /** 先持久化再发布，写入失败时不谎报已保存。 */
  function setEnabled(value) {
    if (typeof value !== 'boolean') throw new Error('无效的通知设置')
    fs.writeFileSync(file, JSON.stringify({ enabled: value }))
    return monitor.setEnabled(value)
  }
  for (const [name, operation] of Object.entries({ state: () => monitor.state, notifications: setEnabled })) {
    ipcMain.handle(`tasks:${name}`, (event, value) => {
      const window = getWindow()
      if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame ||
        new URL(event.senderFrame.url).origin !== new URL(url).origin) throw new Error('无效的桌面任务请求')
      return operation(value)
    })
  }
  tray.on('click', showWindow)
  monitor.setEnabled(enabled)
  monitor.start()
  return {
    stop() { monitor.stop(); notifications.forEach(item => item.close()); tray.destroy() },
  }
}

/** 复用应用的括号图形，以状态圆点区分运行、待处理和连接异常。 */
function trayImage(state = 'idle') {
  const size = 32, pixels = Buffer.alloc(size * size * 4)
  const dot = { active: [182, 217, 141], waiting: [80, 170, 245], error: [100, 100, 230] }[state]
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    if ((x < 3 || x > 28) && (y < 3 || y > 28)) continue
    let color = [40, 42, 37]
    if ((x >= 7 && x <= 9 || x >= 22 && x <= 24) && y >= 7 && y <= 24 ||
      (y >= 7 && y <= 9 || y >= 22 && y <= 24) && (x >= 7 && x <= 12 || x >= 19 && x <= 24)) color = [238, 244, 245]
    if (x >= 9 && x <= 22 && y >= 15 && y <= 17) color = [182, 217, 141]
    if (dot && (x - 25) ** 2 + (y - 7) ** 2 <= 25) color = dot
    const offset = (y * size + x) * 4
    pixels[offset] = color[0]; pixels[offset + 1] = color[1]; pixels[offset + 2] = color[2]; pixels[offset + 3] = 255
  }
  return nativeImage.createFromBitmap(pixels, { width: size, height: size, scaleFactor: 1 })
}

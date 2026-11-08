const { contextBridge, ipcRenderer } = require('electron')

// 只公开固定更新操作，不允许页面指定下载地址、安装路径或任意 IPC 通道。
contextBridge.exposeInMainWorld('harnessDesktop', {
  getTaskActivity: () => ipcRenderer.invoke('tasks:state'),
  setTaskNotifications: enabled => ipcRenderer.invoke('tasks:notifications', enabled),
  takePendingTask: () => ipcRenderer.invoke('tasks:ready'),
  onTaskActivity: listener => {
    const handler = (_event, state) => listener(state)
    ipcRenderer.on('tasks:changed', handler)
    return () => ipcRenderer.removeListener('tasks:changed', handler)
  },
  onOpenTask: listener => {
    const handler = (_event, task) => listener(task)
    ipcRenderer.on('tasks:open', handler)
    return () => ipcRenderer.removeListener('tasks:open', handler)
  },
  getUpdateState: () => ipcRenderer.invoke('updates:state'),
  checkForUpdates: () => ipcRenderer.invoke('updates:check'),
  installUpdate: () => ipcRenderer.invoke('updates:install'),
  restartCurrentVersion: () => ipcRenderer.invoke('updates:restart'),
  onUpdateState: listener => {
    const handler = (_event, state) => listener(state)
    ipcRenderer.on('updates:changed', handler)
    return () => ipcRenderer.removeListener('updates:changed', handler)
  },
})

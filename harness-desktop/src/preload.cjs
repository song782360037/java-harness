const { contextBridge, ipcRenderer } = require('electron')

// 只公开固定更新操作，不允许页面指定下载地址、安装路径或任意 IPC 通道。
contextBridge.exposeInMainWorld('harnessDesktop', {
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

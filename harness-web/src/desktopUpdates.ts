import { computed, shallowRef } from 'vue'

export type UpdateState = {
  revision: number; supported: boolean; disabledReason: string; version: string
  phase: 'disabled' | 'idle' | 'checking' | 'current' | 'downloading' | 'ready' | 'installing' | 'error'
  targetVersion: string; releaseNotes: string; percent: number; lastChecked: string; error: string; restartRequired: boolean
}
export type DesktopBridge = {
  getUpdateState(): Promise<UpdateState>
  checkForUpdates(): Promise<UpdateState>
  installUpdate(): Promise<UpdateState>
  restartCurrentVersion(): Promise<void>
  onUpdateState(listener: (state: UpdateState) => void): () => void
}
declare global { interface Window { harnessDesktop?: DesktopBridge } }

export const desktopAvailable = !!window.harnessDesktop
export const updateState = shallowRef<UpdateState>()
export const updateError = shallowRef('')
export const updateBusy = computed(() => ['checking', 'downloading', 'installing'].includes(updateState.value?.phase || ''))
export const updateStatus = computed(() => {
  const value = updateState.value
  if (!value) return '正在读取版本信息…'
  if (value.restartRequired) return '安装未开始，请重启当前版本后重试'
  switch (value.phase) {
    case 'disabled': return value.disabledReason
    case 'checking': return '正在检查更新…'
    case 'current': return '已是最新版本'
    case 'downloading': return `正在下载 ${value.targetVersion} · ${Math.floor(value.percent)}%`
    case 'ready': return `${value.targetVersion} 已就绪，重启后安装`
    case 'installing': return '正在准备更新，请稍候…'
    case 'error': return '更新失败，可重新检查'
    default: return '自动检查并在后台下载正式版本'
  }
})

/** 初始查询不能覆盖先到达的新状态。 */
function acceptState(state: UpdateState) {
  if (!updateState.value || state.revision >= updateState.value.revision) updateState.value = state
}

/** 工作台加载时订阅一次；离开页面时清理订阅。 */
export function connectDesktopUpdates() {
  const bridge = window.harnessDesktop
  if (!bridge) return () => {}
  let disposed = false
  const unsubscribe = bridge.onUpdateState(state => { if (!disposed) acceptState(state) })
  void bridge.getUpdateState().then(state => { if (!disposed) acceptState(state) }).catch(error => {
    if (!disposed) updateError.value = error.message || '无法读取更新状态'
  })
  return () => { disposed = true; unsubscribe() }
}

/** 操作失败只影响更新界面，不打断工作台。 */
export async function runUpdateAction(action: 'check' | 'install' | 'restart') {
  const bridge = window.harnessDesktop
  if (!bridge) return
  updateError.value = ''
  try {
    if (action === 'restart') await bridge.restartCurrentVersion()
    else acceptState(await (action === 'check' ? bridge.checkForUpdates() : bridge.installUpdate()))
  } catch (error) {
    updateError.value = error instanceof Error ? error.message : '更新操作失败，请稍后重试'
  }
}

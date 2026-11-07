/** 管理更新状态与安装顺序，不依赖 Electron，便于验证失败与重试路径。 */
export class DesktopUpdates {
  constructor({ updater, version, supported, disabledReason = '', prepareInstall, onState, logger }) {
    this.updater = updater
    this.prepareInstall = prepareInstall
    this.onState = onState
    this.logger = logger
    this.downloaded = false
    this.checking = null
    this.state = { revision: 0, supported, disabledReason, version, phase: supported ? 'idle' : 'disabled', targetVersion: '', releaseNotes: '', percent: 0, lastChecked: '', error: '', restartRequired: false }
    updater.autoDownload = true
    updater.autoInstallOnAppQuit = false
    updater.allowPrerelease = false
    updater.allowDowngrade = false
    updater.disableWebInstaller = true
    updater.logger = logger
    updater.on('checking-for-update', () => this.publish({ phase: 'checking', error: '' }))
    updater.on('update-not-available', () => this.publish({ phase: 'current', lastChecked: new Date().toISOString() }))
    updater.on('update-available', info => {
      this.downloaded = false
      const releaseNotes = typeof info.releaseNotes === 'string' ? info.releaseNotes : ''
      this.publish({ phase: 'downloading', targetVersion: info.version, releaseNotes, percent: 0, error: '', lastChecked: new Date().toISOString() })
    })
    updater.on('download-progress', progress => this.publish({ percent: Math.max(0, Math.min(100, progress.percent)) }))
    updater.on('update-downloaded', info => {
      this.downloaded = true
      this.publish({ phase: 'ready', targetVersion: info.version, percent: 100, error: '' })
    })
    updater.on('error', error => this.fail(error))
  }

  /** 返回可通过 IPC 传输的状态快照。 */
  snapshot() {
    return { ...this.state }
  }

  /** 合并并发布状态，序号用于防止初始查询覆盖较新的推送。 */
  publish(change) {
    this.state = { ...this.state, ...change, revision: this.state.revision + 1 }
    this.onState(this.snapshot())
  }

  /** 合并并发检查；下载和安装期间不再发起检查。 */
  async check() {
    if (!this.state.supported || this.state.restartRequired || this.downloaded || ['downloading', 'installing'].includes(this.state.phase)) return this.snapshot()
    if (!this.checking) {
      this.checking = Promise.resolve().then(() => this.updater.checkForUpdates()).then(result => result?.downloadPromise).catch(error => this.fail(error)).finally(() => { this.checking = null })
    }
    await this.checking
    return this.snapshot()
  }

  /** 只有后端正常关闭且备份成功后才允许启动安装器。 */
  async install() {
    if (!this.state.supported || !this.downloaded || this.state.phase === 'installing' || this.state.restartRequired) return this.snapshot()
    this.publish({ phase: 'installing', error: '' })
    try {
      await this.prepareInstall(this.state.targetVersion)
      this.updater.quitAndInstall(true, true)
    } catch (error) {
      this.fail(error)
    }
    return this.snapshot()
  }

  /** 安装准备失败仍保留已下载包；关闭后端后的失败要求重启当前版本。 */
  fail(error) {
    this.logger.error(error)
    this.publish({ phase: this.downloaded ? 'ready' : 'error', error: error.message || '更新失败，请稍后重试', restartRequired: this.state.restartRequired || !!error.restartRequired || this.state.phase === 'installing' && !error.beforeShutdown })
  }

  /** 工作台就绪后延迟检查，随后每六小时检查一次。 */
  start() {
    if (!this.state.supported) return
    this.initialTimer = setTimeout(() => void this.check(), 20_000)
    this.interval = setInterval(() => void this.check(), 6 * 60 * 60 * 1000)
    this.initialTimer.unref?.()
    this.interval.unref?.()
  }

  /** 退出时停止周期检查。 */
  stop() {
    clearTimeout(this.initialTimer)
    clearInterval(this.interval)
  }
}

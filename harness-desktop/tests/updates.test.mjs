import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { DesktopUpdates } from '../src/updates.mjs'

function setup(options = {}) {
  const updater = new EventEmitter()
  const events = []
  updater.checkForUpdates = async () => { events.push('check'); updater.emit('update-not-available', { version: '1.0.0' }) }
  updater.quitAndInstall = (...args) => events.push(['install', ...args])
  const controller = new DesktopUpdates({ updater, version: '1.0.0', supported: true, prepareInstall: async () => events.push('prepared'), onState: () => {}, logger: { error() {}, info() {}, warn() {} }, ...options })
  return { updater, events, controller }
}

test('background policy preserves explicit installation and stable versions', () => {
  const { updater } = setup()
  assert.equal(updater.autoDownload, true)
  assert.equal(updater.autoInstallOnAppQuit, false)
  assert.equal(updater.allowPrerelease, false)
  assert.equal(updater.allowDowngrade, false)
  assert.equal(updater.disableWebInstaller, true)
})

test('disabled builds never check or install', async () => {
  const { controller, updater, events } = setup({ supported: false })
  await controller.check()
  updater.emit('update-downloaded', { version: '1.1.0' })
  await controller.install()
  assert.deepEqual(events, [])
})

test('concurrent checks share one request and handle download rejection', async () => {
  const { controller, updater, events } = setup()
  let complete
  updater.checkForUpdates = async () => {
    events.push('check')
    updater.emit('update-available', { version: '1.1.0', releaseNotes: '<script>untrusted</script>' })
    return { downloadPromise: new Promise((_resolve, reject) => { complete = reject }) }
  }
  const first = controller.check()
  const second = controller.check()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(events, ['check'])
  assert.equal(controller.snapshot().phase, 'downloading')
  complete(new Error('网络已断开'))
  await Promise.all([first, second])
  assert.equal(controller.snapshot().phase, 'error')
  assert.equal(controller.snapshot().error, '网络已断开')
  assert.equal(controller.snapshot().restartRequired, false)
})

test('installer waits for shutdown and backup, ignores repeated clicks', async () => {
  let prepared
  const { controller, updater, events } = setup({ prepareInstall: () => new Promise(resolve => { prepared = resolve }) })
  await controller.install()
  assert.deepEqual(events, [])
  updater.emit('update-available', { version: '1.1.0' })
  updater.emit('download-progress', { percent: 45.5 })
  assert.equal(controller.snapshot().percent, 45.5)
  updater.emit('update-downloaded', { version: '1.1.0' })
  const installing = controller.install()
  await controller.install()
  await controller.check()
  assert.deepEqual(events, [])
  prepared()
  await installing
  assert.deepEqual(events, [['install', true, true]])
})

test('busy runtime leaves update retryable without requiring restart', async () => {
  let busy = true
  const { controller, updater, events } = setup({ prepareInstall: async () => {
    if (busy) throw Object.assign(new Error('任务仍在运行'), { beforeShutdown: true })
  } })
  updater.emit('update-downloaded', { version: '1.1.0' })
  await controller.install()
  assert.equal(controller.snapshot().phase, 'ready')
  assert.equal(controller.snapshot().restartRequired, false)
  assert.deepEqual(events, [])
  busy = false
  await controller.install()
  assert.deepEqual(events, [['install', true, true]])
})

test('shutdown or backup failure blocks installation until current version restarts', async () => {
  const { controller, updater, events } = setup({ prepareInstall: async () => { throw new Error('备份失败') } })
  updater.emit('update-downloaded', { version: '1.1.0' })
  await controller.install()
  await controller.install()
  await controller.check()
  assert.equal(controller.snapshot().restartRequired, true)
  assert.deepEqual(events, [])
})

test('invalid signature never creates an installable update', async () => {
  const { controller, updater, events } = setup()
  updater.emit('update-available', { version: '1.1.0' })
  updater.emit('error', new Error('ERR_UPDATER_INVALID_SIGNATURE'))
  await controller.install()
  assert.equal(controller.snapshot().phase, 'error')
  assert.deepEqual(events, [])
})

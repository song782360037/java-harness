import { test } from 'node:test'
import assert from 'node:assert/strict'

let bootCount = 0
const state = (revision, phase = 'idle') => ({ revision, phase, version: '1.0.0', targetVersion: '1.1.0', supported: true, disabledReason: '', releaseNotes: '', percent: 0, lastChecked: '', error: '', restartRequired: false })

test('browser mode has no desktop operations', async () => {
  globalThis.window = {}
  const app = await import(`../src/desktopUpdates.ts?boot=${++bootCount}`)
  assert.equal(app.desktopAvailable, false)
  app.connectDesktopUpdates()()
  await app.runUpdateAction('check')
  assert.equal(app.updateState.value, undefined)
})

test('live update state wins over delayed initial snapshot and disconnect cleans up', async () => {
  let initial, listener, unsubscribed = false
  globalThis.window = { harnessDesktop: {
    getUpdateState: () => new Promise(resolve => { initial = resolve }),
    onUpdateState: callback => { listener = callback; return () => { unsubscribed = true } },
    installUpdate: async () => state(3, 'installing'),
    checkForUpdates: async () => { throw new Error('IPC unavailable') },
    restartCurrentVersion: async () => {},
  } }
  const app = await import(`../src/desktopUpdates.ts?boot=${++bootCount}`)
  const disconnect = app.connectDesktopUpdates()
  listener(state(2, 'ready'))
  initial(state(1))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(app.updateState.value.phase, 'ready')
  await app.runUpdateAction('check')
  assert.equal(app.updateError.value, 'IPC unavailable')
  await app.runUpdateAction('install')
  assert.equal(app.updateState.value.phase, 'installing')
  assert.equal(app.updateBusy.value, true)
  disconnect()
  listener(state(4, 'ready'))
  assert.equal(app.updateState.value.phase, 'installing')
  assert.equal(unsubscribed, true)
})

import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const source = readFileSync(new URL('../public/theme.js', import.meta.url), 'utf8')
function boot({ saved, dark = false, blocked = false, writesBlocked = false } = {}) {
  let stored = saved ?? null
  const mediaListeners = new Map()
  const windowListeners = new Map()
  const storage = {
    getItem: () => stored,
    setItem: (key, value) => { if (writesBlocked) throw new Error('Read only'); stored = value },
  }
  const system = { matches: dark, addEventListener: (type, listener) => mediaListeners.set(type, listener) }
  const root = { dataset: {}, style: {} }
  const window = {
    get localStorage() { if (blocked) throw new Error('Unavailable'); return storage },
    matchMedia: () => system,
    addEventListener: (type, listener) => windowListeners.set(type, listener),
  }
  runInNewContext(source, { window, document: { documentElement: root } })
  return {
    theme: window.harnessTheme, root, stored: () => stored,
    systemChange(value) { system.matches = value; mediaListeners.get('change')() },
    storageChange(value, key = 'harness.theme', area = storage) { windowListeners.get('storage')({ key, newValue: value, storageArea: area }) },
  }
}
test('default follows the system immediately and tracks later changes', () => {
  const app = boot({ dark: true })
  assert.equal(app.root.dataset.theme, 'dark')
  assert.equal(app.root.style.colorScheme, 'dark')
  app.systemChange(false)
  assert.equal(app.root.dataset.theme, 'light')
  assert.equal(app.theme.preference, 'system')
})
test('explicit choice persists, survives reopening and ignores system changes', () => {
  const app = boot()
  app.theme.setPreference('dark')
  app.systemChange(false)
  assert.equal(app.root.dataset.theme, 'dark')
  assert.equal(app.stored(), 'dark')
  const reopened = boot({ saved: app.stored() })
  assert.equal(reopened.root.dataset.theme, 'dark')
  app.theme.setPreference('light')
  app.systemChange(true)
  assert.equal(app.root.dataset.theme, 'light')
})
test('switching back to system uses the latest OS preference', () => {
  const app = boot({ saved: 'light', dark: true })
  app.theme.setPreference('system')
  assert.equal(app.root.dataset.theme, 'dark')
  assert.equal(app.stored(), 'system')
})
test('other tabs synchronize theme changes, including removal and storage clear', () => {
  const app = boot({ saved: 'light', dark: true })
  app.storageChange('dark')
  assert.equal(app.theme.preference, 'dark')
  app.storageChange(null)
  assert.equal(app.theme.preference, 'system')
  app.storageChange('light')
  app.storageChange(null, null)
  assert.equal(app.root.dataset.theme, 'dark')
  app.storageChange('light', 'unrelated')
  app.storageChange('light', 'harness.theme', {})
  assert.equal(app.theme.preference, 'system')
})
test('invalid stored values fall back to system safely', () => {
  const app = boot({ saved: 'invalid', dark: true })
  assert.equal(app.theme.preference, 'system')
  assert.equal(app.root.dataset.theme, 'dark')
})
test('storage failures still apply the choice and report that it cannot be saved', () => {
  for (const options of [{ blocked: true }, { writesBlocked: true }]) {
    const app = boot(options)
    app.theme.setPreference('dark')
    assert.equal(app.root.dataset.theme, 'dark')
    assert.equal(app.theme.persistent, false)
  }
})
test('subscribers receive effective theme changes and can unsubscribe', () => {
  const app = boot()
  const changes = []
  const stop = app.theme.subscribe(() => changes.push(app.theme.resolved))
  app.theme.setPreference('dark')
  app.storageChange('light')
  stop()
  app.theme.setPreference('system')
  assert.deepEqual(changes, ['dark', 'light'])
})

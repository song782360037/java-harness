import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const source = readFileSync(new URL('../public/theme.js', import.meta.url), 'utf8')
function boot({ saved, blocked = false, writesBlocked = false } = {}) {
  let stored = saved ?? null
  const windowListeners = new Map()
  const storage = {
    getItem: () => stored,
    setItem: (key, value) => { if (writesBlocked) throw new Error('Read only'); stored = value },
  }
  const root = { dataset: {}, style: {} }
  const window = {
    get localStorage() { if (blocked) throw new Error('Unavailable'); return storage },
    addEventListener: (type, listener) => windowListeners.set(type, listener),
  }
  runInNewContext(source, { window, document: { documentElement: root } })
  return {
    theme: window.harnessTheme, root, stored: () => stored,
    storageChange(value, key = 'harness.theme', area = storage) { windowListeners.get('storage')({ key, newValue: value, storageArea: area }) },
  }
}
test('default falls back to light theme cleanly', () => {
  const app = boot()
  assert.equal(app.theme.preference, 'light')
  assert.equal(app.theme.resolved, 'light')
  assert.equal(app.theme.colorScheme, 'light')
  assert.equal(app.root.dataset.theme, 'light')
  assert.equal(app.root.dataset.colorScheme, 'light')
  assert.equal(app.root.style.colorScheme, 'light')
})
test('explicit choice persists and survives reopening', () => {
  const app = boot()
  app.theme.setPreference('dark')
  assert.equal(app.root.dataset.theme, 'dark')
  assert.equal(app.stored(), 'dark')
  const reopened = boot({ saved: app.stored() })
  assert.equal(reopened.root.dataset.theme, 'dark')
  assert.equal(reopened.root.dataset.colorScheme, 'dark')
  app.theme.setPreference('pure')
  assert.equal(app.root.dataset.theme, 'pure')
  assert.equal(app.root.dataset.colorScheme, 'light')
})
test('other tabs synchronize theme changes, including removal and storage clear', () => {
  const app = boot({ saved: 'pure' })
  app.storageChange('dark')
  assert.equal(app.theme.preference, 'dark')
  app.storageChange(null)
  assert.equal(app.theme.preference, 'light')
  app.storageChange('midnight')
  app.storageChange(null, null)
  assert.equal(app.root.dataset.theme, 'light')
  app.storageChange('dark', 'unrelated')
  app.storageChange('dark', 'harness.theme', {})
  assert.equal(app.theme.preference, 'light')
})
test('legacy system or invalid stored values fall back to light safely', () => {
  for (const invalid of ['system', 'invalid', 'toString']) {
    const app = boot({ saved: invalid })
    assert.equal(app.theme.preference, 'light')
    assert.equal(app.root.dataset.theme, 'light')
    assert.equal(app.root.dataset.colorScheme, 'light')
  }
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
  app.storageChange('pure')
  stop()
  app.theme.setPreference('midnight')
  assert.deepEqual(changes, ['dark', 'pure'])
})
test('named light and dark themes persist and map to the right color scheme', () => {
  const expected = [
    ['pure', 'light'],
    ['light', 'light'],
    ['paper', 'light'],
    ['ocean', 'light'],
    ['sakura', 'light'],
    ['dark', 'dark'],
    ['midnight', 'dark'],
    ['espresso', 'dark'],
    ['nebula', 'dark'],
  ]
  const app = boot()
  for (const [name, scheme] of expected) {
    app.theme.setPreference(name)
    assert.equal(app.theme.preference, name)
    assert.equal(app.theme.resolved, name)
    assert.equal(app.theme.colorScheme, scheme)
    assert.equal(app.root.dataset.theme, name)
    assert.equal(app.root.dataset.colorScheme, scheme)
    assert.equal(app.root.style.colorScheme, scheme)
    const reopened = boot({ saved: app.stored() })
    assert.equal(reopened.theme.resolved, name)
    assert.equal(reopened.theme.colorScheme, scheme)
    assert.equal(reopened.root.dataset.colorScheme, scheme)
  }
})

import { test } from 'node:test'
import assert from 'node:assert/strict'

let bootCount = 0
async function boot({ saved = null, blocked = false, writesBlocked = false } = {}) {
  let stored = saved
  const listeners = new Map()
  const storage = {
    getItem: () => stored,
    setItem: (key, value) => { if (writesBlocked) throw new Error('Read only'); stored = value },
  }
  globalThis.window = {
    get localStorage() { if (blocked) throw new Error('Unavailable'); return storage },
    addEventListener: (type, listener) => listeners.set(type, listener),
  }
  const app = await import(`../src/preferences.ts?boot=${++bootCount}`)
  return {
    ...app, stored: () => stored,
    storageChange(value, key = 'harness.preferences', area = storage) { listeners.get('storage')({ key, newValue: value, storageArea: area }) },
  }
}
const defaults = { sendShortcut: 'modifier-enter', chatFontSize: 'standard', autoScroll: true }

test('defaults preserve existing behavior and saved choices survive reopening', async () => {
  const app = await boot()
  assert.deepEqual(app.preferences.value, defaults)
  const selected = { sendShortcut: 'enter', chatFontSize: 'large', autoScroll: false }
  app.updatePreferences(selected)
  assert.deepEqual(app.preferences.value, selected)
  const reopened = await boot({ saved: app.stored() })
  assert.deepEqual(reopened.preferences.value, selected)
  reopened.updatePreferences({ chatFontSize: 'small' })
  assert.deepEqual(reopened.preferences.value, { ...selected, chatFontSize: 'small' })
})
test('invalid and partial persisted values use safe per-field defaults', async () => {
  for (const saved of ['invalid json', 'null', 'false', '[]', '{"sendShortcut":"unknown","chatFontSize":99,"autoScroll":"false"}']) {
    assert.deepEqual((await boot({ saved })).preferences.value, defaults)
  }
  const app = await boot({ saved: '{"autoScroll":false}' })
  assert.deepEqual(app.preferences.value, { ...defaults, autoScroll: false })
})
test('storage failures still apply changes and report that they are not saved', async () => {
  for (const options of [{ blocked: true }, { writesBlocked: true }]) {
    const app = await boot(options)
    app.updatePreferences({ sendShortcut: 'enter', autoScroll: false })
    assert.equal(app.preferences.value.sendShortcut, 'enter')
    assert.equal(app.preferences.value.autoScroll, false)
    assert.equal(app.preferencesPersistent.value, false)
  }
})
test('other tabs synchronize changes and storage removal restores defaults', async () => {
  const app = await boot()
  app.storageChange('{"sendShortcut":"enter","autoScroll":false}')
  assert.deepEqual(app.preferences.value, { ...defaults, sendShortcut: 'enter', autoScroll: false })
  app.storageChange('{}', 'unrelated')
  app.storageChange('{}', 'harness.preferences', {})
  assert.equal(app.preferences.value.sendShortcut, 'enter')
  app.storageChange(null)
  assert.deepEqual(app.preferences.value, defaults)
  app.storageChange('{"chatFontSize":"large"}')
  app.storageChange(null, null)
  assert.deepEqual(app.preferences.value, defaults)
  app.storageChange('invalid json')
  assert.deepEqual(app.preferences.value, defaults)
})
test('keyboard shortcuts respect newline, IME confirmation and key repeat', async () => {
  const { shouldSendPrompt } = await boot()
  const enter = { key: 'Enter', keyCode: 13, isComposing: false, repeat: false, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false }
  assert.equal(shouldSendPrompt(enter, 'enter'), true)
  assert.equal(shouldSendPrompt(enter, 'modifier-enter'), false)
  for (const modifier of ['ctrlKey', 'metaKey']) {
    assert.equal(shouldSendPrompt({ ...enter, [modifier]: true }, 'modifier-enter'), true)
    assert.equal(shouldSendPrompt({ ...enter, [modifier]: true }, 'enter'), false)
  }
  for (const patch of [{ shiftKey: true }, { altKey: true }, { isComposing: true }, { keyCode: 229 }, { repeat: true }, { key: 'Escape' }]) {
    assert.equal(shouldSendPrompt({ ...enter, ...patch }, 'enter'), false)
    assert.equal(shouldSendPrompt({ ...enter, ctrlKey: true, ...patch }, 'modifier-enter'), false)
  }
})

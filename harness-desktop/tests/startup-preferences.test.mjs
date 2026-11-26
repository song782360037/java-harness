import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {
  createStartupPreferencesStore,
  normalizeStartupPreferences,
  resolveDesktopSplashMode,
} from '../src/startup-preferences.mjs'

function tempDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'harness-startup-prefs-'))
}

test('first launch defaults to enabled orbital animation and persists updates', () => {
  const dir = tempDir()
  try {
    const store = createStartupPreferencesStore(dir)
    assert.deepEqual(store.snapshot(), { enabled: true, style: 'orbital' })

    const updated = store.save({ style: 'prism' })
    assert.deepEqual(updated, { enabled: true, style: 'prism' })

    const disabled = store.save({ enabled: false })
    assert.deepEqual(disabled, { enabled: false, style: 'prism' })

    const reopened = createStartupPreferencesStore(dir)
    assert.deepEqual(reopened.snapshot(), { enabled: false, style: 'prism' })
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('corrupted or partial config files fall back cleanly per field', () => {
  const dir = tempDir()
  try {
    const file = path.join(dir, 'startup-animation.json')
    fs.writeFileSync(file, '{broken json', 'utf8')
    assert.deepEqual(createStartupPreferencesStore(dir).snapshot(), { enabled: true, style: 'orbital' })

    fs.writeFileSync(file, JSON.stringify({ enabled: false, style: 'invalid' }), 'utf8')
    assert.deepEqual(createStartupPreferencesStore(dir).snapshot(), { enabled: false, style: 'orbital' })

    assert.deepEqual(normalizeStartupPreferences({ style: 'terminal' }), { enabled: true, style: 'terminal' })
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('invalid updates throw without overwriting saved preferences', () => {
  const dir = tempDir()
  try {
    const store = createStartupPreferencesStore(dir)
    store.save({ enabled: true, style: 'terminal' })

    assert.throws(() => store.save(null))
    assert.throws(() => store.save({ enabled: 'yes' }))
    assert.throws(() => store.save({ style: 'matrix' }))
    assert.deepEqual(store.snapshot(), { enabled: true, style: 'terminal' })
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test('desktop splash mode chooses none, full, or handoff based on enabled state and boot duration', () => {
  assert.equal(resolveDesktopSplashMode(false, 0), 'none')
  assert.equal(resolveDesktopSplashMode(false, 4000), 'none')
  assert.equal(resolveDesktopSplashMode(true, 120), 'full')
  assert.equal(resolveDesktopSplashMode(true, 899), 'full')
  assert.equal(resolveDesktopSplashMode(true, 900), 'handoff')
  assert.equal(resolveDesktopSplashMode(true, 3200), 'handoff')
})

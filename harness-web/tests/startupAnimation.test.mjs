import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

let bootCount = 0

async function boot({
  saved = null,
  reducedMotion = false,
  desktopBootstrap = undefined,
  setStartupAnimation = undefined,
} = {}) {
  let stored = saved
  const storage = {
    getItem: () => stored,
    setItem: (_key, value) => { stored = value },
  }
  const desktop = desktopBootstrap !== undefined || setStartupAnimation
    ? {
        getStartupBootstrap: () => desktopBootstrap ?? null,
        setStartupAnimation,
      }
    : undefined

  globalThis.window = {
    localStorage: storage,
    addEventListener: () => {},
    matchMedia: () => ({ matches: reducedMotion }),
    harnessDesktop: desktop,
  }

  const seq = ++bootCount
  const prefsUrl = new URL(`../src/preferences.ts?boot=${seq}`, import.meta.url).href
  const source = readFileSync(new URL('../src/startupAnimation.ts', import.meta.url), 'utf8')
    .replace("from 'vue'", `from '${import.meta.resolve('vue')}'`)
    .replace("from './preferences'", `from '${prefsUrl}'`)
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const mod = await import(`data:text/javascript;base64,${Buffer.from(output + `\n// boot ${seq}`).toString('base64')}`)
  return { ...mod, stored: () => stored }
}

test('first launch in browser enables full orbital animation by default', async () => {
  const app = await boot()
  assert.equal(app.startupAnimationEnabled.value, true)
  assert.equal(app.startupAnimationStyle.value, 'orbital')
  assert.equal(app.startupSplash.value.mode, 'full')
  assert.equal(app.startupSplash.value.style, 'orbital')

  app.finishStartupSplash()
  assert.equal(app.startupSplash.value.mode, 'none')
})

test('resolveInitialSplash handles disabled state, reduced motion, and desktop handoff cleanly', async () => {
  const { resolveInitialSplash } = await boot()
  assert.deepEqual(
    resolveInitialSplash({ webEnabled: true, webStyle: 'orbital', reducedMotion: false }),
    { enabled: true, style: 'orbital', mode: 'full' },
  )
  assert.deepEqual(
    resolveInitialSplash({ webEnabled: false, webStyle: 'prism', reducedMotion: false }),
    { enabled: false, style: 'prism', mode: 'none' },
  )
  assert.deepEqual(
    resolveInitialSplash({ webEnabled: true, webStyle: 'terminal', reducedMotion: true }),
    { enabled: true, style: 'terminal', mode: 'none' },
  )
  assert.deepEqual(
    resolveInitialSplash({
      desktopBootstrap: { enabled: true, style: 'prism', mode: 'handoff' },
      webEnabled: false,
      webStyle: 'orbital',
      reducedMotion: false,
    }),
    { enabled: true, style: 'prism', mode: 'handoff' },
  )
  assert.deepEqual(
    resolveInitialSplash({
      desktopBootstrap: { enabled: false, style: 'terminal', mode: 'handoff' },
      webEnabled: true,
      webStyle: 'orbital',
      reducedMotion: false,
    }),
    { enabled: false, style: 'terminal', mode: 'none' },
  )
})

test('desktop bootstrap syncs persisted preferences and preview replays any style on demand', async () => {
  const calls = []
  const app = await boot({
    desktopBootstrap: { enabled: true, style: 'prism', mode: 'handoff' },
    setStartupAnimation: async patch => {
      calls.push(patch)
      return { enabled: patch.enabled ?? true, style: patch.style ?? 'prism' }
    },
  })
  assert.equal(app.startupSplash.value.mode, 'handoff')
  assert.equal(app.startupSplash.value.style, 'prism')
  assert.equal(app.startupAnimationEnabled.value, true)
  assert.equal(app.startupAnimationStyle.value, 'prism')

  await app.setStartupAnimationStyle('terminal')
  assert.equal(app.startupAnimationStyle.value, 'terminal')
  assert.deepEqual(calls.at(-1), { style: 'terminal' })

  const beforeRunId = app.startupSplash.value.runId
  app.previewStartupAnimation()
  assert.equal(app.startupSplash.value.mode, 'preview')
  assert.equal(app.startupSplash.value.style, 'terminal')
  assert.equal(app.startupSplash.value.runId, beforeRunId + 1)

  await app.setStartupAnimationEnabled(false)
  assert.equal(app.startupAnimationEnabled.value, false)
  assert.deepEqual(calls.at(-1), { enabled: false })
})

test('failed desktop persistence rolls back preference and exposes error message', async () => {
  const app = await boot({
    desktopBootstrap: { enabled: true, style: 'orbital', mode: 'none' },
    setStartupAnimation: async () => { throw new Error('disk full') },
  })
  await app.setStartupAnimationEnabled(false)
  assert.equal(app.startupAnimationEnabled.value, true)
  assert.ok(app.startupAnimationError.value.length > 0)
})

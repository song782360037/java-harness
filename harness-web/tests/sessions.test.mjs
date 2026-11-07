import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

// 运行真实组件的状态逻辑；子组件和浏览器事件不参与这些会话生命周期测试。
function boot() {
  const stored = new Map()
  globalThis.localStorage = { getItem: key => stored.get(key) ?? null, setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) }
  globalThis.window = { innerWidth: 1200, matchMedia: () => ({ matches: false }) }
  globalThis.document = { documentElement: { style: { setProperty() {} } } }
  globalThis.EventSource = class { close() {} addEventListener() {} }
  const calls = [], saved = new Map(), runs = new Map()
  let rejectRun = false
  const request = async (path, body) => {
    calls.push({ path, body })
    if (path === '/sessions' && body) {
      const session = { id: `session-${saved.size + 1}`, ...body, workspace: body.workspaceId ? 'E:/project' : '', modelProfileId: '', reasoningEffort: '', approvalMode: 'MANUAL' }
      saved.set(session.id, session)
      return session
    }
    if (path === '/sessions') return [...saved.values()]
    if (path === '/workspaces' && body) return { ...state.workspaces.value[0], ...body }
    if (path === '/workspaces') return state.workspaces.value
    if (path.endsWith('/attachments')) return []
    if (path.endsWith('/runs')) {
      if (rejectRun) throw new Error('network unavailable')
      const id = path.split('/')[2]
      const run = { id: 'run-1', status: 'COMPLETED', ...body }
      runs.set(id, [run])
      return run
    }
    const id = path.split('/')[2]
    assert.ok(saved.has(id), path)
    return { session: saved.get(id), runs: runs.get(id) || [], lastSeq: 0, usage: null }
  }
  const cache = new Map(), nativeRequire = createRequire(import.meta.url)
  function load(path) {
    if (cache.has(path)) return cache.get(path).exports
    const module = { exports: {} }
    cache.set(path, module)
    let source = readFileSync(path, 'utf8')
    if (path.endsWith('.vue')) source = compileScript(parse(source).descriptor, { id: 'session-test' }).content
    const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
    new Function('require', 'module', 'exports', output)(name => {
      if (name === 'vue') return { ...vue, onMounted() {}, onUnmounted() {} }
      if (name.endsWith('.vue')) return {}
      if (name === './preferences') return { preferences: vue.ref({ chatFontSize: 'standard', autoScroll: true, sendShortcut: 'enter' }), shouldSendPrompt: () => true }
      if (name.startsWith('.')) {
        const exports = load(resolve(dirname(path), `${name}.ts`))
        return name === './api' ? { ...exports, api: request } : exports
      }
      return nativeRequire(name)
    }, module, module.exports)
    return module.exports
  }
  const scope = vue.effectScope()
  const state = scope.run(() => load(fileURLToPath(new URL('../src/App.vue', import.meta.url))).default.setup({}, { expose() {} }))
  state.profiles.value = [{ id: 'default', model: { configured: true } }]
  state.workspaces.value = [{ id: 'workspace-1', name: '项目', directories: ['E:/project'], icon: 'folder', color: '#123456', revision: 0 }]
  return { state, calls, saved, stored, rejectRun: value => { rejectRun = value },
    workspaceSettings: (props, emit) => scope.run(() => load(fileURLToPath(new URL('../src/WorkspaceSettings.vue', import.meta.url))).default.setup(props, { emit, expose() {} })),
    close: () => { scope.stop(); state.source?.close(); clearTimeout(state.refreshTimer); clearTimeout(state.watchdogTimer) } }
}

test('repeated new actions and workspace selection do not save or list empty conversations', async t => {
  const app = boot(); t.after(app.close)
  const s = app.state
  await s.createSession(); await s.createSession(); await s.createSession()
  await s.send()
  assert.equal(app.calls.length, 0)
  await s.createWorkspaceSession('workspace-1'); await s.createWorkspaceSession('workspace-1')
  s.prompt.value = '尚未发送'
  await s.chooseWorkspace('')
  await s.chooseWorkspace('workspace-1')
  await s.reloadSessions()
  assert.equal(app.saved.size, 0)
  assert.deepEqual(s.sessions.value, [])
  assert.equal(s.selectedId.value, '')
  assert.equal(s.snapshot.value.session.workspaceId, 'workspace-1')
  assert.equal(s.prompt.value, '尚未发送')
  assert.equal(app.calls.some(call => call.path === '/sessions' && call.body), false)
})

test('saving a multi-root workspace before sending updates only the draft', async t => {
  const app = boot(); t.after(app.close)
  const s = app.state
  await s.createSession()
  s.prompt.value = '尚未发送'
  let selected
  const settings = app.workspaceSettings({ initialPath: '', disabled: false }, (name, value) => { if (name === 'saved') selected = value })
  settings.name.value = '草稿项目'
  settings.directories.value = ['E:/project', 'E:/other']
  await settings.save()
  assert.ok(selected)
  await s.workspaceSaved(selected)
  assert.equal(app.saved.size, 0)
  assert.equal(s.selectedId.value, '')
  assert.equal(s.snapshot.value.session.workspace, '["E:/project","E:/other"]')
  assert.equal(s.prompt.value, '尚未发送')
  assert.equal(app.calls.some(call => call.path === '/sessions' && call.body), false)
})

test('first send saves once and retains workspace, model choice, mentions and attachments', async t => {
  const app = boot(); t.after(app.close)
  const s = app.state
  await s.createWorkspaceSession('workspace-1')
  s.prompt.value = '分析项目'
  s.reasoningEffort.value = 'high'
  s.approvalMode.value = 'SMART'
  s.draftMentions.value = [{ root: 1, path: 'README.md', directory: false }]
  s.draftAttachments.value = [{ key: 'a', id: 'attachment-1', name: 'notes.txt', preview: '', error: '' }]
  assert.equal(app.saved.size, 0)
  await s.send()
  assert.equal(app.saved.size, 1)
  assert.equal(s.sessions.value.length, 1)
  assert.equal(s.selectedId.value, 'session-1')
  const created = app.calls.find(call => call.path === '/sessions' && call.body)
  assert.equal(created.body.workspaceId, 'workspace-1')
  const submitted = app.calls.find(call => call.path.endsWith('/runs'))
  assert.deepEqual(submitted.body.mentions, [{ root: 1, path: 'README.md', directory: false }])
  assert.deepEqual(submitted.body.attachmentIds, ['attachment-1'])
  assert.equal(submitted.body.reasoningEffort, 'high')
  assert.equal(submitted.body.approvalMode, 'SMART')
  assert.equal(s.prompt.value, '')
})

test('attachment-only first messages submit and uncertain retries reuse the same session and request', async t => {
  const app = boot(); t.after(app.close)
  const s = app.state
  await s.createSession()
  s.draftAttachments.value = [{ key: 'a', id: 'attachment-1', name: 'notes.txt', preview: '', error: '' }]
  app.rejectRun(true)
  await s.send()
  assert.equal(app.saved.size, 1)
  assert.ok(s.pending.value)
  app.rejectRun(false)
  await s.send()
  const submissions = app.calls.filter(call => call.path.endsWith('/runs'))
  assert.equal(app.saved.size, 1)
  assert.equal(submissions.length, 2)
  assert.equal(submissions[0].body.requestId, submissions[1].body.requestId)
  assert.equal(submissions[0].body.prompt, '见附件：notes.txt')
})

test('new drafts leave existing conversations and their unsent text intact', async t => {
  const app = boot(); t.after(app.close)
  const s = app.state
  await s.createWorkspaceSession('workspace-1')
  s.prompt.value = '已发送'
  await s.send()
  s.prompt.value = '原会话的未发送消息'
  s.approvalMode.value = 'SMART'
  await s.createSession()
  assert.equal(s.selectedId.value, '')
  assert.equal(s.approvalMode.value, 'MANUAL')
  assert.equal(app.saved.size, 1)
  await s.reloadSessions()
  assert.equal(s.sessions.value.length, 1)
  await s.selectSession('session-1')
  assert.equal(s.prompt.value, '原会话的未发送消息')
  assert.equal(s.snapshot.value.runs.length, 1)
})

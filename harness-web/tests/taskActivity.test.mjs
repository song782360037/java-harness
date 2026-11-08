import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import { taskStatusLabel, taskSourceLabel, taskTone } from '../src/taskActivityModel.ts'

let bootCount = 0
async function boot(bridge) {
  globalThis.window = { harnessDesktop: bridge }
  const source = fs.readFileSync(new URL('../src/taskActivity.ts', import.meta.url), 'utf8')
    .replace("from 'vue'", `from '${import.meta.resolve('vue')}'`)
    .replace("import { api } from './api'", 'const api = globalThis.taskActivityApi')
  const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(output + `\n// boot ${++bootCount}`).toString('base64')}`)
}

test('task state labels distinguish waiting, cancellation and failures', () => {
  assert.equal(taskStatusLabel('CANCELLING'), '正在停止')
  assert.equal(taskStatusLabel('CANCELLED'), '已取消')
  assert.equal(taskStatusLabel('WAITING_APPROVAL'), '等待审批')
  assert.equal(taskStatusLabel('WAITING_ANSWER'), '等待回答')
  assert.equal(taskTone('WAITING_ANSWER'), 'waiting')
  assert.equal(taskTone('TIMED_OUT'), 'error')
  assert.equal(taskTone('COMPLETED'), 'success')
  assert.equal(taskSourceLabel('GROUP'), '群聊')
  assert.equal(taskSourceLabel('AUTOMATION'), '自动化')
})

test('desktop events win over a slower initial response and unsubscribe cleanly', async () => {
  let initial, listener, navigation, removed = 0
  const bridge = {
    getTaskActivity: () => new Promise(resolve => { initial = resolve }),
    onTaskActivity: callback => { listener = callback; return () => removed++ },
    onOpenTask: callback => { navigation = callback; return () => removed++ },
    takePendingTask: async () => ({ id: 'queued-navigation' }),
  }
  const app = await boot(bridge), opened = []
  const stop = app.connectTaskActivity(task => opened.push(task.id))
  listener({ revision: 2, active: [{ id: 'new' }], recent: [] })
  initial({ revision: 1, active: [], recent: [] })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(app.taskActivity.value.active[0].id, 'new')
  assert.deepEqual(opened, ['queued-navigation'])
  navigation({ id: 'clicked' })
  stop()
  navigation({ id: 'after-dispose' })
  listener({ revision: 3, active: [], recent: [] })
  assert.deepEqual(opened, ['queued-navigation', 'clicked'])
  assert.equal(app.taskActivity.value.active[0].id, 'new')
  assert.equal(removed, 2)
})

test('failed notification persistence keeps the previous setting and allows retry', async () => {
  let fail = true
  const app = await boot({ setTaskNotifications: async value => {
    if (fail) throw new Error('disk full')
    return { revision: 1, active: [], recent: [], notificationsEnabled: value }
  } })
  await app.setTaskNotifications(false)
  assert.match(app.taskActivityError.value, /无法保存/)
  assert.equal(app.taskActivity.value.notificationsEnabled, undefined)
  assert.equal(app.taskNotificationsBusy.value, false)
  fail = false
  await app.setTaskNotifications(false)
  assert.equal(app.taskActivity.value.notificationsEnabled, false)
})

test('browser disconnect aborts the request and removes recovery listeners', async () => {
  const listeners = new Map()
  let signal
  globalThis.taskActivityApi = (_path, _body, value) => {
    signal = value
    return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted'))))
  }
  const app = await boot(undefined)
  globalThis.document = { hidden: false, addEventListener: (type, cb) => listeners.set(type, cb), removeEventListener: type => listeners.delete(type) }
  window.addEventListener = (type, cb) => listeners.set(type, cb)
  window.removeEventListener = type => listeners.delete(type)
  const stop = app.connectTaskActivity(() => {})
  stop()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(signal.aborted, true)
  assert.equal(listeners.size, 0)
  assert.equal(app.taskActivityError.value, '')
})

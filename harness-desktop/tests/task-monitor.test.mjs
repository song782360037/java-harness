import { test } from 'node:test'
import assert from 'node:assert/strict'
import { TaskMonitor } from '../src/task-monitor.mjs'

const moment = '2026-11-08T13:00:00Z'
const task = (id, status, attentionKey = '') => ({ id, title: id, status, attentionKey, updatedAt: moment })
const snapshot = (active = [], recent = []) => ({ active, recent, observedAt: moment })
function setup(enabled = true) {
  const notifications = [], states = []
  const monitor = new TaskMonitor({ read: async () => snapshot(), notify: task => notifications.push(task), publish: state => states.push(state), enabled })
  return { monitor, notifications, states }
}

test('startup does not replay history but prompts current pending work', () => {
  const { monitor, notifications } = setup()
  monitor.accept(snapshot([task('pending', 'WAITING_APPROVAL', 'command')], [task('old', 'COMPLETED')]))
  assert.deepEqual(notifications.map(task => task.id), ['pending'])
})

test('completion and failure are notified once, including tasks between polls', () => {
  const { monitor, notifications } = setup()
  monitor.accept(snapshot([task('running', 'RUNNING')]))
  const finished = snapshot([], [task('running', 'COMPLETED'), task('quick', 'FAILED')])
  monitor.accept(finished)
  monitor.accept(finished)
  assert.deepEqual(notifications.map(task => task.id), ['running', 'quick'])
})

test('removing one pending operation does not re-notify the other', () => {
  const { monitor, notifications } = setup()
  monitor.accept(snapshot([task('run', 'WAITING_APPROVAL', 'a,b')]))
  monitor.accept(snapshot([task('run', 'WAITING_ANSWER', 'b')]))
  monitor.accept(snapshot([task('run', 'WAITING_APPROVAL', 'b,c')]))
  assert.equal(notifications.length, 2)
})

test('notifications switched off still track state and do not replay when re-enabled', () => {
  const { monitor, notifications } = setup(false)
  monitor.accept(snapshot())
  monitor.accept(snapshot([], [task('finished', 'COMPLETED')]))
  monitor.setEnabled(true)
  monitor.accept(snapshot([], [task('finished', 'COMPLETED')]))
  assert.equal(notifications.length, 0)
  assert.throws(() => monitor.setEnabled('yes'))
})

test('old records exposed by deleting recent history remain silent', () => {
  const { monitor, notifications } = setup()
  monitor.accept(snapshot())
  monitor.accept(snapshot([], [{ ...task('old', 'COMPLETED'), updatedAt: '2026-11-07T13:00:00Z' }]))
  assert.equal(notifications.length, 0)
})

test('a previously active group completing without a new message still notifies', () => {
  const { monitor, notifications } = setup()
  const group = { ...task('group', 'RUNNING'), updatedAt: '2026-11-07T13:00:00Z' }
  monitor.accept(snapshot([group]))
  monitor.accept(snapshot([], [{ ...group, status: 'COMPLETED' }]))
  assert.equal(notifications.length, 1)
})

test('stopping while a fetch is in flight prevents publication and rescheduling', async () => {
  let resolve
  const states = []
  const monitor = new TaskMonitor({ read: () => new Promise(done => { resolve = done }), notify() {}, publish: value => states.push(value) })
  monitor.start()
  monitor.stop()
  resolve(snapshot())
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(states, [])
  assert.equal(monitor.timer, undefined)
})

test('read failures preserve the last tasks, then recover without duplicate notices', async () => {
  const { monitor, notifications } = setup()
  monitor.accept(snapshot([task('running', 'RUNNING')]))
  monitor.stopped = false
  monitor.read = async () => { throw new Error('offline') }
  await monitor.poll()
  monitor.stop()
  assert.equal(monitor.state.active[0].id, 'running')
  assert.ok(monitor.state.error)
  monitor.accept(snapshot([], [task('running', 'COMPLETED')]))
  assert.equal(monitor.state.error, '')
  assert.equal(notifications.length, 1)
})

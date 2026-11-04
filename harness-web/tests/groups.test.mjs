import { test } from 'node:test'
import assert from 'node:assert/strict'
import { approvalTargets, hostLabel, insertMention, memberStates, mentionCandidates, mentionQuery, roundNotice, roundSpeakers, speakingMembers, studioSummary, taskRelations, taskSteps } from '../src/groups.ts'

const member = (id, name, enabled = true) => ({ id, name, enabled, sessionId: `s-${id}` })

test('mention query starts at an @ after whitespace or the beginning', () => {
  assert.deepEqual(mentionQuery('@后端', 3), { start: 0, query: '后端' })
  assert.deepEqual(mentionQuery('请 @测', 4), { start: 2, query: '测' })
  assert.deepEqual(mentionQuery('请@', 2), null)
  assert.equal(mentionQuery('mail@host', 9), null)
  assert.equal(mentionQuery('@后端 已经', 6), null)
})

test('inserting a mention replaces the typed query and keeps the rest', () => {
  assert.deepEqual(insertMention('看下 @后 这里', 5, '后端开发'), { text: '看下 @后端开发  这里', caret: 9 })
  assert.deepEqual(insertMention('没有提及', 4, '后端开发'), { text: '没有提及', caret: 4 })
})

test('candidates exclude disabled members and rank prefix matches first', () => {
  const members = [member('1', '测试工程师'), member('2', '前端开发'), member('3', '后端开发', false), member('4', '开发经理')]
  assert.deepEqual(mentionCandidates(members, '开发').map(value => value.name), ['开发经理', '前端开发'])
  assert.deepEqual(mentionCandidates(members, '').map(value => value.id), ['1', '2', '4'])
})

test('round notices show failures, stops and limits but not a quiet end', () => {
  assert.equal(roundNotice(null), null)
  assert.equal(roundNotice({ status: 'COMPLETED', detail: '' }), null)
  assert.equal(roundNotice({ status: 'SUPERSEDED', detail: '用户发送了新消息' }), null)
  assert.deepEqual(roundNotice({ status: 'COMPLETED', detail: '已达到本轮发言上限 12 次' }), { text: '本轮已结束：已达到本轮发言上限 12 次', error: false })
  assert.deepEqual(roundNotice({ status: 'FAILED', detail: '主持人调度失败' }), { text: '协作中断：主持人调度失败', error: true })
})

test('speaking members are the members behind active runs', () => {
  const snapshot = {
    members: [member('1', '后端开发'), member('2', '测试工程师')],
    runs: [{ id: 'r1', sessionId: 's-1', status: 'RUNNING' }, { id: 'r2', sessionId: 's-2', status: 'COMPLETED' }, { id: 'r3', sessionId: 'gone', status: 'RUNNING' }],
  }
  assert.deepEqual(speakingMembers(snapshot).map(value => [value.member.name, value.run.id]), [['后端开发', 'r1']])
})

test('task steps follow the latest dependency and keep plan order', () => {
  const task = (key, dependsOn = []) => ({ key, dependsOn })
  const steps = taskSteps([task('a'), task('b'), task('c', ['a']), task('d', ['c', 'b']), task('e', ['a'])])
  assert.deepEqual(steps.map(step => step.map(value => value.key)), [['a', 'b'], ['c', 'e'], ['d']])
  assert.deepEqual(taskSteps([]), [])
})

const studio = (overrides = {}) => ({
  group: { mode: 'TASKS' }, busy: true, round: { id: 'round' }, messages: [], tasks: [], runs: [],
  members: [member('1', '前端开发'), member('2', '后端开发'), member('3', '测试工程师')], ...overrides,
})
const task = (key, memberId, status, dependsOn = [], runId = null) => ({ key, memberId, status, dependsOn, runId })
const run = (id, sessionId, status, workspace = 'w') => ({ id, sessionId, status, workspace, activity: 0 })
const kinds = (snapshot, approvals = {}) => memberStates(snapshot, approvals).map(value => value.kind)

test('a pending command outranks every other desk state and asks for attention', () => {
  const snapshot = studio({ runs: [run('r1', 's-1', 'RUNNING')], tasks: [task('a', '1', 'RUNNING', [], 'r1')] })
  const [state] = memberStates(snapshot, { r1: { commands: ['APPROVED', 'PENDING'], changes: [] } })
  assert.equal(state.kind, 'approval')
  assert.equal(state.attention, true)
  assert.equal(state.task.key, 'a')
  assert.deepEqual(kinds(snapshot, { r1: { commands: ['REVIEWING'], changes: [] } })[0], 'reviewing')
  assert.deepEqual(kinds(snapshot)[0], 'working')
})

test('active runs show stopping, queued and a superseded round still winding down', () => {
  const snapshot = studio({
    runs: [run('r1', 's-1', 'CANCELLING'), run('r2', 's-2', 'QUEUED'), run('old', 's-3', 'RUNNING')],
    tasks: [task('a', '1', 'RUNNING', [], 'r1'), task('b', '2', 'RUNNING', [], 'r2')],
  })
  assert.deepEqual(kinds(snapshot), ['stopping', 'queued', 'finishing'])
  // Discussion mode has no tasks, so an active run is simply working.
  assert.deepEqual(kinds({ ...snapshot, group: { mode: 'DISCUSS' }, tasks: [] }), ['stopping', 'queued', 'working'])
})

test('pending tasks wait on upstream or are ready, only while the group is busy', () => {
  const tasks = [task('a', '1', 'DONE', [], 'r1'), task('b', '2', 'PENDING', ['a']), task('c', '3', 'PENDING', ['b'])]
  const snapshot = studio({ tasks, runs: [run('r1', 's-1', 'COMPLETED')] })
  assert.deepEqual(kinds(snapshot), ['done', 'ready', 'blocked'])
  assert.deepEqual(kinds({ ...snapshot, busy: false }), ['done', 'idle', 'idle'])
})

test('a finished run with unreviewed file changes is not reported as done', () => {
  const snapshot = studio({ tasks: [task('a', '1', 'DONE', [], 'r1')], runs: [run('r1', 's-1', 'COMPLETED')] })
  const [state] = memberStates(snapshot, { r1: { commands: [], changes: ['APPLIED', 'PENDING'] } })
  assert.equal(state.kind, 'review')
  assert.equal(state.attention, true)
  assert.equal(kinds(snapshot, { r1: { commands: [], changes: ['APPLIED'] } })[0], 'done')
})

test('failed, skipped and disabled members keep their own labels', () => {
  const members = [member('1', '前端开发'), member('2', '后端开发'), member('3', '测试工程师', false)]
  const snapshot = studio({ members, busy: false, tasks: [task('a', '1', 'INTERRUPTED'), task('b', '2', 'SKIPPED', ['a'])] })
  const states = memberStates(snapshot, {})
  assert.deepEqual(states.map(value => [value.kind, value.label]), [['failed', '已中断'], ['skipped', '已跳过'], ['disabled', '已停用']])
})

test('approval targets are active runs plus each member latest run with a workspace', () => {
  const snapshot = studio({ runs: [run('r1', 's-1', 'COMPLETED'), run('r2', 's-1', 'COMPLETED'), run('r3', 's-2', 'RUNNING'), run('r4', 's-3', 'COMPLETED', '')] })
  assert.deepEqual(approvalTargets(snapshot).map(value => value.id), ['r2', 'r3'])
})

test('host label follows mode, planning and speaking members', () => {
  assert.equal(hostLabel(studio({ busy: false })), '空闲')
  assert.equal(hostLabel(studio()), '拆分任务')
  assert.equal(hostLabel(studio({ tasks: [task('a', '1', 'PENDING')] })), '推进任务')
  assert.equal(hostLabel(studio({ runs: [run('r1', 's-1', 'RUNNING')] })), '跟进任务')
  assert.equal(hostLabel(studio({ group: { mode: 'DISCUSS' } })), '安排发言')
})

test('task relations are the direct upstream and downstream keys', () => {
  const tasks = [task('a', '1', 'DONE'), task('b', '2', 'PENDING', ['a']), task('c', '3', 'PENDING', ['b']), task('d', '3', 'PENDING', ['a'])]
  const value = taskRelations(tasks, 'b')
  assert.deepEqual([...value.upstream], ['a'])
  assert.deepEqual([...value.downstream], ['c'])
  assert.deepEqual([...taskRelations(tasks, 'missing').upstream], [])
})

test('round speakers are member replies of the latest round only', () => {
  const message = (id, roundId, author) => ({ id, roundId, author })
  const messages = [message('1', 'old', 'MEMBER'), message('2', 'round', 'USER'), message('3', 'round', 'MEMBER'), message('4', 'round', 'SYSTEM')]
  assert.deepEqual(roundSpeakers(studio({ messages })).map(value => value.id), ['3'])
  assert.deepEqual(roundSpeakers(studio({ messages, round: null })), [])
})

test('studio summary counts working members and what waits on the user', () => {
  const snapshot = studio({ runs: [run('r1', 's-1', 'RUNNING'), run('r2', 's-2', 'RUNNING')], tasks: [task('a', '1', 'RUNNING', [], 'r1'), task('b', '2', 'RUNNING', [], 'r2')] })
  const states = memberStates(snapshot, { r2: { commands: ['PENDING'], changes: [] } })
  assert.deepEqual(studioSummary(snapshot, states), { text: '1 人工作中 · 1 个命令待批准', attention: true })
  assert.deepEqual(studioSummary(studio({ busy: false }), memberStates(studio({ busy: false }), {})), { text: '团队空闲', attention: false })
  assert.equal(studioSummary(studio(), memberStates(studio(), {})).text, '主持人正在拆分任务')
})

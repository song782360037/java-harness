import { test } from 'node:test'
import assert from 'node:assert/strict'
import { insertMention, mentionCandidates, mentionQuery, roundNotice, speakingMembers, taskSteps } from '../src/groups.ts'

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

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { emptyDraft, executionState, scheduleLabel } from '../src/automations.ts'

test('schedule labels describe each preset', () => {
  const base = { scheduleTime: '09:30', scheduleWeekday: 1, cron: '' }
  assert.equal(scheduleLabel({ ...base, scheduleKind: 'DAILY' }), '每天 09:30')
  assert.equal(scheduleLabel({ ...base, scheduleKind: 'WEEKDAYS' }), '工作日 09:30')
  assert.equal(scheduleLabel({ ...base, scheduleKind: 'WEEKLY' }), '每周一 09:30')
  assert.equal(scheduleLabel({ ...base, scheduleKind: 'HOURLY', scheduleTime: '00:05' }), '每小时第 5 分')
  assert.equal(scheduleLabel({ ...base, scheduleKind: 'CUSTOM', cron: '0 9 1 * *' }), 'cron 0 9 1 * *')
  assert.equal(scheduleLabel({ ...base, scheduleKind: 'MANUAL' }), '仅手动运行')
})

test('execution state combines trigger and run status', () => {
  assert.deepEqual(executionState({ status: 'SKIPPED', runStatus: null }), { label: '已跳过', tone: 'muted' })
  assert.deepEqual(executionState({ status: 'FAILED', runStatus: null }), { label: '触发失败', tone: 'danger' })
  assert.equal(executionState({ status: 'STARTED', runStatus: 'RUNNING' }).label, '运行中')
  assert.equal(executionState({ status: 'STARTED', runStatus: 'COMPLETED' }).tone, 'success')
  assert.equal(executionState({ status: 'STARTED', runStatus: null }).label, '会话已删除')
})

test('new drafts default to a daily morning run without commands', () => {
  const draft = emptyDraft('w1', 'fast')
  assert.equal(draft.scheduleKind, 'DAILY')
  assert.equal(draft.scheduleTime, '09:00')
  assert.equal(draft.allowCommands, false)
  assert.equal(draft.workspaceId, 'w1')
  assert.equal(draft.modelProfileId, 'fast')
})

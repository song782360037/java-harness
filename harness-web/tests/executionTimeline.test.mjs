import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildTimeline, elapsedSeconds, exploreSummary, formatDuration, toolLabel } from '../src/executionTimeline.ts'

const tool = (step, callId, name, args, status = 'COMPLETED') => ({ runId: 'r', step, callId, name, arguments: JSON.stringify(args), status, output: '', startedAt: null, finishedAt: null })
const step = (number, outputStart, reasoning = '', startedAt = '2026-01-01T00:00:00Z', reasoningAt = null, finishedAt = null) => ({ step: number, outputStart, reasoning, startedAt, reasoningAt, finishedAt })

test('steps interleave reasoning, text and tools and keep the final answer outside the process', () => {
  const output = '先读取代码分析完成'
  const steps = [step(1, 0, '想一想', '2026-01-01T00:00:00Z', '2026-01-01T00:00:03Z'), step(2, 5)]
  const tools = [tool(1, 'a', 'read_file', { path: 'App.java' }), tool(1, 'b', 'search_files', { query: 'class' })]
  const { items, answer } = buildTimeline(output, steps, tools, false)
  assert.deepEqual(items.map(item => item.kind), ['reasoning', 'text', 'explore'])
  assert.equal(items[0].seconds, 3)
  assert.equal(items[1].text, '先读取代码')
  assert.equal(items[2].tools.length, 2)
  assert.equal(answer, '分析完成')
})
test('active runs keep streaming text inside the process and mark live reasoning', () => {
  const { items, answer } = buildTimeline('', [step(1, 0, '思考中')], [], true)
  assert.equal(answer, '')
  assert.equal(items[0].live, true)
})
test('failed runs ending in tools have no answer and non-explore tools stay single rows', () => {
  const tools = [tool(1, 'a', 'read_file', { path: 'a' }), tool(1, 'b', 'propose_command', { command: 'npm test' }), tool(1, 'c', 'read_file', { path: 'b' })]
  const { items, answer } = buildTimeline('', [step(1, 0)], tools, false)
  assert.equal(answer, '')
  assert.deepEqual(items.map(item => item.kind), ['tool', 'tool', 'tool'])
})
test('legacy runs without steps show tools and the whole output as answer', () => {
  const { items, answer } = buildTimeline('完整回答', [], [tool(1, 'a', 'git_status', {})], false)
  assert.equal(items.length, 1)
  assert.equal(answer, '完整回答')
})
test('labels, summaries and durations are readable', () => {
  assert.deepEqual(toolLabel(tool(1, 'a', 'read_file', { path: 'a.ts', start_line: 3, end_line: 9 })), { icon: 'file', verb: '读取', target: 'a.ts:3-9' })
  assert.equal(toolLabel(tool(1, 'a', 'mcp_tool', {})).raw, true)
  assert.equal(toolLabel({ ...tool(1, 'a', 'read_file', {}), arguments: '{bad' }).target, '')
  assert.equal(exploreSummary([tool(1, 'a', 'read_file', { path: 'x' }), tool(1, 'b', 'read_file', { path: 'x' }), tool(1, 'c', 'search_files', { query: 'q' }, 'RUNNING')]), '正在探索 1 个文件，1 次搜索')
  assert.equal(exploreSummary([tool(1, 'a', 'read_file', { path: 'x' }, 'FAILED'), tool(1, 'b', 'read_file', { path: 'y' })]), '已探索 2 个文件（1 次失败）')
  assert.equal(formatDuration(0), '1 秒')
  assert.equal(formatDuration(125), '2 分 5 秒')
  assert.equal(elapsedSeconds([step(1, 0, '', '2026-01-01T00:00:00Z', null, '2026-01-01T00:00:42Z')], 0), 42)
})

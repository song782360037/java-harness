import test from 'node:test'
import assert from 'node:assert/strict'
import { exportFilename, formatSessionMarkdown } from '../src/sessionExport.ts'

test('formatSessionMarkdown renders session metadata, prompts, outputs and failure fallback cleanly', () => {
  const snapshot = {
    session: { id: 's-1', title: '架构评审', createdAt: '2026-11-24T00:00:00Z', workspace: '/repo', workspaceId: 'ws-1', modelProfileId: 'default', reasoningEffort: 'low', approvalMode: 'MANUAL' },
    runs: [
      { id: 'r-1', sessionId: 's-1', requestId: 'req-1', prompt: '分析模块边界', scenario: 'NORMAL', status: 'COMPLETED', output: '核心模块零外部依赖。', detail: '', createdAt: '2026-11-24T01:00:00Z', provider: 'openai', model: 'gpt-5', context: null, workspace: '/repo', modelProfileId: 'default', reasoningEffort: 'low' },
      { id: 'r-2', sessionId: 's-1', requestId: 'req-2', prompt: '补充性能压测', scenario: 'NORMAL', status: 'FAILED', output: '', detail: '上游服务超时', createdAt: '2026-11-24T01:05:00Z', provider: 'openai', model: 'gpt-5', context: null, workspace: '/repo', modelProfileId: 'default', reasoningEffort: 'low' },
    ],
    lastSeq: 4,
    usage: null,
  }
  const md = formatSessionMarkdown(snapshot, 'Java Harness', new Date('2026-11-24T12:00:00.000Z'))
  assert.match(md, /^# 架构评审\n/)
  assert.match(md, /- 工作区：Java Harness/)
  assert.match(md, /- 轮次：2/)
  assert.match(md, /## 第 1 轮/)
  assert.match(md, /### 回复（openai \/ gpt-5）/)
  assert.match(md, /核心模块零外部依赖。/)
  assert.match(md, /### 回复（openai \/ gpt-5 · 执行失败）/)
  assert.match(md, /上游服务超时/)
})

test('exportFilename strips forbidden filesystem characters and falls back safely', () => {
  assert.equal(exportFilename('排查: /api/v1?a=1*2'), '排查 api v1 a=1 2.md')
  assert.equal(exportFilename('   ***   '), 'conversation.md')
  assert.ok(exportFilename('a'.repeat(120)).length <= 67)
})

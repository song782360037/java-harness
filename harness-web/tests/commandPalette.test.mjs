import test from 'node:test'
import assert from 'node:assert/strict'
import { filterPaletteItems, isPaletteShortcut, mergeContentHits } from '../src/commandPalette.ts'

test('isPaletteShortcut matches Ctrl+K and Cmd+K while ignoring IME, Alt and Shift', () => {
  assert.equal(isPaletteShortcut({ key: 'k', ctrlKey: true, metaKey: false, altKey: false, shiftKey: false }), true)
  assert.equal(isPaletteShortcut({ key: 'K', ctrlKey: false, metaKey: true, altKey: false, shiftKey: false }), true)
  assert.equal(isPaletteShortcut({ key: 'k', ctrlKey: true, metaKey: false, altKey: true, shiftKey: false }), false)
  assert.equal(isPaletteShortcut({ key: 'k', ctrlKey: true, metaKey: false, altKey: false, shiftKey: true }), false)
  assert.equal(isPaletteShortcut({ key: 'k', ctrlKey: true, metaKey: false, altKey: false, shiftKey: false, isComposing: true }), false)
  assert.equal(isPaletteShortcut({ key: 'p', ctrlKey: true, metaKey: false, altKey: false, shiftKey: false }), false)
})

test('filterPaletteItems supports multi-token queries and ranks title prefix matches first', () => {
  const items = [
    { id: '1', group: 'page', title: '打开设置', subtitle: '模型配置与偏好', keywords: 'settings', icon: 'settings' },
    { id: '2', group: 'page', title: '模型与供应商设置', subtitle: 'API Key', keywords: 'models', icon: 'reasoning' },
    { id: '3', group: 'action', title: '导出当前会话为 Markdown', subtitle: '下载 .md', keywords: 'export md', icon: 'download' },
  ]
  assert.deepEqual(filterPaletteItems(items, '模型').map(item => item.id), ['2', '1'])
  assert.deepEqual(filterPaletteItems(items, 'export md').map(item => item.id), ['3'])
  assert.deepEqual(filterPaletteItems(items, '不存在').map(item => item.id), [])
})

test('mergeContentHits attaches snippets to local session matches and appends content-only hits', () => {
  const workspaces = [{ id: 'ws-1', name: '核心仓库', directories: ['/repo'], icon: 'folder', color: '#123456', revision: 1 }]
  const local = [
    { id: 'session:s-1', group: 'session', title: '排查超时', subtitle: '核心仓库', icon: 'chat' },
  ]
  const hits = [
    {
      session: { id: 's-1', title: '排查超时', createdAt: '2026-11-24T00:00:00Z', workspace: '/repo', workspaceId: 'ws-1', modelProfileId: '', reasoningEffort: '', approvalMode: 'MANUAL' },
      matchedIn: 'output',
      snippet: '建议调大 connectTimeout 参数',
      matchedAt: '2026-11-24T00:00:00Z',
    },
    {
      session: { id: 's-2', title: '性能优化记录', createdAt: '2026-11-24T00:00:00Z', workspace: '/repo', workspaceId: 'ws-1', modelProfileId: '', reasoningEffort: '', approvalMode: 'MANUAL' },
      matchedIn: 'prompt',
      snippet: '请帮我分析 connectTimeout 异常日志',
      matchedAt: '2026-11-24T00:00:00Z',
    },
  ]
  const merged = mergeContentHits(local, hits, workspaces)
  assert.equal(merged.length, 2)
  assert.equal(merged[0].id, 'session:s-1')
  assert.equal(merged[0].subtitle, '回复：建议调大 connectTimeout 参数')
  assert.equal(merged[1].id, 'session:s-2')
  assert.equal(merged[1].group, 'content')
  assert.equal(merged[1].subtitle, '核心仓库 · 提问：请帮我分析 connectTimeout 异常日志')
})

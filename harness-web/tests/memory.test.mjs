import { test } from 'node:test'
import assert from 'node:assert/strict'
import { memoryCamera, memoryLayout, memoryTopics } from '../src/memory.ts'

test('memory topics prefer the most shared distinguishing tag and fall back to the kind', () => {
  const topics = memoryTopics([
    { id: '1', kind: 'FACT', tags: ['tool_choice', '项目', 'OpenAI'] },
    { id: '2', kind: 'FACT', tags: ['流式', '项目', 'OpenAI'] },
    { id: '3', kind: 'DECISION', tags: ['Maven', '项目'] },
    { id: '4', kind: 'FACT', tags: ['项目', 'OpenAI'] },
  ])
  assert.deepEqual([...topics.values()], ['OpenAI', 'OpenAI', '项目', 'OpenAI'])
  assert.deepEqual([...memoryTopics([{ id: '1', kind: 'DECISION', tags: ['独有'] }]).values()], ['决策'])
  assert.deepEqual([...memoryTopics([{ id: '1', kind: 'FACT', tags: ['数据库'] }, { id: '2', kind: 'FACT', tags: ['数据库'] }]).values()], ['数据库', '数据库'])
})

test('memory graph positions are deterministic, finite and unique within a topic', () => {
  const entries = Array.from({ length: 200 }, (_, i) => ({ id: String(i).padStart(3, '0'), title: `记忆${i}`, kind: 'FACT', tags: ['数据库'] }))
  const graph = memoryLayout(entries)
  assert.deepEqual(graph, memoryLayout(entries.toReversed()))
  assert.equal(new Set(graph.nodes.map(node => `${node.x}:${node.y}`)).size, 200)
  assert.ok(graph.nodes.every(node => Number.isFinite(node.x) && Number.isFinite(node.y)))
  assert.equal(graph.centers.length, 1)
})

test('untagged memories cluster by type and empty data remains empty', () => {
  const graph = memoryLayout([{ id: '1', kind: 'FACT', tags: [] }, { id: '2', kind: 'DECISION', tags: [] }])
  assert.deepEqual(graph.centers.map(center => center.name).sort(), ['决策', '项目事实'].sort())
  assert.deepEqual(memoryLayout([]), { centers: [], nodes: [] })
})

test('dense graphs keep node centers selectable and fit inside a narrow viewport', () => {
  for (const tags of [i => [`主题${i % 20}`], () => ['同一主题']]) {
    const entries = Array.from({ length: 200 }, (_, i) => ({ id: String(i), kind: 'FACT', tags: tags(i) }))
    const graph = memoryLayout(entries, 390, 480)
    const camera = memoryCamera(graph, 390, 480)
    assert.ok(graph.nodes.every(node => {
      const x = node.x * camera.zoom + camera.x, y = node.y * camera.zoom + camera.y
      return x >= 20 && x <= 370 && y >= 40 && y <= 400
    }))
    // SVG 节点命中圆半径为19，别的节点不能遮住其可见核心。
    for (const node of graph.nodes) {
      assert.ok(graph.nodes.every(other => other === node || Math.hypot(other.x - node.x, other.y - node.y) > 24))
    }
    assert.equal(new Set(graph.nodes.map(node => `${node.x}:${node.y}`)).size, 200)
  }
})

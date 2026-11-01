import { test } from 'node:test'
import assert from 'node:assert/strict'
import { memoryLayout } from '../src/memory.ts'

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

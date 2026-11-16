import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isOpen, itemState, layoutFlow, needsUser, NODE_HEIGHT, NODE_WIDTH, stepStateLabel } from '../src/flows.ts'

const node = (key, type) => ({ key, type, name: key, description: '', modelProfileId: '', reasoningEffort: null, approvalMode: '' })
const edge = (from, to, label = '') => ({ from, to, label })
const flow = (nodes, edges) => ({ nodes: Object.entries(nodes).map(([key, type]) => node(key, type)), edges: edges.map(item => edge(...item)) })
// start → gate —通过→ review —通过→ accept(你) → done；gate/review —不通过→ returned；accept —打回→ review
const review = flow({ start: 'TRIGGER', gate: 'AGENT', review: 'AGENT', accept: 'HUMAN', returned: 'END', done: 'END' },
  [['start', 'gate'], ['gate', 'review', '通过'], ['gate', 'returned', '不通过'], ['review', 'accept', '通过'],
    ['review', 'returned', '不通过'], ['accept', 'done', '合并'], ['accept', 'review', '打回']])
const shapes = {
  review,
  // 没有人工泳道的打回回环。
  rework: flow({ start: 'TRIGGER', dev: 'AGENT', test: 'AGENT', done: 'END' },
    [['start', 'dev'], ['dev', 'test'], ['test', 'done', '通过'], ['test', 'dev', '打回']]),
  // 单列里跳过一个节点。
  skip: flow({ start: 'TRIGGER', a: 'AGENT', b: 'AGENT', done: 'END' }, [['start', 'a'], ['a', 'b', '继续'], ['a', 'done', '跳过'], ['b', 'done']]),
  // 一个岗位分到两个人工环节，其中一个打回另一个。
  humans: flow({ start: 'TRIGGER', a: 'AGENT', h1: 'HUMAN', h2: 'HUMAN', h3: 'HUMAN', done: 'END' },
    [['start', 'a'], ['a', 'h1', '甲'], ['a', 'h2', '乙'], ['h1', 'h3'], ['h2', 'done'], ['h3', 'done', '同意'], ['h3', 'h1', '打回']]),
  // 三个分支再汇合。
  fan: flow({ start: 'TRIGGER', a: 'AGENT', b: 'AGENT', c: 'AGENT', d: 'AGENT', done: 'END' },
    [['start', 'a'], ['a', 'b', '一'], ['a', 'c', '二'], ['a', 'd', '三'], ['b', 'done'], ['c', 'done'], ['d', 'done']]),
  // 第二列的岗位连到人工泳道，同行主列有节点。
  across: flow({ start: 'TRIGGER', a: 'AGENT', r: 'AGENT', q: 'AGENT', h: 'HUMAN', done: 'END' },
    [['start', 'a'], ['a', 'r', '主'], ['a', 'q', '旁'], ['r', 'done'], ['q', 'h'], ['h', 'done']]),
}
const segments = item => {
  const points = item.path.slice(1).split(' L').map(point => point.split(' ').map(Number))
  return points.slice(1).map((point, index) => [points[index], point])
}
const inside = ([[x1, y1], [x2, y2]], placed) => Math.max(x1, x2) > placed.x && Math.min(x1, x2) < placed.x + NODE_WIDTH
  && Math.max(y1, y2) > placed.y && Math.min(y1, y2) < placed.y + NODE_HEIGHT

test('layout ranks nodes by the longest forward path and keeps humans in their own lane', () => {
  const layout = layoutFlow(review)
  const at = key => layout.nodes.find(item => item.node.key === key)
  const row = key => Math.round((at(key).y - at('start').y) / (at('gate').y - at('start').y))

  assert.deepEqual(['start', 'gate', 'review', 'accept', 'returned', 'done'].map(row), [0, 1, 2, 3, 3, 4])
  assert.deepEqual(layout.lanes.map(lane => lane.title), ['你', 'Agent'])
  // The human node sits inside the first lane, every other node inside the second.
  assert.ok(at('accept').x + NODE_WIDTH <= layout.lanes[0].width)
  assert.ok(layout.nodes.filter(item => item.node.type !== 'HUMAN').every(item => item.x >= layout.lanes[1].x))
  // The main chain shares a column.
  assert.equal(at('gate').x, at('review').x)
})

test('only the rejection that closes a loop is drawn as a back edge', () => {
  const layout = layoutFlow(review)
  assert.deepEqual(layout.edges.filter(item => item.back).map(item => `${item.edge.from}>${item.edge.to}`), ['accept>review'])
})

for (const [name, definition] of Object.entries(shapes)) {
  test(`${name}: nodes never overlap and everything stays on the canvas`, () => {
    const layout = layoutFlow(definition)
    for (const a of layout.nodes) {
      assert.ok(a.x >= 0 && a.y >= 0 && a.x + NODE_WIDTH <= layout.width && a.y + NODE_HEIGHT <= layout.height, `${a.node.key} leaves the canvas`)
      for (const b of layout.nodes) assert.ok(a === b || a.x !== b.x || a.y !== b.y, `${a.node.key} overlaps ${b.node.key}`)
    }
    for (const item of layout.edges)
      for (const segment of segments(item))
        for (const [x, y] of segment) assert.ok(x >= 0 && x <= layout.width && y >= 0 && y <= layout.height, `${item.edge.from}>${item.edge.to} leaves the canvas at ${x},${y}`)
  })

  test(`${name}: no edge runs through a node it does not connect`, () => {
    const layout = layoutFlow(definition)
    for (const item of layout.edges)
      for (const segment of segments(item))
        for (const placed of layout.nodes)
          assert.ok(placed.node.key === item.edge.from || placed.node.key === item.edge.to || !inside(segment, placed), `${item.edge.from}>${item.edge.to} crosses ${placed.node.key}`)
  })

  test(`${name}: edges and labels stay apart`, () => {
    const layout = layoutFlow(definition)
    // A rejection never retraces the edge it reverses.
    for (const a of layout.edges) for (const b of layout.edges) assert.ok(a === b || a.path !== b.path, `${a.edge.from}>${a.edge.to} retraces ${b.edge.from}>${b.edge.to}`)
    const labelled = layout.edges.filter(item => item.edge.label)
    for (const a of labelled) for (const b of labelled)
      assert.ok(a === b || Math.abs(a.labelX - b.labelX) >= 44 || Math.abs(a.labelY - b.labelY) >= 20, `labels ${a.edge.label} and ${b.edge.label} collide`)
  })
}

test('a flow without human nodes has a single lane', () => {
  const layout = layoutFlow(shapes.rework)
  assert.deepEqual(layout.lanes.map(lane => lane.title), ['Agent'])
  assert.equal(layout.lanes[0].x, 0)
})

test('item and step states map to labels', () => {
  assert.deepEqual(itemState({ status: 'WAITING' }), { label: '待你处理', tone: 'waiting' })
  assert.deepEqual(itemState({ status: 'RUNNING', pending: '' }), { label: '流转中', tone: 'active' })
  assert.deepEqual(itemState({ status: 'RUNNING', pending: 'APPROVAL' }), { label: '待审批', tone: 'waiting' })
  assert.deepEqual(itemState({ status: 'RUNNING', pending: 'ANSWER' }), { label: '待回答', tone: 'waiting' })
  // 已结束的事项即使残留标记也不算待处理。
  assert.equal(needsUser({ status: 'RUNNING', pending: 'ANSWER' }), true)
  assert.equal(needsUser({ status: 'STOPPED', pending: 'ANSWER' }), false)
  assert.deepEqual(itemState({ status: 'STOPPED' }), { label: '已停止', tone: 'muted' })
  assert.equal(stepStateLabel('INTERRUPTED'), '已中断')
  assert.equal(stepStateLabel('SOMETHING'), 'SOMETHING')
  assert.ok(isOpen({ status: 'RUNNING' }) && isOpen({ status: 'WAITING' }) && !isOpen({ status: 'COMPLETED' }))
})

import type { ApprovalMode } from './api'

export type FlowNodeType = 'TRIGGER' | 'AGENT' | 'HUMAN' | 'END'
export interface FlowNode {
  key: string; type: FlowNodeType; name: string; description: string
  modelProfileId: string; reasoningEffort: string | null; approvalMode: ApprovalMode | ''; externalAgentId?: string
}
export interface FlowEdge { from: string; to: string; label: string }
export interface FlowDefinition { nodes: FlowNode[]; edges: FlowEdge[] }
export interface Flow {
  id: string; name: string; description: string; workspaceId: string; definition: FlowDefinition; createdAt: string; revision: number
}
export interface FlowSummary { flow: Flow; running: number; waiting: number; version: string }
export type FlowItemStatus = 'RUNNING' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'STOPPED'
export interface FlowItem {
  id: string; flowId: string; flowName: string; title: string; status: FlowItemStatus; nodeKey: string; detail: string
  // 岗位运行中等你审批命令或外部工具（APPROVAL）、回答提问（ANSWER）；其余情况为空。
  steps: number; pending: '' | 'APPROVAL' | 'ANSWER'; createdAt: string; updatedAt: string
}
export interface FlowSnapshot { flow: Flow; items: FlowItem[]; version: string }
export interface FlowStep {
  id: string; seq: number; nodeKey: string; nodeName: string; nodeType: FlowNodeType; status: string; outcome: string
  summary: string; detail: string; runId: string | null; createdAt: string
}
export interface FlowItemDetail {
  item: FlowItem; input: string; sessionId: string; definition: FlowDefinition; steps: FlowStep[]; options: string[]; retryable: boolean
}
export interface FlowProposal { name: string; definition: FlowDefinition }

export const isOpen = (item: FlowItem) => item.status === 'RUNNING' || item.status === 'WAITING'
/** 需要用户介入：停在节点上等决定，或岗位运行中等审批、等回答。 */
export const needsUser = (item: Pick<FlowItem, 'status' | 'pending'>) => item.status === 'WAITING' || item.status === 'RUNNING' && !!item.pending
export const pendingText = (item: Pick<FlowItem, 'pending'>) => item.pending === 'APPROVAL' ? '岗位在等你审批操作' : '岗位在等你回答问题'

export function itemState(item: Pick<FlowItem, 'status' | 'pending'>): { label: string; tone: 'active' | 'success' | 'muted' | 'danger' | 'waiting' } {
  switch (item.status) {
    case 'RUNNING': return item.pending ? { label: item.pending === 'APPROVAL' ? '待审批' : '待回答', tone: 'waiting' } : { label: '流转中', tone: 'active' }
    case 'WAITING': return { label: '待你处理', tone: 'waiting' }
    case 'COMPLETED': return { label: '已结束', tone: 'success' }
    case 'FAILED': return { label: '已失败', tone: 'danger' }
    default: return { label: '已停止', tone: 'muted' }
  }
}

export const stepStateLabel = (status: string) => ({
  RUNNING: '进行中', WAITING: '等待你', DONE: '完成', FAILED: '失败', CANCELLED: '已停止', INTERRUPTED: '已中断',
})[status] || status

export const NODE_WIDTH = 176
export const NODE_HEIGHT = 74
const COLUMN_GAP = 64
const ROW_GAP = 44
// 泳道内边距同时是绕行连线的通道，必须大于回边的绕行距离。
const LANE_PADDING = 36
const LANE_TOP = 44
const LANE_GAP = 8
const ARROW = 4
// 回边比前向连线绕得更远、端点上移，两者共用一个空隙时不会重合。
const DETOUR = 14
const BACK_DETOUR = 28
const BACK_PORT = -14
// 同一节点同一侧出发的多条连线上下错开的间距。
const PORT_GAP = 12
const CORNER = 8

export interface PlacedNode { node: FlowNode; x: number; y: number }
/** path 是直角折线，供布局校验使用；curve 是同一条线的圆角版本，用于绘制。 */
export interface PlacedEdge { edge: FlowEdge; path: string; curve: string; arrow: string; labelX: number; labelY: number; back: boolean }
export interface Lane { title: string; x: number; width: number; human: boolean }
export interface FlowLayout { nodes: PlacedNode[]; edges: PlacedEdge[]; lanes: Lane[]; width: number; height: number }

type Point = [number, number]

/**
 * 把流程定义排成泳道图：人工节点在左侧「你」泳道，其余节点在右侧，行号为从触发节点出发的最长前向路径，
 * 同一行里通往终点路径最长的节点占主列。打回形成的回边不参与分层，用虚线绕行。
 */
export function layoutFlow(definition: FlowDefinition): FlowLayout {
  const { nodes, edges } = definition
  const byKey = new Map(nodes.map(node => [node.key, node]))
  const next = new Map<string, string[]>()
  for (const edge of edges) next.set(edge.from, [...(next.get(edge.from) || []), edge.to])

  // 深度优先遍历中指向栈内节点的边是回边。
  const back = new Set<string>()
  const state = new Map<string, 1 | 2>()
  const visit = (key: string) => {
    state.set(key, 1)
    for (const to of next.get(key) || []) {
      if (state.get(to) === 1) back.add(`${key}>${to}`)
      else if (!state.has(to)) visit(to)
    }
    state.set(key, 2)
  }
  const trigger = nodes.find(node => node.type === 'TRIGGER') || nodes[0]
  if (trigger) visit(trigger.key)
  for (const node of nodes) if (!state.has(node.key)) visit(node.key)
  const forward = edges.filter(edge => !back.has(`${edge.from}>${edge.to}`))

  // 去掉回边后是无环图，逐轮松弛得到最长路径。
  const rank = new Map(nodes.map(node => [node.key, 0]))
  const height = new Map(nodes.map(node => [node.key, 0]))
  for (let round = 0; round < nodes.length; round++) {
    for (const edge of forward) {
      rank.set(edge.to, Math.max(rank.get(edge.to)!, rank.get(edge.from)! + 1))
      height.set(edge.from, Math.max(height.get(edge.from)!, height.get(edge.to)! + 1))
    }
  }

  // 两条泳道各自按行分列，同一行的多个节点并排。
  const hasHuman = nodes.some(node => node.type === 'HUMAN')
  const column = new Map<string, number>()
  const columns = { human: 1, agent: 1 }
  const rows = Math.max(0, ...rank.values()) + 1
  for (let row = 0; row < rows; row++) {
    for (const lane of ['human', 'agent'] as const) {
      const inRow = nodes.filter(node => rank.get(node.key) === row && (node.type === 'HUMAN') === (lane === 'human'))
        .sort((a, b) => height.get(b.key)! - height.get(a.key)!)
      inRow.forEach((node, index) => column.set(node.key, index))
      columns[lane] = Math.max(columns[lane], inRow.length)
    }
  }

  const laneWidth = (count: number) => LANE_PADDING * 2 + count * NODE_WIDTH + (count - 1) * COLUMN_GAP
  const humanWidth = hasHuman ? laneWidth(columns.human) : 0
  const agentX = hasHuman ? humanWidth + LANE_GAP : 0
  const agentWidth = laneWidth(columns.agent)
  const placed = new Map<string, PlacedNode>()
  for (const node of nodes) {
    const x = (node.type === 'HUMAN' ? 0 : agentX) + LANE_PADDING + column.get(node.key)! * (NODE_WIDTH + COLUMN_GAP)
    placed.set(node.key, { node, x, y: LANE_TOP + rank.get(node.key)! * (NODE_HEIGHT + ROW_GAP) })
  }

  const drawn = edges.filter(edge => byKey.has(edge.from) && byKey.has(edge.to)).map(edge => {
    const from = placed.get(edge.from)!
    const to = placed.get(edge.to)!
    const isBack = back.has(`${edge.from}>${edge.to}`)
    const others = [...placed.values()].filter(item => item !== from && item !== to)
    return { edge, from, to, others, isBack, points: route(from, to, others, isBack) }
  })
  // 同一节点同一侧出发的多条连线上下错开。向下的连线里走得远的在上、向上的走得远的在下，错开后互不交叉。
  const ports = new Map<string, typeof drawn>()
  for (const item of drawn) {
    const [[x, y], [, nextY]] = item.points
    if (item.from.y === item.to.y || y !== nextY || x !== item.from.x && x !== item.from.x + NODE_WIDTH) continue
    const key = `${item.edge.from}:${x}:${y}`
    ports.set(key, [...(ports.get(key) || []), item])
  }
  for (const group of ports.values()) {
    if (group.length < 2) continue
    const order = (item: typeof drawn[number]) => {
      const reach = Math.abs(item.points[1][0] - item.points[0][0])
      return item.to.y > item.from.y ? 10000 - reach : reach - 10000
    }
    group.sort((a, b) => order(a) - order(b)).forEach((item, index) => {
      item.points = route(item.from, item.to, item.others, item.isBack, (index - (group.length - 1) / 2) * PORT_GAP)
    })
  }
  const labels: Point[] = []
  const routed = drawn.map(({ edge, points, isBack }) => {
    // 标签依次尝试几个位置，避开已经放好的标签。
    const spots = labelSpots(points, isBack, edge.label)
    const [lx, ly] = spots.find(([x, y]) => !labels.some(([ox, oy]) => Math.abs(ox - x) < 44 && Math.abs(oy - y) < 20)) || spots[0]
    if (edge.label) labels.push([lx, ly])
    return { edge, path: 'M' + points.map(point => point.join(' ')).join(' L'), curve: roundedPath(points), arrow: arrowHead(points), labelX: lx, labelY: ly, back: isBack }
  })
  const lanes: Lane[] = []
  if (hasHuman) lanes.push({ title: '你', x: 0, width: humanWidth, human: true })
  lanes.push({ title: 'Agent', x: agentX, width: agentWidth, human: false })
  return {
    nodes: [...placed.values()], edges: routed, lanes,
    width: agentX + agentWidth, height: LANE_TOP + rows * NODE_HEIGHT + (rows - 1) * ROW_GAP + LANE_PADDING,
  }
}

/** 折线是否穿过其他节点；线段都是水平或竖直的。 */
function crosses(points: Point[], others: PlacedNode[]) {
  return points.slice(1).some(([x2, y2], index) => {
    const [x1, y1] = points[index]
    return others.some(node => Math.max(x1, x2) > node.x && Math.min(x1, x2) < node.x + NODE_WIDTH
      && Math.max(y1, y2) > node.y && Math.min(y1, y2) < node.y + NODE_HEIGHT)
  })
}

/**
 * 两个节点之间的折线，按顺序取第一条不穿过其他节点的走法：相邻行直连、同行横连、一次直角拐弯，
 * 否则贴着目标列旁的通道走，或先进入行间空隙再走通道。回边优先走通道，不与反向的前向连线重合。
 * shift 是从起点侧面出发时端点的上下偏移，同行横连不使用。
 */
function route(from: PlacedNode, to: PlacedNode, others: PlacedNode[], back: boolean, shift = 0): Point[] {
  const cx = (node: PlacedNode) => node.x + NODE_WIDTH / 2
  const cy = (node: PlacedNode) => node.y + NODE_HEIGHT / 2 + (back ? BACK_PORT : 0) + (node === from && from.y !== to.y ? shift : 0)
  const side = (node: PlacedNode, right: boolean): Point => [right ? node.x + NODE_WIDTH : node.x, cy(node)]
  const detour = back ? BACK_DETOUR : DETOUR
  const down = to.y > from.y
  const right = to.x > from.x
  const below = from.y + NODE_HEIGHT + ROW_GAP / 2
  const candidates: Point[][] = []
  if (from.y === to.y) {
    candidates.push([side(from, right), side(to, !right)])
    // 中间隔着节点时从下方的行间空隙绕过去。
    candidates.push([[cx(from), from.y + NODE_HEIGHT], [cx(from), below], [cx(to), below], [cx(to), to.y + NODE_HEIGHT]])
  } else if (from.x === to.x) {
    // 同列向下且中间没有节点时直连，跨行也一样。
    if (!back && down) candidates.push([[cx(from), from.y + NODE_HEIGHT], [cx(to), to.y]])
    const around = (onRight: boolean): Point[] => {
      const x = onRight ? from.x + NODE_WIDTH + detour : from.x - detour
      return [side(from, onRight), [x, cy(from)], [x, cy(to)], side(to, onRight)]
    }
    // 前向跨行从右侧绕，打回从左侧绕。
    candidates.push(around(!back), around(back))
  } else {
    // 通道在目标节点的一侧；连线从起点朝向通道的一侧出发。
    const channel = (enterRight: boolean): Point[] => {
      const x = enterRight ? to.x + NODE_WIDTH + detour : to.x - detour
      return [side(from, x > from.x), [x, cy(from)], [x, cy(to)], side(to, enterRight)]
    }
    const viaGap = (enterRight: boolean): Point[] => {
      const x = enterRight ? to.x + NODE_WIDTH + detour : to.x - detour
      const y = down ? below : from.y - ROW_GAP / 2
      return [[cx(from), down ? from.y + NODE_HEIGHT : from.y], [cx(from), y], [x, y], [x, cy(to)], side(to, enterRight)]
    }
    // 向下：先横向走到目标列，再竖直进入目标顶部；向上：先竖直回到目标所在行，再横向进入目标侧面。
    const elbow: Point[] = down ? [side(from, right), [cx(to), cy(from)], [cx(to), to.y]] : [[cx(from), from.y], [cx(from), cy(to)], side(to, !right)]
    if (back) candidates.push(channel(!right), viaGap(!right), channel(right), viaGap(right), elbow)
    else candidates.push(elbow, channel(!right), viaGap(!right), viaGap(right), channel(right))
  }
  return candidates.find(points => !crosses(points, others)) || candidates[0]
}

/** 把折线的拐角画成圆角；线段太短时圆角随之缩小。 */
function roundedPath(points: Point[]) {
  const toward = ([x1, y1]: Point, [x2, y2]: Point, distance: number) => {
    const length = Math.hypot(x2 - x1, y2 - y1) || 1
    return `${x1 + (x2 - x1) / length * distance} ${y1 + (y2 - y1) / length * distance}`
  }
  const span = (a: Point, b: Point) => Math.hypot(b[0] - a[0], b[1] - a[1])
  let path = `M${points[0].join(' ')}`
  for (let index = 1; index < points.length - 1; index++) {
    const radius = Math.min(CORNER, span(points[index - 1], points[index]) / 2, span(points[index], points[index + 1]) / 2)
    path += ` L${toward(points[index], points[index - 1], radius)} Q${points[index].join(' ')} ${toward(points[index], points[index + 1], radius)}`
  }
  return `${path} L${points[points.length - 1].join(' ')}`
}

/**
 * 标签的候选位置：前向连线优先靠近起点，分支在起点处就能读出；回边优先放在最长一段的中点，离开节点。
 * 横向出发时按文字宽度让开节点边；第一段放不下标签时改用最长一段的中点。
 */
function labelSpots(points: Point[], back: boolean, label: string): Point[] {
  const along = ([x1, y1]: Point, [x2, y2]: Point, distance: number): Point => {
    const length = Math.hypot(x2 - x1, y2 - y1) || 1
    const offset = Math.min(length / 2, distance)
    return [x1 + (x2 - x1) / length * offset, y1 + (y2 - y1) / length * offset]
  }
  let longest = 1
  for (let index = 2; index < points.length; index++) {
    const length = (at: number) => Math.hypot(points[at][0] - points[at - 1][0], points[at][1] - points[at - 1][1])
    if (length(index) > length(longest)) longest = index
  }
  const reach = points[0][1] === points[1][1] ? 14 + label.length * 5.5 : ROW_GAP / 2
  const near = along(points[0], points[1], reach)
  const middle = along(points[longest - 1], points[longest], Infinity)
  const end = along(points[points.length - 1], points[points.length - 2], 34)
  const cramped = Math.hypot(points[1][0] - points[0][0], points[1][1] - points[0][1]) < reach
  return back || cramped ? [middle, near, end] : [near, middle, end]
}

function arrowHead(points: Point[]) {
  const [x1, y1] = points[points.length - 2]
  const [x2, y2] = points[points.length - 1]
  const length = Math.hypot(x2 - x1, y2 - y1) || 1
  const ux = (x2 - x1) / length
  const uy = (y2 - y1) / length
  const bx = x2 - ux * ARROW * 1.6
  const by = y2 - uy * ARROW * 1.6
  return `M${x2} ${y2} L${bx - uy * ARROW} ${by + ux * ARROW} L${bx + uy * ARROW} ${by - ux * ARROW} Z`
}

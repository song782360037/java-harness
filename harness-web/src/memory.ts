/** 长期记忆和图谱接口的数据结构。 */
export interface MemoryEntry {
  id: string; workspaceId: string; title: string; content: string; kind: string; tags: string[]
  status: string; sourceSessionId: string | null; sourceRunId: string | null; sourceExcerpt: string
  createdAt: string; updatedAt: string; revision: number
}
export interface MemoryRelation { fromId: string; toId: string; label: string }
export interface MemoryPage { items: MemoryEntry[]; relations: MemoryRelation[]; total: number }
export interface MemoryUsage { memoryId: string; title: string; content: string; revision: number }
export interface MemorySettings { enabled: boolean; modelProfileId: string; revision: number }
export interface MemoryState {
  settings: MemorySettings
  progress: { total: number; pending: number; running: number; completed: number; failed: number; skipped: number }
}
export const memoryKinds: Record<string, string> = { FACT: '项目事实', DECISION: '决策', PREFERENCE: '偏好', SOLUTION: '解决方案' }
export const memoryStatuses: Record<string, string> = { CANDIDATE: '旧版待确认', ACTIVE: '已生效', ARCHIVED: '已归档' }
// 主题色变量在 memories.css 中按明暗主题分别定义。
export const memoryColors = [1, 2, 3, 4, 5, 6].map(index => `var(--memory-${index})`)

/** 按主题分格排布，密集簇保留命中间距，由相机整体适配画布。 */
export function memoryLayout(entries: MemoryEntry[], width = 1200, height = 800) {
  const groups = [...new Set(entries.map(item => item.tags[0] || memoryKinds[item.kind] || item.kind))].sort()
  const columns = Math.max(1, Math.ceil(Math.sqrt(groups.length * width / Math.max(1, height))))
  const rows = Math.max(1, Math.ceil(groups.length / columns))
  const largestGroup = Math.max(1, ...groups.map(name => entries.filter(item => (item.tags[0] || memoryKinds[item.kind] || item.kind) === name).length))
  const minimumSpan = largestGroup === 1 ? 80 : Math.sqrt(largestGroup) * 46 + 48
  const cellWidth = Math.max(minimumSpan, (width - 48) / columns)
  const cellHeight = Math.max(minimumSpan, (height - 140) / rows)
  const centers = groups.map((name, index) => {
    return { name, x: 24 + (index % columns + .5) * cellWidth,
      y: 52 + (Math.floor(index / columns) + .5) * cellHeight, labelY: 52 + Math.floor(index / columns) * cellHeight,
      color: memoryColors[index % memoryColors.length]! }
  })
  const nodes = entries.slice().sort((a, b) => a.id.localeCompare(b.id)).map(item => {
    const group = item.tags[0] || memoryKinds[item.kind] || item.kind
    const center = centers.find(value => value.name === group)!
    return { ...item, group, color: center.color, x: center.x, y: center.y }
  })
  for (const center of centers) {
    const members = nodes.filter(item => item.group === center.name)
    members.forEach((item, index) => {
      const radius = members.length === 1 ? 0 : Math.sqrt(index + 1) * 23
      item.x += Math.cos(index * 2.39996) * radius
      item.y += Math.sin(index * 2.39996) * radius
    })
  }
  return { centers, nodes }
}

/** 初始视角完整显示节点及主题标题，底部为缩放和图例留出空间。 */
export function memoryCamera(layout: ReturnType<typeof memoryLayout>, width: number, height: number) {
  if (!layout.nodes.length) return { x: 0, y: 0, zoom: 1 }
  const left = Math.min(...layout.nodes.map(node => node.x - 24), ...layout.centers.map(center => center.x - 40))
  const right = Math.max(...layout.nodes.map(node => node.x + 24), ...layout.centers.map(center => center.x + 40))
  const top = Math.min(...layout.nodes.map(node => node.y - 24), ...layout.centers.map(center => center.labelY - 18))
  const bottom = Math.max(...layout.nodes.map(node => node.y + 24))
  const zoom = Math.min(1, Math.max(1, width - 48) / (right - left), Math.max(1, height - 140) / (bottom - top))
  return { x: width / 2 - (left + right) / 2 * zoom, y: 44 - top * zoom, zoom }
}

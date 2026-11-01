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

/** 按首个标签或类型分簇，稳定排序使刷新和筛选后的布局可复现。 */
export function memoryLayout(entries: MemoryEntry[], width = 1200, height = 800) {
  const groups = [...new Set(entries.map(item => item.tags[0] || memoryKinds[item.kind] || item.kind))].sort()
  const centers = groups.map((name, index) => {
    const angle = index / Math.max(1, groups.length) * Math.PI * 2 - Math.PI / 2
    return { name, x: width / 2 + (groups.length === 1 ? 0 : Math.cos(angle) * width * 0.3),
      y: height / 2 + (groups.length === 1 ? 0 : Math.sin(angle) * height * 0.3), color: memoryColors[index % memoryColors.length]! }
  })
  const nodes = entries.slice().sort((a, b) => a.id.localeCompare(b.id)).map(item => {
    const group = item.tags[0] || memoryKinds[item.kind] || item.kind
    const center = centers.find(value => value.name === group)!
    return { ...item, group, color: center.color, x: center.x, y: center.y }
  })
  for (const center of centers) {
    nodes.filter(item => item.group === center.name).forEach((item, index) => {
      const radius = 28 + Math.sqrt(index) * 23
      item.x += Math.cos(index * 2.39996) * radius
      item.y += Math.sin(index * 2.39996) * radius
    })
  }
  return { centers, nodes }
}

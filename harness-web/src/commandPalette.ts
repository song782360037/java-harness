import type { SessionSearchHit, Workspace } from './api'

export type PaletteGroup = 'action' | 'page' | 'workspace' | 'session' | 'content'

export interface CommandPaletteItem {
  id: string
  group: PaletteGroup
  title: string
  subtitle?: string
  keywords?: string
  icon: string
  color?: string
  badge?: string
  shortcut?: string
}

export const PALETTE_GROUP_LABELS: Record<PaletteGroup, string> = {
  action: '快捷操作',
  page: '前往页面',
  workspace: '工作区新建对话',
  session: '历史会话',
  content: '对话正文命中',
}

/** 识别全局命令面板快捷键：Ctrl+K（Windows/Linux）或 Cmd+K（macOS）。 */
export function isPaletteShortcut(event: { key: string; ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean; isComposing?: boolean }): boolean {
  if (event.isComposing || event.altKey || event.shiftKey) return false
  return (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k'
}

/** 支持空格分隔的多词模糊匹配，标题前缀命中排在最前。 */
export function filterPaletteItems(items: CommandPaletteItem[], query: string): CommandPaletteItem[] {
  const tokens = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (!tokens.length) return items
  const scored: { item: CommandPaletteItem; score: number }[] = []
  for (const item of items) {
    const title = item.title.toLowerCase()
    const haystack = `${title} ${(item.subtitle || '').toLowerCase()} ${(item.keywords || '').toLowerCase()}`
    if (!tokens.every(token => haystack.includes(token))) continue
    const prefix = tokens.every(token => title.startsWith(token)) ? 0
      : tokens.every(token => title.includes(token)) ? 1 : 2
    scored.push({ item, score: prefix })
  }
  scored.sort((left, right) => left.score - right.score)
  return scored.map(entry => entry.item)
}

/** 合并后端跨会话全文检索结果：已在本地命中且为正文匹配时补充摘要，否则追加到“对话正文命中”分组。 */
export function mergeContentHits(
  localItems: CommandPaletteItem[],
  hits: SessionSearchHit[],
  workspaces: Workspace[],
): CommandPaletteItem[] {
  if (!hits.length) return localItems
  const workspaceNames = new Map(workspaces.map(workspace => [workspace.id, workspace.name]))
  const hitBySession = new Map(hits.map(hit => [hit.session.id, hit]))
  const seenSessionIds = new Set<string>()
  const updated = localItems.map(item => {
    if (!item.id.startsWith('session:')) return item
    const sessionId = item.id.slice('session:'.length)
    seenSessionIds.add(sessionId)
    const hit = hitBySession.get(sessionId)
    if (!hit || hit.matchedIn === 'title' || !hit.snippet) return item
    const prefix = hit.matchedIn === 'prompt' ? '提问：' : '回复：'
    return { ...item, subtitle: `${prefix}${hit.snippet}` }
  })
  const extra: CommandPaletteItem[] = []
  for (const hit of hits) {
    if (seenSessionIds.has(hit.session.id)) continue
    seenSessionIds.add(hit.session.id)
    const workspace = workspaceNames.get(hit.session.workspaceId)
    const prefix = hit.matchedIn === 'prompt' ? '提问：' : hit.matchedIn === 'output' ? '回复：' : ''
    const subtitle = hit.snippet && hit.matchedIn !== 'title'
      ? `${workspace ? `${workspace} · ` : ''}${prefix}${hit.snippet}`
      : workspace || '历史会话'
    extra.push({
      id: `session:${hit.session.id}`,
      group: hit.matchedIn === 'title' ? 'session' : 'content',
      title: hit.session.title,
      subtitle,
      icon: 'chat',
    })
  }
  return [...updated, ...extra]
}

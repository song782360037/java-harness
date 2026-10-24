export type ExtensionKind = 'plugin' | 'skill' | 'connector'
export interface ExtensionItem {
  id: string; kind: ExtensionKind; title: string; description: string; category: string; icon: string
  enabled: boolean; editable: boolean; revision: number; official: boolean; featured: boolean; version: string; installationId: string; parentName: string; componentId: string; issue: string
}
/** A successful mutation must not be reported as failed when only the following list refresh fails. */
export async function saveAndRefresh<T>(save: () => Promise<T>, refresh: () => Promise<unknown>) {
  const value = await save()
  let refreshError = ''
  try { await refresh() } catch (cause) { refreshError = cause instanceof Error ? cause.message : '暂时无法读取扩展列表' }
  return { value, refreshError }
}
/** Match the same catalog fields in discovery and installed views. */
export function filterExtensions(items: ExtensionItem[], kind: ExtensionKind, query: string, category: string, installed: boolean, sort: string) {
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return items.filter(item => item.kind === kind && (!installed || item.installationId)
    && (category === '全部' || (category === '官方精选' ? item.official : category === '自定义' ? !item.official : item.category === category))
    && terms.every(term => `${item.title} ${item.description} ${item.category} ${item.parentName}`.toLocaleLowerCase().includes(term)))
    .sort((a, b) => sort === 'name' ? a.title.localeCompare(b.title, 'zh-CN') : Number(b.featured) - Number(a.featured))
}

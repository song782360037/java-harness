<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, type Session, type SessionSearchHit, type Workspace } from './api'
import { filterPaletteItems, mergeContentHits, PALETTE_GROUP_LABELS, type CommandPaletteItem, type PaletteGroup } from './commandPalette'
import { modifierKeyLabel } from './preferences'
import { resolvedTheme, setTheme, themePreference, type ThemePreference } from './theme'
import UiIcon from './UiIcon.vue'

const props = defineProps<{
  sessions: Session[]
  workspaces: Workspace[]
  selectedId: string
  waitingFlowCount: number
  sidebarCollapsed: boolean
  canExport: boolean
  canExplore: boolean
  explorerOpen: boolean
  navigationDisabled: boolean
}>()

const emit = defineEmits<{
  close: []
  createSession: []
  createWorkspaceSession: [workspaceId: string]
  selectSession: [id: string]
  openAutomations: []
  openMemories: []
  openGroups: []
  openFlows: []
  openSettings: [section?: 'general' | 'models' | 'workspace' | 'approval' | 'memory' | 'updates' | 'plugins' | 'agents']
  toggleSidebar: []
  toggleExplorer: []
  exportMarkdown: []
}>()

const dialog = ref<HTMLDialogElement>()
const input = ref<HTMLInputElement>()
const list = ref<HTMLElement>()
const query = ref('')
const activeIndex = ref(0)
const searching = ref(false)
const contentHits = ref<SessionSearchHit[]>([])
let searchTimer: number | undefined
let searchRevision = 0

const nextTheme = computed<{ value: ThemePreference; label: string; icon: string }>(() => {
  if (themePreference.value === 'system') {
    return resolvedTheme.value === 'dark'
      ? { value: 'light', label: '切换到明亮主题', icon: 'sun' }
      : { value: 'dark', label: '切换到深色主题', icon: 'moon' }
  }
  return themePreference.value === 'dark'
    ? { value: 'light', label: '切换到明亮主题', icon: 'sun' }
    : { value: 'dark', label: '切换到深色主题', icon: 'moon' }
})

const baseItems = computed<CommandPaletteItem[]>(() => {
  const items: CommandPaletteItem[] = [
    { id: 'action:new-session', group: 'action', title: '新建对话', subtitle: '开始新的空白任务', keywords: 'new chat create 新建 任务 对话', icon: 'plus' },
    ...(props.canExport
      ? [{ id: 'action:export-md', group: 'action' as const, title: '导出当前会话为 Markdown', subtitle: '将当前会话的提问与回复保存为 .md 文件', keywords: 'export markdown download md 导出 下载 归档', icon: 'download' }]
      : []),
    ...(props.canExplore
      ? [{ id: 'action:toggle-explorer', group: 'action' as const, title: props.explorerOpen ? '关闭工作区文件浏览栏' : '打开工作区文件浏览栏', subtitle: '浏览项目目录树并预览源码', keywords: 'files tree explorer code preview 文件 目录 代码 预览', icon: 'folder' }]
      : []),
    { id: 'action:toggle-theme', group: 'action', title: nextTheme.value.label, subtitle: `当前主题：${themePreference.value === 'system' ? '跟随系统' : themePreference.value === 'dark' ? '深色' : '明亮'}`, keywords: 'theme dark light 主题 明亮 深色 夜间', icon: nextTheme.value.icon },
    { id: 'action:toggle-sidebar', group: 'action', title: props.sidebarCollapsed ? '展开侧栏' : '收起侧栏', subtitle: '切换左侧会话导航栏显示状态', keywords: 'sidebar panel 侧栏 折叠 展开', icon: 'panel' },
    { id: 'page:flows', group: 'page', title: '打开流水线', subtitle: '查看与处理固定研发泳道流水线', keywords: 'flows pipeline 流水线 泳道', icon: 'branch', badge: props.waitingFlowCount ? String(props.waitingFlowCount) : undefined },
    { id: 'page:groups', group: 'page', title: '打开群聊', subtitle: '多角色 AI 群聊与像素协作工坊', keywords: 'groups studio 群聊 协作 工坊', icon: 'users' },
    { id: 'page:memories', group: 'page', title: '打开长期记忆', subtitle: '浏览主题知识图谱与记忆条目', keywords: 'memory graph 记忆 图谱', icon: 'memory' },
    { id: 'page:automations', group: 'page', title: '打开自动化', subtitle: '管理定时任务与自动触发规则', keywords: 'automations cron schedule 自动化 定时', icon: 'clock' },
    { id: 'page:settings:models', group: 'page', title: '模型与供应商设置', subtitle: '配置模型名称、API Key 与思考深度', keywords: 'models provider api key 模型 设置 配置', icon: 'reasoning' },
    { id: 'page:settings:general', group: 'page', title: '打开设置与偏好', subtitle: '外观、快捷键、工作区、审批与扩展管理', keywords: 'settings preferences 设置 偏好 扩展 插件', icon: 'settings' },
  ]
  for (const workspace of props.workspaces) {
    items.push({
      id: `workspace:${workspace.id}`,
      group: 'workspace',
      title: workspace.name,
      subtitle: workspace.directories.join(' · '),
      keywords: `workspace project 工作区 项目 ${workspace.directories.join(' ')}`,
      icon: workspace.icon || 'folder',
      color: workspace.color,
    })
  }
  const workspaceNames = new Map(props.workspaces.map(workspace => [workspace.id, workspace.name]))
  const limit = query.value.trim() ? props.sessions.length : 12
  for (const session of props.sessions.slice(0, limit)) {
    items.push({
      id: `session:${session.id}`,
      group: 'session',
      title: session.title,
      subtitle: workspaceNames.get(session.workspaceId) || session.workspace || '最近会话',
      keywords: `${workspaceNames.get(session.workspaceId) || ''} ${session.workspace}`,
      icon: 'chat',
      badge: session.id === props.selectedId ? '当前' : undefined,
    })
  }
  return items
})

const filteredItems = computed(() => {
  const local = filterPaletteItems(baseItems.value, query.value)
  return query.value.trim() ? mergeContentHits(local, contentHits.value, props.workspaces) : local
})

const groupedItems = computed(() => {
  const order: PaletteGroup[] = ['action', 'page', 'workspace', 'session', 'content']
  const buckets = new Map<PaletteGroup, CommandPaletteItem[]>()
  for (const item of filteredItems.value) {
    const bucket = buckets.get(item.group) || []
    bucket.push(item)
    buckets.set(item.group, bucket)
  }
  return order
    .filter(group => buckets.has(group))
    .map(group => ({ group, label: PALETTE_GROUP_LABELS[group], items: buckets.get(group)! }))
})

const flatItems = computed(() => groupedItems.value.flatMap(section => section.items))

watch(query, value => {
  activeIndex.value = 0
  clearTimeout(searchTimer)
  const trimmed = value.trim()
  if (!trimmed) {
    searchRevision++
    searching.value = false
    contentHits.value = []
    return
  }
  const currentRevision = ++searchRevision
  searching.value = true
  searchTimer = window.setTimeout(async () => {
    try {
      const hits = await api<SessionSearchHit[]>(`/sessions/search?q=${encodeURIComponent(trimmed)}&limit=15`)
      if (currentRevision === searchRevision) contentHits.value = hits
    } catch {
      if (currentRevision === searchRevision) contentHits.value = []
    } finally {
      if (currentRevision === searchRevision) searching.value = false
    }
  }, 180)
})

watch(flatItems, items => {
  if (activeIndex.value >= items.length) activeIndex.value = Math.max(0, items.length - 1)
})

function itemIndex(id: string) {
  return flatItems.value.findIndex(item => item.id === id)
}

function close() {
  dialog.value?.close()
}

function runItem(item: CommandPaletteItem) {
  close()
  if (item.id === 'action:new-session') { if (!props.navigationDisabled) emit('createSession'); return }
  if (item.id === 'action:export-md') { emit('exportMarkdown'); return }
  if (item.id === 'action:toggle-theme') { setTheme(nextTheme.value.value); return }
  if (item.id === 'action:toggle-sidebar') { emit('toggleSidebar'); return }
  if (item.id === 'action:toggle-explorer') { emit('toggleExplorer'); return }
  if (item.id === 'page:flows') { emit('openFlows'); return }
  if (item.id === 'page:groups') { emit('openGroups'); return }
  if (item.id === 'page:memories') { emit('openMemories'); return }
  if (item.id === 'page:automations') { emit('openAutomations'); return }
  if (item.id === 'page:settings:models') { emit('openSettings', 'models'); return }
  if (item.id === 'page:settings:general') { emit('openSettings', 'general'); return }
  if (item.id.startsWith('workspace:')) { if (!props.navigationDisabled) emit('createWorkspaceSession', item.id.slice('workspace:'.length)); return }
  if (item.id.startsWith('session:')) { if (!props.navigationDisabled) emit('selectSession', item.id.slice('session:'.length)) }
}

function onKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    if (!flatItems.value.length) return
    activeIndex.value = (activeIndex.value + 1) % flatItems.value.length
    void nextTick(scrollActiveIntoView)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    if (!flatItems.value.length) return
    activeIndex.value = (activeIndex.value - 1 + flatItems.value.length) % flatItems.value.length
    void nextTick(scrollActiveIntoView)
  } else if (event.key === 'Enter') {
    const item = flatItems.value[activeIndex.value]
    if (item) { event.preventDefault(); runItem(item) }
  }
}

function scrollActiveIntoView() {
  const active = list.value?.querySelector<HTMLElement>('.palette-item.is-active')
  active?.scrollIntoView({ block: 'nearest' })
}

function onBackdropClick(event: MouseEvent) {
  if (event.target === dialog.value) close()
}

onMounted(() => {
  dialog.value?.showModal()
  void nextTick(() => input.value?.focus())
})

onUnmounted(() => {
  clearTimeout(searchTimer)
})
</script>

<template>
  <dialog ref="dialog" class="command-palette-dialog" aria-label="快速命令与全文搜索" @close="emit('close')" @click="onBackdropClick" @keydown="onKeydown">
    <div class="command-palette">
      <div class="palette-search">
        <UiIcon name="search" :size="17" />
        <input
          ref="input"
          v-model="query"
          type="text"
          placeholder="搜索会话内容、工作区、页面或命令…"
          aria-label="搜索命令或会话内容"
          autocomplete="off"
        />
        <span v-if="searching" class="loading-indicator" aria-label="正在全文检索"></span>
        <button v-else-if="query" type="button" class="icon-button" aria-label="清空搜索" @click="query = ''; input?.focus()"><UiIcon name="close" :size="14" /></button>
        <kbd class="palette-esc">Esc</kbd>
      </div>
      <div ref="list" class="palette-results" role="listbox" aria-label="匹配结果">
        <template v-for="section in groupedItems" :key="section.group">
          <div class="palette-group-label">{{ section.label }}</div>
          <button
            v-for="item in section.items"
            :key="item.id"
            type="button"
            role="option"
            class="palette-item"
            :class="{ 'is-active': itemIndex(item.id) === activeIndex }"
            :aria-selected="itemIndex(item.id) === activeIndex"
            @mouseenter="activeIndex = itemIndex(item.id)"
            @click="runItem(item)"
          >
            <UiIcon :name="item.icon" :size="16" :style="item.color ? { color: item.color } : undefined" />
            <span class="palette-item-copy">
              <strong>{{ item.title }}</strong>
              <small v-if="item.subtitle">{{ item.subtitle }}</small>
            </span>
            <span v-if="item.badge" class="palette-badge">{{ item.badge }}</span>
          </button>
        </template>
        <div v-if="!flatItems.length && !searching" class="palette-empty">
          <UiIcon name="search" :size="20" />
          <p>没有匹配的命令、工作区或会话内容</p>
        </div>
      </div>
      <footer class="palette-footer">
        <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
        <span><kbd>Enter</kbd> 执行</span>
        <span><kbd>{{ modifierKeyLabel }} K</kbd> 随时唤起</span>
      </footer>
    </div>
  </dialog>
</template>

<style scoped>
.command-palette-dialog {
  width: min(620px, calc(100vw - 32px));
  max-height: min(72vh, 560px);
  margin: 11vh auto auto;
  padding: 0;
  border: 0.5px solid var(--border-strong);
  border-radius: var(--radius-xl);
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--popover-shadow);
  overflow: hidden;
  opacity: 1;
  transform: none;
  transition: none;
}
.command-palette-dialog[open] {
  opacity: 1;
  transform: none;
  animation: pop-in .15s ease-out;
}
.command-palette-dialog::backdrop {
  background: var(--scrim);
  backdrop-filter: blur(4px);
  opacity: 1;
  transition: none;
}
.command-palette {
  display: flex;
  flex-direction: column;
  max-height: min(72vh, 560px);
  background: var(--surface);
}
.palette-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 0.5px solid var(--border);
  color: var(--muted);
}
.palette-search > .ui-icon {
  color: var(--accent);
}
.palette-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font-size: 15px;
  outline: none;
}
.palette-search input:focus-visible {
  outline: none;
}
.palette-search input::placeholder {
  color: var(--placeholder);
}
.palette-esc,
.palette-footer kbd {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  padding: 2px 6px;
  border: 0.5px solid var(--border-strong);
  border-radius: 5px;
  background: var(--surface);
  color: var(--muted);
  font-family: inherit;
  font-size: 11px;
  line-height: 1.3;
  box-shadow: 0 1px 0 var(--border);
}
.palette-results {
  flex: 1;
  overflow-y: auto;
  padding: 6px 8px 10px;
}
.palette-group-label {
  padding: 10px 10px 5px;
  font-size: 11px;
  font-weight: 600;
  color: var(--faint);
  letter-spacing: 0.04em;
}
.palette-item {
  position: relative;
  width: 100%;
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}
.palette-item > .ui-icon {
  color: var(--muted);
}
.palette-item.is-active {
  background: var(--selected);
}
.palette-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  bottom: 7px;
  width: 3px;
  border-radius: 3px;
  background: var(--accent);
}
.palette-item.is-active > .ui-icon {
  color: var(--accent);
}
.palette-item-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.palette-item-copy strong {
  font-size: var(--font-body);
  font-weight: 550;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.palette-item-copy small {
  font-size: 12px;
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.palette-badge {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--subtle);
  border: 0.5px solid var(--border);
  font-size: 11px;
  font-weight: 500;
  color: var(--muted);
  flex-shrink: 0;
}
.palette-item.is-active .palette-badge {
  background: var(--surface);
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 28%, var(--border));
}
.palette-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 36px 16px;
  color: var(--muted);
  font-size: 13px;
}
.palette-empty p {
  margin: 0;
}
.palette-footer {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 9px 14px;
  border-top: 0.5px solid var(--border);
  background: var(--subtle);
  color: var(--muted);
  font-size: 11px;
}
.palette-footer span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>

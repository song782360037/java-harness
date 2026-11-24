<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import type { Session, Workspace } from './api'
import SessionDeleteDialog from './SessionDeleteDialog.vue'
import SessionRenameDialog from './SessionRenameDialog.vue'
import SidebarResize from './SidebarResize.vue'
import UiIcon from './UiIcon.vue'

const props = defineProps<{
  sessions: Session[]
  workspaces: Workspace[]
  selectedId: string
  activePage: 'chat' | 'automations' | 'memories' | 'groups' | 'flows'
  waitingFlowCount: number
  collapsed: boolean
  width: number
  isMobile: boolean
  showSettings: boolean
  loading: boolean
  submitting: boolean
  settingsBusy: boolean
  workspaceChanging: boolean
  currentRunning: boolean
  hasPending: boolean
}>()

const emit = defineEmits<{
  'update:collapsed': [value: boolean]
  'update:width': [value: number]
  createSession: []
  createWorkspaceSession: [workspaceId: string]
  selectSession: [id: string]
  openAutomations: []
  openMemories: []
  openGroups: []
  openFlows: []
  openSettings: []
  sessionRenamed: [session: Session]
  sessionDeleted: [session: { id: string }]
}>()

const sessionSearch = ref('')
const collapsedGroups = ref(new Set<string>())
const sessionMenuId = ref('')
const sessionMenuElement = ref<HTMLElement>()
const sessionMenuPosition = ref<Record<string, string>>({})
const sessionMenuSession = computed(() => props.sessions.find(session => session.id === sessionMenuId.value))
let sessionMenuTrigger: HTMLElement | null = null

const renamingSession = ref<Session | null>(null)
let renameTrigger: HTMLElement | null = null
const deletingSession = ref<Session | null>(null)
let deleteTrigger: HTMLElement | null = null

const sessionGroups = computed(() => {
  const groups = new Map<string, { label: string; icon: string; color: string; directories: string[]; sessions: Session[] }>()
  const query = sessionSearch.value.toLowerCase()
  for (const workspace of props.workspaces) {
    if (!query || `${workspace.name} ${workspace.directories.join(' ')}`.toLowerCase().includes(query))
      groups.set(workspace.id, { label: workspace.name, icon: workspace.icon, color: workspace.color, directories: workspace.directories, sessions: [] })
  }
  for (const session of props.sessions) {
    const workspace = props.workspaces.find(value => value.id === session.workspaceId)
    if (!`${session.title} ${workspace?.name || ''} ${session.workspace}`.toLowerCase().includes(query)) continue
    const key = session.workspaceId || ''
    if (!groups.has(key)) groups.set(key, { label: workspace?.name || '最近会话', icon: workspace?.icon || 'folder', color: workspace?.color || '', directories: workspace?.directories || [], sessions: [] })
    groups.get(key)!.sessions.push(session)
  }
  return [...groups.entries()]
})

// 在项目列表中折叠或展开会话，搜索时始终显示结果。
function toggleGroup(path: string) {
  const next = new Set(collapsedGroups.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsedGroups.value = next
}

function closeSessionMenu(restoreFocus = false) {
  const trigger = sessionMenuTrigger
  sessionMenuId.value = ''
  sessionMenuTrigger = null
  if (restoreFocus) trigger?.focus()
}

async function toggleSessionMenu(session: Session, event: MouseEvent) {
  if (sessionMenuId.value === session.id) { closeSessionMenu(true); return }
  const trigger = event.currentTarget as HTMLElement
  const bounds = trigger.getBoundingClientRect()
  const width = 144
  const below = window.innerHeight - bounds.bottom - 12
  const above = bounds.top - 12
  const upwards = below < 88 && above > below
  sessionMenuTrigger = trigger
  sessionMenuPosition.value = {
    left: `${Math.max(8, Math.min(bounds.right - width, window.innerWidth - width - 8))}px`,
    maxHeight: `${Math.max(80, upwards ? above : below)}px`,
    ...(upwards ? { bottom: `${window.innerHeight - bounds.top + 4}px` } : { top: `${bounds.bottom + 4}px` }),
  }
  sessionMenuId.value = session.id
  await nextTick()
  sessionMenuElement.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
}

function outsideSessionMenu(event: PointerEvent) {
  if (!sessionMenuId.value) return
  const target = event.target as Node
  if (!sessionMenuTrigger?.contains(target) && !sessionMenuElement.value?.contains(target)) closeSessionMenu()
}

function focusOutsideSessionMenu(event: FocusEvent) {
  if (!sessionMenuId.value) return
  const target = event.target as Node
  if (!sessionMenuTrigger?.contains(target) && !sessionMenuElement.value?.contains(target)) closeSessionMenu()
}

function scrollSessionMenu(event: Event) {
  if (sessionMenuId.value && !sessionMenuElement.value?.contains(event.target as Node)) closeSessionMenu()
}

function resizeSessionMenu() { closeSessionMenu() }

function navigateSessionMenu(event: KeyboardEvent) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const options = [...(sessionMenuElement.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || [])]
  if (!options.length) return
  event.preventDefault()
  const index = options.indexOf(event.target as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
    : event.key === 'ArrowDown' ? (index + 1) % options.length : (index <= 0 ? options.length : index) - 1
  options[next]?.focus()
}

function onEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || event.defaultPrevented) return
  if (sessionMenuId.value) { event.preventDefault(); closeSessionMenu(true); return }
  if (props.isMobile) emit('update:collapsed', true)
}

// 记录重命名入口，关闭弹窗后恢复键盘焦点。
function openRename(session: Session, event: MouseEvent) {
  renameTrigger = sessionMenuTrigger || (event.currentTarget as HTMLElement)
  closeSessionMenu()
  renamingSession.value = session
}

async function closeRename() {
  const trigger = renameTrigger
  renamingSession.value = null; renameTrigger = null
  await nextTick()
  if (trigger?.isConnected) trigger.focus()
}

// 记录删除入口，关闭弹窗后恢复键盘焦点。
function openDelete(session: Session, event: MouseEvent) {
  deleteTrigger = sessionMenuTrigger || (event.currentTarget as HTMLElement)
  closeSessionMenu()
  deletingSession.value = session
}

// 弹窗取消和删除后都返回原入口；入口已随会话移除时，焦点落到"新对话"。
async function closeDelete() {
  const trigger = deleteTrigger
  deletingSession.value = null; deleteTrigger = null
  await nextTick()
  if (trigger?.isConnected) trigger.focus()
  else (document.querySelector('.new-session') as HTMLElement | null)?.focus()
}

onMounted(() => {
  document.addEventListener('keydown', onEscape)
  document.addEventListener('pointerdown', outsideSessionMenu)
  document.addEventListener('focusin', focusOutsideSessionMenu)
  document.addEventListener('scroll', scrollSessionMenu, true)
  window.addEventListener('resize', resizeSessionMenu)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onEscape)
  document.removeEventListener('pointerdown', outsideSessionMenu)
  document.removeEventListener('focusin', focusOutsideSessionMenu)
  document.removeEventListener('scroll', scrollSessionMenu, true)
  window.removeEventListener('resize', resizeSessionMenu)
})
</script>

<template>
  <button v-if="!collapsed && !showSettings" class="sidebar-scrim" aria-label="关闭会话侧栏" @click="emit('update:collapsed', true)"></button>
  <aside v-show="!showSettings" class="sidebar" :inert="collapsed || undefined">
    <div class="sidebar-primary"><button class="new-session" :disabled="loading || submitting || settingsBusy || workspaceChanging" @click="emit('createSession')"><UiIcon name="plus" :size="17" /><span>新的任务</span></button><button class="icon-button collapse-sidebar" aria-label="收起侧栏" title="收起侧栏" @click="emit('update:collapsed', true)"><UiIcon name="panel" :size="16" /></button></div>
    <nav class="sidebar-links" aria-label="功能"><button class="sidebar-link" :aria-current="activePage === 'automations' ? 'page' : undefined" :disabled="settingsBusy || workspaceChanging" @click="emit('openAutomations')"><UiIcon name="clock" :size="16" /><span>自动化</span></button><button class="sidebar-link" :aria-current="activePage === 'memories' ? 'page' : undefined" :disabled="settingsBusy || workspaceChanging" @click="emit('openMemories')"><UiIcon name="memory" :size="16" /><span>记忆</span></button><button class="sidebar-link" :aria-current="activePage === 'groups' ? 'page' : undefined" :disabled="settingsBusy || workspaceChanging" @click="emit('openGroups')"><UiIcon name="users" :size="16" /><span>群聊</span></button><button class="sidebar-link" :aria-current="activePage === 'flows' ? 'page' : undefined" :disabled="settingsBusy || workspaceChanging" @click="emit('openFlows')"><UiIcon name="branch" :size="16" /><span>流水线</span><span v-if="waitingFlowCount" class="sidebar-link-badge" :aria-label="`${waitingFlowCount} 件流水线事项待处理`">{{ waitingFlowCount }}</span></button></nav>
    <label class="session-search"><UiIcon name="search" :size="16" /><input v-model="sessionSearch" placeholder="搜索" aria-label="搜索会话" /><button v-if="sessionSearch" class="icon-button" aria-label="清空搜索" @click="sessionSearch = ''"><UiIcon name="close" :size="14" /></button></label>
    <nav class="session-navigation" aria-label="会话列表">
      <div v-if="sessionGroups.length" class="session-section-label">工作区</div>
      <section v-for="[path, group] in sessionGroups" :key="path" class="session-group" :class="{ collapsed: collapsedGroups.has(path) && !sessionSearch }">
        <h2 class="workspace-group-heading"><button class="group-toggle" :title="group.directories.join('\n') || group.label" :aria-expanded="!collapsedGroups.has(path) || !!sessionSearch" @click="toggleGroup(path)"><UiIcon v-if="path" :name="group.icon" :style="{ color: group.color }" :size="16" /><span>{{ group.label }}</span></button><button v-if="path" class="icon-button workspace-add" :disabled="loading || submitting || workspaceChanging" :aria-label="`在 ${group.label} 新建会话`" title="新建会话" @click="emit('createWorkspaceSession', path)"><UiIcon name="plus" :size="14" /></button></h2>
        <div v-for="session in group.sessions" v-show="!collapsedGroups.has(path) || !!sessionSearch" :key="session.id" class="session-row" :class="{ selected: session.id === selectedId && activePage === 'chat', 'menu-open': sessionMenuId === session.id }">
          <button class="session-item" :disabled="submitting || loading || settingsBusy || workspaceChanging" :aria-current="session.id === selectedId && activePage === 'chat' ? 'page' : undefined" :title="session.title" @click="emit('selectSession', session.id)"><span>{{ session.title }}</span><span v-if="session.id === selectedId && currentRunning" class="activity-dot" aria-label="任务运行中"></span></button>
          <button class="icon-button session-more" :disabled="loading || workspaceChanging" :aria-label="`更多操作：${session.title}`" title="更多操作" aria-haspopup="menu" :aria-expanded="sessionMenuId === session.id" :aria-controls="sessionMenuId === session.id ? `session-menu-${session.id}` : undefined" @click="toggleSessionMenu(session, $event)"><UiIcon name="more" :size="16" /></button>
        </div>
      </section>
      <div v-if="!sessionGroups.length && sessionSearch" class="sidebar-empty"><UiIcon name="search" :size="22" /><p>没有匹配的会话</p></div>
    </nav>
    <div class="sidebar-footer"><button class="settings-entry" :disabled="settingsBusy || workspaceChanging" @click="emit('openSettings')" title="设置与偏好"><img class="sidebar-avatar" src="/harness-icon.svg" width="20" height="20" alt="" /><span>本地工作台</span><UiIcon name="settings" :size="16" /></button></div>
    <SidebarResize v-if="!isMobile && !collapsed && !showSettings" :width="width" @update:width="emit('update:width', $event)" />
  </aside>
  <Teleport to="body">
    <div v-if="sessionMenuSession" :id="`session-menu-${sessionMenuSession.id}`" ref="sessionMenuElement" class="session-menu" :style="sessionMenuPosition" role="menu" :aria-label="`${sessionMenuSession.title} 的操作`" @keydown="navigateSessionMenu">
      <button type="button" role="menuitem" tabindex="-1" :disabled="loading || workspaceChanging" @click="openRename(sessionMenuSession, $event)"><UiIcon name="edit" :size="13" /><span>重命名</span></button>
      <button type="button" class="danger" role="menuitem" tabindex="-1" :disabled="loading || workspaceChanging || submitting || (sessionMenuSession.id === selectedId && (currentRunning || hasPending))" @click="openDelete(sessionMenuSession, $event)"><UiIcon name="trash" :size="15" /><span>删除</span></button>
    </div>
  </Teleport>
  <SessionRenameDialog v-if="renamingSession" :session="renamingSession" @saved="emit('sessionRenamed', $event)" @close="closeRename" />
  <SessionDeleteDialog v-if="deletingSession" :session="deletingSession" @deleted="emit('sessionDeleted', $event)" @close="closeDelete" />
</template>

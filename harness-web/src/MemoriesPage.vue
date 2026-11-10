<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError, type Workspace } from './api'
import MemoryGraph from './MemoryGraph.vue'
import UiIcon from './UiIcon.vue'
import { memoryKinds, memoryStatuses, memoryColors, memoryTopics, type MemoryEntry, type MemoryPage, type MemoryRelation, type MemoryState } from './memory'

const props = defineProps<{ workspaces: Workspace[]; defaultWorkspaceId: string; sidebarCollapsed: boolean }>()
const emit = defineEmits<{ 'expand-sidebar': []; 'open-session': [id: string]; 'open-settings': [] }>()
const automatic = ref<MemoryState>()
const automaticError = ref('')
const workspaceId = ref(props.defaultWorkspaceId)
const status = ref('')
const query = ref('')
const group = ref('')
const entries = ref<MemoryEntry[]>([])
const relations = ref<MemoryRelation[]>([])
const total = ref(0)
const selectedId = ref('')
const detail = ref<MemoryEntry>()
const selected = computed(() => entries.value.find(item => item.id === selectedId.value) || (detail.value?.id === selectedId.value ? detail.value : undefined))
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const mode = ref<'graph' | 'list'>('list')
const detailClose = ref<HTMLButtonElement>()
let detailTrigger: HTMLElement | SVGElement | null = null
const confirmingDelete = ref(false)
const editor = ref<HTMLDialogElement>()
const editingId = ref('')
const replaces = ref<MemoryEntry>()
const formError = ref('')
const form = ref({ title: '', content: '', kind: 'FACT', tags: '', status: 'ACTIVE', revision: 0 })
const relationTarget = ref('')
const relationLabel = ref('相关')
const related = computed(() => relations.value.filter(edge => edge.fromId === selectedId.value || edge.toId === selectedId.value))
const topics = computed(() => memoryTopics(entries.value))
const groups = computed(() => [...new Set(topics.value.values())].sort())
const visibleEntries = computed(() => group.value ? entries.value.filter(item => topics.value.get(item.id) === group.value) : entries.value)
const workspaceName = computed(() => props.workspaces.find(item => item.id === workspaceId.value)?.name || '全局记忆')
let loadVersion = 0
let detailVersion = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
let processingTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let refreshedCompleted = -1
let editorTrigger: HTMLElement | null = null

/** 过滤和分页请求使用版本隔离，迟到响应不能覆盖新的工作区。 */
async function load(append = false) {
  const version = ++loadVersion
  const completedAtStart = automatic.value?.progress.completed ?? -1
  loading.value = true; error.value = ''
  const parameters = new URLSearchParams({ workspaceId: workspaceId.value, status: status.value, query: query.value,
    offset: String(append ? entries.value.length : 0), limit: '200' })
  try {
    const page = await api<MemoryPage>(`/memories?${parameters}`)
    if (version !== loadVersion) return
    const combined = append ? [...entries.value, ...page.items] : page.items
    entries.value = [...new Map(combined.map(item => [item.id, item])).values()]
    relations.value = page.relations; total.value = page.total
    if (!append) refreshedCompleted = completedAtStart
    if (group.value && !groups.value.includes(group.value)) group.value = ''
    if (selectedId.value && !entries.value.some(item => item.id === selectedId.value)) {
      if (detail.value?.id === selectedId.value) await select(selectedId.value)
      else selectedId.value = ''
    }
  } catch (cause) { if (version === loadVersion) error.value = message(cause) }
  finally { if (version === loadVersion) loading.value = false }
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : '记忆操作失败' }
/** 关联目标可能位于分页或过滤范围外，按标识单独读取详情。 */
async function select(id: string) {
  if (id && !selectedId.value) detailTrigger = document.activeElement instanceof HTMLElement || document.activeElement instanceof SVGElement ? document.activeElement : null
  const version = ++detailVersion
  selectedId.value = id; detail.value = undefined; confirmingDelete.value = false; relationTarget.value = ''
  if (!id || entries.value.some(item => item.id === id)) return
  try {
    const value = await api<MemoryEntry>(`/memories/${id}`)
    if (version === detailVersion && value.workspaceId === workspaceId.value) detail.value = value
  } catch (cause) {
    if (version === detailVersion) {
      if (cause instanceof ApiError && cause.status === 404) selectedId.value = ''
      error.value = message(cause)
    }
  }
}
/** 收起详情后回到原来的记忆，便于继续浏览列表或图谱。 */
async function closeDetail() {
  await select('')
  await nextTick()
  if (detailTrigger?.isConnected) detailTrigger.focus({ preventScroll: true })
}
watch(selectedId, async id => {
  if (!id || window.innerWidth > 1000) return
  await nextTick()
  detailClose.value?.focus({ preventScroll: true })
})
function titleOf(id: string) { return entries.value.find(item => item.id === id)?.title || '未在当前列表中显示的记忆' }

/** 使用原生对话框保留键盘焦点和 Esc 语义。 */
async function openEditor(entry: MemoryEntry, replace = false) {
  editorTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  editingId.value = replace ? '' : entry.id
  replaces.value = replace ? entry : undefined
  form.value = { title: entry.title, content: entry.content,
    kind: entry.kind, tags: entry.tags.join(', '), status: replace ? 'ACTIVE' : entry.status, revision: entry.revision }
  formError.value = ''
  await nextTick()
  editor.value?.showModal()
}
function closeEditor() { if (!busy.value) editor.value?.close() }
function editorClosed() { editorTrigger?.focus(); replaces.value = undefined }
async function save() {
  if (busy.value) return
  busy.value = true; formError.value = ''
  try {
    const value = await api<MemoryEntry>(editingId.value ? `/memories/${editingId.value}` : '/memories', {
      ...form.value, workspaceId: workspaceId.value, tags: [...new Set(form.value.tags.split(/[,，]/).map(tag => tag.trim()).filter(Boolean))],
      replacesId: replaces.value?.id, replacesRevision: replaces.value?.revision,
    })
    selectedId.value = value.id
    detail.value = value
    editor.value?.close()
    notice.value = '记忆已保存'
    await load()
  } catch (cause) { formError.value = message(cause) }
  finally { busy.value = false }
}
async function setStatus(value: string) {
  if (!selected.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    detail.value = await api<MemoryEntry>(`/memories/${selected.value.id}`, { ...selected.value, status: value })
    notice.value = '记忆状态已更新'; await load()
  }
  catch (cause) { error.value = message(cause) }
  finally { busy.value = false }
}
async function remove() {
  if (!selected.value || busy.value) return
  busy.value = true; error.value = ''
  try {
    await api(`/memories/${selected.value.id}/delete`, { revision: selected.value.revision })
    select(''); notice.value = '记忆已删除，后续任务不会再召回它'; await load()
  } catch (cause) { error.value = message(cause) }
  finally { busy.value = false }
}
async function relate(edge?: MemoryRelation) {
  if (busy.value || (!edge && !relationTarget.value)) return
  busy.value = true; error.value = ''
  try {
    await api(edge ? '/memory-relations/delete' : '/memory-relations', edge || {
      fromId: selectedId.value, toId: relationTarget.value, label: relationLabel.value,
    })
    relationTarget.value = ''; await load()
  } catch (cause) { error.value = message(cause) }
  finally { busy.value = false }
}
watch([workspaceId, status], () => {
  clearTimeout(searchTimer); loadVersion++; entries.value = []; relations.value = []; total.value = 0
  select(''); group.value = ''; notice.value = ''; void load()
})
watch(query, () => {
  clearTimeout(searchTimer); loadVersion++; loading.value = true
  searchTimer = setTimeout(() => { select(''); void load() }, 250)
})
/** 自动生成完成后刷新图谱，进度全部取自后台队列。 */
async function loadAutomatic() {
  try {
    const value = await api<MemoryState>('/memory-settings')
    if (disposed) return
    automatic.value = value; automaticError.value = ''
    if (refreshedCompleted !== value.progress.completed && !busy.value && !loading.value) void load()
  } catch (cause) { if (!disposed) automaticError.value = message(cause) }
  finally { if (!disposed) processingTimer = setTimeout(() => { void loadAutomatic() }, 3000) }
}
onMounted(() => { void load(); void loadAutomatic() })
onUnmounted(() => { disposed = true; loadVersion++; detailVersion++; clearTimeout(searchTimer); clearTimeout(processingTimer) })
</script>

<template>
  <main class="main-panel memories-page">
    <header class="workspace-header">
      <div class="workspace-title"><button v-if="sidebarCollapsed" class="icon-button" aria-label="展开侧栏" @click="emit('expand-sidebar')"><UiIcon name="panel" /></button><span class="header-breadcrumb">工作台 <span aria-hidden="true">/</span></span><h1>长期记忆</h1></div>
      <button class="memory-settings-link" :disabled="busy" @click="emit('open-settings')"><UiIcon name="settings" :size="15" />记忆设置</button>
    </header>
    <div class="memory-toolbar">
      <select v-model="workspaceId" aria-label="记忆范围" :disabled="busy"><option value="">全局记忆</option><option v-for="workspace in workspaces" :key="workspace.id" :value="workspace.id">{{ workspace.name }}</option></select>
      <label class="memory-search"><UiIcon name="search" :size="16" /><input v-model="query" maxlength="200" placeholder="搜索记忆" aria-label="搜索记忆" :disabled="busy" /></label>
      <select v-model="status" aria-label="记忆状态" :disabled="busy"><option value="">全部状态</option><option v-for="(label, value) in memoryStatuses" :key="value" :value="value">{{ label }}</option></select>
      <div class="memory-view-switch segmented" aria-label="视图"><button :aria-pressed="mode === 'graph'" @click="mode = 'graph'">图谱</button><button :aria-pressed="mode === 'list'" @click="mode = 'list'">列表</button></div>
      <button class="icon-button" :disabled="loading || busy" aria-label="刷新记忆" title="刷新记忆" @click="load()"><UiIcon name="refresh" :size="16" /></button>
    </div>
    <div v-if="automatic" class="memory-automatic-status" role="status"><span class="memory-status-state" :class="{ enabled: automatic.settings.enabled }"><i aria-hidden="true"></i>{{ automatic.settings.enabled ? '自动记忆已开启' : '自动记忆已关闭' }}</span><template v-if="automatic.settings.enabled"><span>已处理 {{ automatic.progress.completed + automatic.progress.skipped }} / {{ automatic.progress.total }} 段对话</span><span v-if="automatic.progress.running">正在生成</span><span v-if="automatic.progress.failed">失败 {{ automatic.progress.failed }}</span></template></div>
    <p v-if="automaticError" class="memory-banner memory-error" role="alert">{{ automaticError }}</p>
    <p v-if="error" class="memory-banner memory-error" role="alert">{{ error }} <button :disabled="busy || loading" @click="load()">刷新重试</button></p>
    <p v-if="notice" class="memory-banner" role="status">{{ notice }}<button class="icon-button" aria-label="关闭提示" @click="notice = ''"><UiIcon name="close" :size="14" /></button></p>
    <div class="memory-workbench" :class="{ 'has-detail': selectedId }" :aria-busy="loading">
      <aside class="memory-categories" aria-label="主题分类">
        <p class="memory-section-label">{{ workspaceName }}</p>
        <button :class="{ active: !group }" @click="group = ''"><span>全部主题</span><small>{{ entries.length }}</small></button>
        <button v-for="(name, index) in groups" :key="name" :class="{ active: group === name }" @click="group = group === name ? '' : name">
          <i :style="{ background: memoryColors[index % memoryColors.length] }"></i><span>{{ name }}</span><small>{{ entries.filter(item => topics.get(item.id) === name).length }}</small>
        </button>
        <div class="memory-category-footer"><span>{{ entries.length }} / {{ total }} 条记忆</span><button v-if="entries.length < total" :disabled="loading" @click="load(true)">加载更多</button><span v-if="loading" role="status">正在读取…</span></div>
      </aside>
      <section class="memory-canvas" aria-label="记忆浏览">
        <MemoryGraph v-if="mode === 'graph' && entries.length" :key="workspaceId" :entries="entries" :relations="relations" :selected-id="selectedId" :group="group" @select="select" />
        <div v-else-if="entries.length" class="memory-list">
          <button v-for="entry in visibleEntries" :key="entry.id" :class="{ selected: selectedId === entry.id }" @click="select(entry.id)">
            <span class="memory-list-meta">{{ memoryKinds[entry.kind] }}<small>{{ memoryStatuses[entry.status] }}</small></span><strong>{{ entry.title }}</strong><p>{{ entry.content }}</p><span class="memory-list-tags">{{ entry.tags.join(' / ') }}</span>
          </button>
        </div>
        <div v-else class="memory-empty"><UiIcon name="memory" :size="26" /><h2>{{ loading ? '正在读取记忆…' : query || status ? '没有匹配的记忆' : !automatic ? '正在读取设置…' : !automatic.settings.enabled ? '自动记忆尚未开启' : automatic.progress.pending || automatic.progress.running ? '正在从对话生成记忆' : '暂时没有可保留的记忆' }}</h2><button v-if="!loading && !query && !status && automatic && !automatic.settings.enabled" class="memory-primary" @click="emit('open-settings')">前往设置开启</button></div>
      </section>
      <aside v-if="selectedId" class="memory-inspector" aria-label="记忆详情" @keydown.esc.stop="($event.target as Element).tagName !== 'OPTION' && closeDetail()">
        <div class="memory-inspector-heading"><strong>记忆详情</strong><button ref="detailClose" class="icon-button" aria-label="关闭记忆详情" @click="closeDetail"><UiIcon name="close" :size="17" /></button></div>
        <template v-if="selected">
          <div class="memory-detail-meta"><span>{{ memoryKinds[selected.kind] }}</span><span class="memory-status-badge" :class="selected.status.toLowerCase()">{{ memoryStatuses[selected.status] }}</span></div>
          <h2>{{ selected.title }}</h2><p class="memory-content">{{ selected.content }}</p>
          <div v-if="selected.tags.length" class="memory-tags"><span v-for="tag in selected.tags" :key="tag">{{ tag }}</span></div>
          <div class="memory-detail-actions"><button :disabled="busy" @click="openEditor(selected)">编辑</button><button v-if="selected.status === 'CANDIDATE'" class="memory-primary" :disabled="busy" @click="setStatus('ACTIVE')">确认记忆</button><button v-if="selected.status === 'ACTIVE'" :disabled="busy" @click="openEditor(selected, true)">用新结论替代</button><button v-if="selected.status !== 'ARCHIVED'" :disabled="busy" @click="setStatus('ARCHIVED')">归档</button></div>
          <div class="memory-detail-section"><h3>来源</h3><button v-if="selected.sourceSessionId" class="memory-source-link" @click="emit('open-session', selected.sourceSessionId)"><UiIcon name="chat" :size="14" />打开原始会话</button><p v-else>{{ selected.sourceExcerpt ? '原始会话已删除，保留提取时的摘录' : '手动创建' }}</p><details v-if="selected.sourceExcerpt"><summary>查看来源摘录</summary><p class="memory-source-excerpt">{{ selected.sourceExcerpt }}</p></details></div>
          <div class="memory-detail-section"><h3>关联记忆</h3>
            <div v-for="edge in related" :key="`${edge.fromId}:${edge.toId}`" class="memory-related-row"><button @click="select(edge.fromId === selectedId ? edge.toId : edge.fromId)">{{ titleOf(edge.fromId === selectedId ? edge.toId : edge.fromId) }}</button><small>{{ edge.label }} {{ edge.fromId === selectedId ? '→' : '←' }}</small><button :disabled="busy" aria-label="删除关系" @click="relate(edge)">×</button></div>
            <form v-if="entries.length > 1" class="memory-relation-form" @submit.prevent="relate()"><select v-model="relationTarget" aria-label="关联记忆" :disabled="busy" required><option value="">选择记忆</option><option v-for="entry in entries.filter(item => item.id !== selectedId)" :key="entry.id" :value="entry.id">{{ entry.title }}</option></select><input v-model="relationLabel" aria-label="关系名称" maxlength="32" required :disabled="busy" /><button :disabled="busy || !relationTarget">关联</button></form>
          </div>
          <div class="memory-detail-section memory-timestamps"><span>创建于 {{ new Date(selected.createdAt).toLocaleString('zh-CN') }}</span><span>更新于 {{ new Date(selected.updatedAt).toLocaleString('zh-CN') }}</span><span>版本 {{ selected.revision }}</span></div>
          <div class="memory-delete"><template v-if="confirmingDelete"><p>删除后不再召回这条记忆。</p><button class="danger" :disabled="busy" @click="remove">确认删除</button><button :disabled="busy" @click="confirmingDelete = false">取消</button></template><button v-else :disabled="busy" @click="confirmingDelete = true">删除记忆</button></div>
        </template>
        <p v-else role="status">正在读取记忆详情…</p>
      </aside>
    </div>
    <dialog ref="editor" class="memory-editor" aria-labelledby="memory-editor-title" @cancel="busy && $event.preventDefault()" @close="editorClosed">
      <form @submit.prevent="save"><header><h2 id="memory-editor-title">{{ replaces ? '用新结论替代' : '编辑记忆' }}</h2><button type="button" class="icon-button" :disabled="busy" aria-label="关闭记忆编辑" @click="closeEditor"><UiIcon name="close" :size="18" /></button></header>
        <p class="memory-editor-scope">{{ workspaceName }}<span v-if="replaces">旧结论将在保存后归档</span></p>
        <label>标题<input v-model="form.title" maxlength="160" required autofocus :disabled="busy" /></label>
        <label>正文<textarea v-model="form.content" maxlength="6000" rows="8" required :disabled="busy"></textarea></label>
        <div class="memory-form-row"><label>类型<select v-model="form.kind" :disabled="busy"><option v-for="(label, value) in memoryKinds" :key="value" :value="value">{{ label }}</option></select></label><label>状态<select v-model="form.status" :disabled="busy || !!replaces"><option v-for="(label, value) in memoryStatuses" :key="value" :value="value">{{ label }}</option></select></label></div>
        <label>标签<input v-model="form.tags" maxlength="600" placeholder="用逗号分隔，首个标签作为图谱主题" :disabled="busy" /></label>
        <p v-if="formError" class="memory-error" role="alert">{{ formError }}</p>
        <footer><button type="button" :disabled="busy" @click="closeEditor">取消</button><button class="memory-primary" :disabled="busy">{{ busy ? '正在保存…' : '保存记忆' }}</button></footer>
      </form>
    </dialog>
  </main>
</template>

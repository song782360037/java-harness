<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from './api'
import { filterExtensions, saveAndRefresh, type ExtensionItem, type ExtensionKind } from './extensions'
import ExtensionRow from './ExtensionRow.vue'
import ExtensionConfiguration from './ExtensionConfiguration.vue'
import UiIcon from './UiIcon.vue'
import './styles/extensions.css'

const emit = defineEmits<{ busy: [value: boolean] }>()
const tabs = [
  { id: 'plugin', label: '插件', english: 'Plugins', icon: 'cube', tagline: '聚焦效率、代码质量、工作流。' },
  { id: 'skill', label: '技能', english: 'Skills', icon: 'code', tagline: '把好方法，变成随时可用的技能。' },
  { id: 'connector', label: '连接器', english: 'Connectors', icon: 'globe', tagline: '连接工具，让工作流更进一步。' },
] as const
const kind = ref<ExtensionKind>('plugin'), query = ref(''), category = ref('全部'), installedOnly = ref(false), sort = ref('featured')
const current = computed(() => tabs.find(tab => tab.id === kind.value)!)
const items = ref<ExtensionItem[]>([]), busy = ref(false), error = ref('')
const toggling = ref(new Set<string>())
let catalogRequest = 0
const configurationBusy = ref(false)
const locked = computed(() => busy.value || configurationBusy.value)
const categories = computed(() => ['全部', '官方精选', '自定义', ...new Set(items.value.filter(item => item.kind === kind.value).map(item => item.category))])
const visible = computed(() => filterExtensions(items.value, kind.value, query.value, category.value, installedOnly.value, sort.value))
const recommended = computed(() => items.value.filter(item => item.kind === kind.value && item.featured).slice(0, 6))
const dialog = ref<'preview' | 'add' | null>(null), selected = ref<ExtensionItem | null>(null), content = ref(''), dialogError = ref('')
const previewLoading = ref(false)
let previewRequest = 0
const modal = ref<HTMLElement>(), previousFocus = ref<HTMLElement>()
const editing = ref(false), editRevision = ref(0)
const name = ref(''), title = ref(''), description = ref(''), version = ref('1.0.0'), body = ref(''), directory = ref(''), source = ref('local')
const file = ref<File | null>(null), importMode = ref('directory')
const connectorExample = '{\n  "type": "stdio",\n  "command": "npx",\n  "args": ["-y", "your-mcp-server"],\n  "env": {}\n}'
watch(locked, value => emit('busy', value))
watch(kind, () => { category.value = '全部'; query.value = ''; installedOnly.value = false })

/** Refresh discovery independently of any scoped launch configuration. */
async function reload() {
  const request = ++catalogRequest
  const values = await api<ExtensionItem[]>('/extensions')
  if (request === catalogRequest) items.value = values
  return values
}
async function refresh() {
  if (locked.value) return
  busy.value = true; error.value = ''
  try { await reload() } catch (cause) { error.value = message(cause) } finally { busy.value = false }
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : '扩展操作失败，请重试。' }
/** Keep keyboard focus within the open modal and return it to the invoking control. */
async function show(mode: 'preview' | 'add') {
  if (!dialog.value) previousFocus.value = document.activeElement as HTMLElement
  dialog.value = mode; dialogError.value = ''
  await nextTick(); modal.value?.focus()
}
function close() {
  if (locked.value) return
  previewRequest++; previewLoading.value = false
  dialog.value = null; selected.value = null; content.value = ''; body.value = ''; file.value = null
  void nextTick(() => previousFocus.value?.isConnected ? previousFocus.value.focus() : document.querySelector<HTMLElement>('.extension-add')?.focus())
}
function keyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close() }
  if (event.key !== 'Tab') return
  const controls = Array.from(modal.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]') || []).filter(element => element.getClientRects().length)
  const first = controls[0], last = controls[controls.length - 1]
  if (!first) { event.preventDefault(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === modal.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === modal.value)) { event.preventDefault(); first.focus() }
}
async function preview(item: ExtensionItem) {
  if (locked.value) return
  const request = ++previewRequest
  selected.value = item; content.value = ''; previewLoading.value = true; await show('preview')
  try {
    const value = await api<{ item: ExtensionItem; content: string }>(`/extensions/items/${encodeURIComponent(item.id)}`)
    if (request !== previewRequest) return
    selected.value = value.item; content.value = value.content
  } catch (cause) { if (request === previewRequest) dialogError.value = message(cause) }
  finally { if (request === previewRequest) previewLoading.value = false }
}
async function install() {
  if (!selected.value || locked.value) return
  busy.value = true; dialogError.value = ''
  try {
    const result = await saveAndRefresh(() => api<{ id: string }>(`/extensions/catalog/${encodeURIComponent(selected.value!.id)}/install`, {}), reload)
    error.value = result.refreshError ? `已安装，列表刷新失败：${result.refreshError}。请重试刷新。` : ''
    busy.value = false; close()
  } catch (cause) { dialogError.value = message(cause) } finally { busy.value = false }
}
function add() {
  editing.value = false; name.value = ''; title.value = ''; description.value = ''; version.value = '1.0.0'; directory.value = ''; source.value = 'local'; file.value = null
  body.value = kind.value === 'connector' ? connectorExample : ''; selected.value = null; void show('add')
}
async function create() {
  if (locked.value || editing.value && editRevision.value === 0) return
  busy.value = true; dialogError.value = ''
  try {
    const result = await saveAndRefresh(async () => {
    let value: { id: string }
    if (kind.value === 'plugin' && importMode.value === 'zip') {
      if (!file.value) throw new Error('请选择插件 ZIP 文件。')
      const form = new FormData(); form.append('file', file.value); form.append('source', source.value)
      const response = await fetch('/api/plugins/import-zip', { method: 'POST', body: form })
      if (!response.ok) { const failure = await response.json().catch(() => ({})); throw new Error(failure.message || '上传失败，ZIP 不超过 16 MiB。') }
      value = await response.json()
    } else if (kind.value === 'plugin') value = await api('/plugins/import-directory', { path: directory.value, source: source.value })
    else { const form = {
      name: name.value, title: title.value, description: description.value, version: version.value, scope: 'user',
      ...(kind.value === 'skill' ? { content: body.value } : { definition: JSON.parse(body.value) }),
    }; value = await api(editing.value ? `/extensions/installed/${selected.value!.installationId}/edit` : `/extensions/${kind.value === 'skill' ? 'skills' : 'connectors'}`, editing.value ? { revision: editRevision.value, form } : form) }
    return value
    }, reload)
    error.value = result.refreshError ? `已保存，列表刷新失败：${result.refreshError}。请重试刷新，无需重复提交。` : ''
    busy.value = false; close()
  } catch (cause) { dialogError.value = message(cause) } finally { busy.value = false }
}
/** Toggle the owning package globally, including components shown in other tabs. */
async function toggle(item: ExtensionItem) {
  if (locked.value || toggling.value.has(item.installationId)) return
  toggling.value.add(item.installationId); error.value = ''
  try {
    const result = await saveAndRefresh(() => api(`/extensions/installed/${item.installationId}/enabled`, { enabled: !item.enabled, revision: item.revision }), reload)
    if (result.refreshError) {
      items.value = items.value.map(value => value.installationId === item.installationId ? { ...value, enabled: !item.enabled, revision: item.revision + 1 } : value)
      error.value = `启停已保存，列表刷新失败：${result.refreshError}。请重试刷新。`
    }
  }
  catch (cause) { error.value = message(cause); await reload().catch(() => {}) }
  finally { toggling.value.delete(item.installationId) }
}
async function remove(item: ExtensionItem) {
  if (locked.value) return
  busy.value = true; error.value = ''
  try {
    const result = await saveAndRefresh(() => api(`/plugins/${item.installationId}/remove`, { uninstall: true }), reload)
    error.value = result.refreshError ? `已卸载，列表刷新失败：${result.refreshError}。请重试刷新。` : ''
    busy.value = false; if (dialog.value) close()
  }
  catch (cause) { error.value = message(cause) } finally { busy.value = false }
}
/** Refresh management state after saved settings without remounting the open details. */
async function configurationSaved() {
  try {
    const values = await reload()
    selected.value = values.find(value => value.id === selected.value?.id) || selected.value
  } catch (cause) { error.value = `配置已保存，列表刷新失败：${message(cause)}。请重试刷新。` }
}
/** Prefill the add form with redacted custom content; null credentials retain saved values. */
async function edit(item: ExtensionItem) {
  selected.value = item; editing.value = true; editRevision.value = 0; body.value = ''; name.value = ''; title.value = ''; description.value = ''; await show('add'); busy.value = true
  try {
    const value = await api<{ definition: { name: string; title: string; description: string; version: string; content?: string; definition?: object }; revision: number }>(`/extensions/installed/${item.installationId}/edit`)
    const form = value.definition; name.value = form.name; title.value = form.title; description.value = form.description
    version.value = form.version.replace(/^(\d+\.\d+\.)(\d+)$/, (_, prefix, patch) => prefix + (Number(patch) + 1))
    body.value = form.content ?? JSON.stringify(form.definition, null, 2); editRevision.value = value.revision
  } catch (cause) { dialogError.value = message(cause) } finally { busy.value = false }
}
onMounted(refresh)
</script>

<template>
  <section class="extension-center" :class="`extension-${kind}`" aria-label="扩展">
    <header class="extension-toolbar">
      <nav class="extension-tabs" aria-label="扩展类型"><button v-for="tab in tabs" :key="tab.id" :aria-pressed="kind === tab.id" :class="{ active: kind === tab.id }" :disabled="locked" @click="kind = tab.id">{{ tab.label }}<small v-if="tab.id === 'connector'">MCP</small></button></nav>
      <div class="extension-tools"><button class="icon-button" aria-label="刷新扩展" title="刷新扩展" :disabled="locked" @click="refresh"><UiIcon name="refresh" :size="17" /></button>
        <label class="extension-search"><UiIcon name="search" :size="17" /><input v-model="query" aria-label="搜索扩展" placeholder="搜索名称、说明或分类" /></label>
        <button class="extension-installed" :class="{ active: installedOnly }" :aria-pressed="installedOnly" @click="installedOnly = !installedOnly">已安装</button>
        <button class="extension-add" :disabled="locked" @click="add">添加{{ current.label }}</button>
      </div>
    </header>
    <div class="extension-discovery">
      <div class="extension-hero"><div><h1><span>{{ installedOnly ? '管理' : '发现' }}</span><span class="extension-hero-icon"><UiIcon :name="current.icon" :size="24" /></span><em>{{ current.english }}</em></h1><p>{{ installedOnly ? '你的扩展，在这里各就各位。' : current.tagline }}</p><svg class="extension-swoosh" viewBox="0 0 110 16" aria-hidden="true"><path d="M3 13Q55-5 88 5M72 12q15-8 33-3" /></svg></div>
        <div class="extension-hero-art" aria-hidden="true"><span><UiIcon name="branch" :size="27" /></span><span><UiIcon :name="current.icon" :size="32" /></span><span><UiIcon name="flask" :size="28" /></span><span><UiIcon name="code" :size="25" /></span></div>
      </div>
      <p v-if="error" role="alert" class="extension-feedback">{{ error }} <button :disabled="locked" @click="refresh">重试</button></p>
      <p v-if="busy && !dialog" role="status" class="extension-loading">正在读取扩展…</p>
      <section v-if="!installedOnly && !query && category === '全部' && recommended.length" class="extension-recommendations" aria-label="官方推荐">
        <h2>官方推荐 <UiIcon name="chevron" :size="14" /></h2>
        <div class="extension-grid"><ExtensionRow v-for="item in recommended" :key="item.id" :item="item" :locked="locked" :pending="toggling.has(item.installationId)" @preview="preview" @toggle="toggle" @edit="edit" @remove="remove" /></div>
      </section>
      <section class="extension-list" :aria-label="`${installedOnly ? '已安装' : '全部'}${current.label}`">
        <h2 v-if="kind !== 'connector' || installedOnly">{{ installedOnly ? '已安装' : '全部' }}{{ current.label }}<small>{{ visible.length }}</small></h2>
        <div class="extension-filterbar"><nav class="extension-categories" aria-label="分类"><button v-for="value in categories" :key="value" :class="{ active: category === value }" :aria-pressed="category === value" @click="category = value">{{ value }}</button></nav><div class="extension-sort" aria-label="排序"><button :class="{ active: sort === 'featured' }" :aria-pressed="sort === 'featured'" @click="sort = 'featured'">推荐</button><button :class="{ active: sort === 'name' }" :aria-pressed="sort === 'name'" @click="sort = 'name'">名称</button></div></div>
        <div class="extension-grid"><ExtensionRow v-for="item in visible" :key="item.id" :item="item" :locked="locked" :pending="toggling.has(item.installationId)" @preview="preview" @toggle="toggle" @edit="edit" @remove="remove" /></div>
        <div v-if="!visible.length && !busy" class="extension-empty"><UiIcon :name="current.icon" :size="32" /><h3>{{ query ? '没有找到匹配的扩展' : installedOnly ? '还没有安装' + current.label : '这个分类还没有扩展' }}</h3><p>{{ query ? '试试其他关键词，或切换分类。' : '从官方目录选择，或添加自己的' + current.label + '。' }}</p><button :disabled="locked" @click="add">添加{{ current.label }}</button></div>
      </section>
      <p class="extension-catalog-note">Java Harness 官方提供 · 支持自定义添加<span>安装后全局启用 · 可随时停用</span></p>
    </div>
    <Teleport to="body"><div v-if="dialog" class="extension-overlay" @keydown="keyboard" @click.self="close"><section ref="modal" class="extension-modal" :class="{ 'extension-modal-preview': dialog === 'preview' }" role="dialog" aria-modal="true" aria-labelledby="extension-dialog-title" tabindex="-1">
      <header class="extension-modal-heading"><div><span class="extension-modal-eyebrow">{{ dialog === 'add' ? '自定义扩展' : selected?.official ? 'JAVA HARNESS · 官方扩展' : selected?.parentName ? '插件内组件' : '自定义扩展' }}</span><h2 id="extension-dialog-title">{{ dialog === 'add' ? (editing ? '编辑' : '添加') + current.label : selected?.title }}</h2></div><button class="icon-button" :disabled="locked" aria-label="关闭扩展详情" @click="close"><UiIcon name="close" :size="20" /></button></header>
      <div class="extension-modal-body"><p v-if="dialogError" role="alert" class="extension-feedback">{{ dialogError }}</p>
        <template v-if="dialog === 'preview' && selected"><p class="extension-preview-description">{{ selected.description }}</p><div class="extension-meta"><span>{{ selected.category }}</span><span>v{{ selected.version }}</span><span v-if="selected.installationId">{{ selected.enabled ? '已启用' : selected.issue ? '待配置 / 已停用' : '已停用' }}</span></div><p v-if="selected.parentName" class="extension-callout">由「{{ selected.parentName }}」提供。启停与卸载将作用于整个插件。</p><p v-if="selected.issue" role="alert" class="extension-feedback">{{ selected.issue }}</p><p v-if="previewLoading" role="status" class="extension-loading">正在读取详情…</p><pre v-else class="extension-preview-content">{{ content }}</pre>
          <ExtensionConfiguration v-if="selected.installationId && !previewLoading" :key="selected.installationId" :installation-id="selected.installationId" :editable="selected.editable" @busy="configurationBusy = $event" @saved="configurationSaved" @edit="edit(selected!)" />
        </template>
        <form v-if="dialog === 'add'" id="extension-add-form" class="extension-add-form" @submit.prevent="create"><fieldset :disabled="locked">
          <template v-if="kind === 'plugin'"><p class="field-note">安装包含 plugin.json 的 Agent Plugin 包。</p><label>安装方式<select v-model="importMode"><option value="directory">本地目录</option><option value="zip">ZIP 文件</option></select></label><label v-if="importMode === 'directory'">插件目录<input v-model="directory" required placeholder="例如 E:\plugins\my-plugin" /></label><label v-else>ZIP 文件<input type="file" accept=".zip" required @change="file = ($event.target as HTMLInputElement).files?.[0] || null" /></label><label>来源标识<input v-model="source" required maxlength="100" placeholder="local" /></label></template>
          <template v-else><div class="extension-form-columns"><label>名称<input v-model="title" required maxlength="80" placeholder="例如 团队代码审查" /></label><label>唯一标识<input v-model="name" :readonly="editing" required pattern="[a-z0-9]+(-[a-z0-9]+)*" maxlength="40" placeholder="例如 team-review" /></label></div><label>说明<input v-model="description" required maxlength="500" placeholder="这个扩展可以帮你做什么" /></label><div class="extension-form-columns"><label>版本<input v-model="version" required maxlength="40" placeholder="1.0.0" /></label></div>
            <label>{{ kind === 'skill' ? '技能正文（Markdown）' : 'MCP 服务配置（JSON）' }}<textarea v-model="body" required :rows="kind === 'skill' ? 12 : 10" spellcheck="false" :placeholder="kind === 'skill' ? '说明适用场景、执行步骤和期望的输出…' : connectorExample" :autocomplete="kind === 'connector' ? 'off' : undefined" /></label>
            <p class="field-note">{{ kind === 'skill' ? '直接填写指令正文，平台会生成 SKILL.md。支持最多 30 KB。' : '支持 stdio 或 streamable-http。env、headers 加密保存；编辑时 null 表示保留原密钥，删除键可移除密钥。其他字段请勿填写密钥。' }}</p></template>
        </fieldset></form>
      </div>
      <footer class="extension-modal-footer"><span>{{ dialog === 'add' ? '保存后全局生效，并允许插件所需的工具调用' : dialog === 'preview' && selected?.parentName ? '此组件随所属插件统一管理' : '启用后对新任务生效' }}</span><button :disabled="locked" @click="close">关闭</button><button v-if="dialog === 'add'" form="extension-add-form" class="primary" :disabled="locked || editing && editRevision === 0">{{ busy ? '保存中…' : editing ? '保存修改' : '添加' + current.label }}</button><button v-else-if="dialog === 'preview' && selected?.issue" :disabled="locked || previewLoading" @click="selected && remove(selected)">卸载此版本</button><button v-else-if="dialog === 'preview' && !selected?.installationId" class="primary" :disabled="locked || previewLoading || !selected" @click="install">{{ busy ? '处理中…' : '安装' }}</button></footer>
    </section></div></Teleport>
  </section>
</template>

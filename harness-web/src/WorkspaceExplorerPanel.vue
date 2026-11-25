<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from './api'
import type { FileEntry } from './composer'
import { mentionLabel } from './composer'
import {
  detectLanguage,
  formatFileSize,
  highlightCodeLine,
  rootLabel,
  type WorkspaceDirectoryView,
  type WorkspaceFilePreview,
} from './workspaceExplorer'
import UiIcon from './UiIcon.vue'

const props = defineProps<{
  sessionId?: string
  workspaceId?: string
  workspace: string
  directories: string[]
  width: number
  changeRevision: number
  mentionDisabled: boolean
}>()

const emit = defineEmits<{
  close: []
  'update:width': [value: number]
  mention: [entry: FileEntry]
}>()

const MIN_WIDTH = 320
const MAX_WIDTH = 760

const search = ref('')
const searchResults = ref<FileEntry[]>([])
const searching = ref(false)
const searchError = ref('')
let searchTimer: number | undefined
let searchSeq = 0

const expanded = ref(new Set<string>())
const loadingDirs = ref(new Set<string>())
const dirCache = ref<Record<string, WorkspaceDirectoryView>>({})
const treeError = ref('')

const activeFile = ref<{ root: number; path: string } | null>(null)
const preview = ref<WorkspaceFilePreview | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
const copied = ref(false)
let previewSeq = 0
let copyTimer: number | undefined

const dragging = ref(false)
let dragStartX = 0
let dragStartWidth = 0

const scope = computed(() => props.sessionId ? `/sessions/${props.sessionId}` : props.workspaceId ? `/workspaces/${props.workspaceId}` : '')
const multiRoot = computed(() => props.directories.length > 1)
const activeLanguage = computed(() => preview.value ? detectLanguage(preview.value.path) : { id: 'text', label: 'Text' })
const highlightedLines = computed(() => {
  if (!preview.value || preview.value.binary) return []
  const lang = activeLanguage.value.id
  return preview.value.content.split(/\r?\n/).map(line => highlightCodeLine(line, lang))
})

interface FlattenedRow {
  key: string
  root: number
  path: string
  name: string
  directory: boolean
  depth: number
  size: number
  isRootHeader?: boolean
  truncatedNotice?: boolean
}

const flattenedTree = computed<FlattenedRow[]>(() => {
  const rows: FlattenedRow[] = []
  const roots = props.directories.length ? props.directories : (props.workspace ? [props.workspace] : [])
  for (let index = 0; index < roots.length; index++) {
    const root = index + 1
    const rootKey = dirKey(root, '')
    if (multiRoot.value) {
      rows.push({
        key: `root:${root}`,
        root,
        path: '',
        name: rootLabel(roots[index]!, index, roots.length),
        directory: true,
        depth: 0,
        size: 0,
        isRootHeader: true,
      })
      if (expanded.value.has(rootKey)) appendChildren(rows, root, '', 1)
    } else {
      appendChildren(rows, root, '', 0)
    }
  }
  return rows
})

function appendChildren(rows: FlattenedRow[], root: number, dirPath: string, depth: number) {
  const view = dirCache.value[dirKey(root, dirPath)]
  if (!view) return
  for (const entry of view.entries) {
    rows.push({
      key: `${entry.root}:${entry.path}`,
      root: entry.root,
      path: entry.path,
      name: entry.name,
      directory: entry.directory,
      depth,
      size: entry.size,
    })
    if (entry.directory && expanded.value.has(dirKey(entry.root, entry.path))) {
      appendChildren(rows, entry.root, entry.path, depth + 1)
    }
  }
  if (view.truncated) {
    rows.push({
      key: `truncated:${root}:${dirPath}`,
      root,
      path: dirPath,
      name: '仅显示前 500 项，可用上方搜索快速定位',
      directory: false,
      depth,
      size: 0,
      truncatedNotice: true,
    })
  }
}

function dirKey(root: number, dirPath: string) {
  return `${root}:${dirPath}`
}

async function loadDirectory(root: number, dirPath: string, force = false) {
  if (!scope.value) return
  const key = dirKey(root, dirPath)
  if (!force && dirCache.value[key]) return
  const nextLoading = new Set(loadingDirs.value)
  nextLoading.add(key)
  loadingDirs.value = nextLoading
  treeError.value = ''
  try {
    const view = await api<WorkspaceDirectoryView>(`${scope.value}/tree?root=${root}&path=${encodeURIComponent(dirPath)}`)
    dirCache.value = { ...dirCache.value, [key]: view }
  } catch (cause) {
    treeError.value = cause instanceof Error ? cause.message : '读取目录失败'
  } finally {
    const done = new Set(loadingDirs.value)
    done.delete(key)
    loadingDirs.value = done
  }
}

async function toggleDirectory(root: number, dirPath: string) {
  const key = dirKey(root, dirPath)
  const next = new Set(expanded.value)
  if (next.has(key)) {
    next.delete(key)
    expanded.value = next
    return
  }
  next.add(key)
  expanded.value = next
  await loadDirectory(root, dirPath)
}

async function openFile(root: number, filePath: string) {
  if (!scope.value) return
  activeFile.value = { root, path: filePath }
  const seq = ++previewSeq
  previewLoading.value = true
  previewError.value = ''
  copied.value = false
  try {
    const data = await api<WorkspaceFilePreview>(`${scope.value}/file?root=${root}&path=${encodeURIComponent(filePath)}`)
    if (seq === previewSeq) preview.value = data
  } catch (cause) {
    if (seq === previewSeq) {
      preview.value = null
      previewError.value = cause instanceof Error ? cause.message : '无法读取文件内容'
    }
  } finally {
    if (seq === previewSeq) previewLoading.value = false
  }
}

function mentionNode(root: number, nodePath: string, directory: boolean) {
  if (props.mentionDisabled || !nodePath) return
  emit('mention', { root, path: nodePath, directory })
}

async function copyPreview() {
  if (!preview.value || preview.value.binary) return
  try {
    await navigator.clipboard.writeText(preview.value.content)
    copied.value = true
    clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => { copied.value = false }, 1800)
  } catch { /* ignore clipboard failure */ }
}

async function refreshAll() {
  if (!scope.value) return
  const roots = props.directories.length ? props.directories : (props.workspace ? [props.workspace] : [])
  const targets = new Set<string>()
  for (let index = 0; index < roots.length; index++) {
    const root = index + 1
    if (!multiRoot.value || expanded.value.has(dirKey(root, ''))) targets.add(dirKey(root, ''))
  }
  for (const key of expanded.value) targets.add(key)
  await Promise.all([...targets].map(key => {
    const colon = key.indexOf(':')
    return loadDirectory(Number(key.slice(0, colon)), key.slice(colon + 1), true)
  }))
  if (activeFile.value) await openFile(activeFile.value.root, activeFile.value.path)
}

async function initializeRoots() {
  expanded.value = new Set()
  dirCache.value = {}
  treeError.value = ''
  activeFile.value = null
  preview.value = null
  previewError.value = ''
  search.value = ''
  searchResults.value = []
  if (!scope.value || !props.workspace) return
  const roots = props.directories.length ? props.directories : [props.workspace]
  const next = new Set<string>()
  next.add(dirKey(1, ''))
  expanded.value = next
  await loadDirectory(1, '', true)
  if (roots.length > 1) {
    // 预展开第二个根目录的顶层折叠状态仅保持收起，避免多根大仓库并发扫盘。
  }
}

watch(search, value => {
  clearTimeout(searchTimer)
  const trimmed = value.trim()
  if (!trimmed || !scope.value) {
    searchSeq++
    searching.value = false
    searchResults.value = []
    searchError.value = ''
    return
  }
  const seq = ++searchSeq
  searching.value = true
  searchError.value = ''
  searchTimer = window.setTimeout(async () => {
    try {
      const entries = await api<FileEntry[]>(`${scope.value}/files?q=${encodeURIComponent(trimmed)}`)
      if (seq === searchSeq) searchResults.value = entries
    } catch (cause) {
      if (seq === searchSeq) searchError.value = cause instanceof Error ? cause.message : '搜索失败'
    } finally {
      if (seq === searchSeq) searching.value = false
    }
  }, 150)
})

watch(() => [props.sessionId, props.workspaceId, props.workspace], () => {
  void initializeRoots()
}, { immediate: true })

watch(() => props.changeRevision, (next, prev) => {
  if (next > 0 && next !== prev) void refreshAll()
})

function startResize(event: PointerEvent) {
  if (event.button !== 0) return
  dragging.value = true
  dragStartX = event.clientX
  dragStartWidth = props.width
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onResizeMove(event: PointerEvent) {
  if (!dragging.value) return
  const next = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Math.round(dragStartWidth + (dragStartX - event.clientX))))
  emit('update:width', next)
}

function stopResize() {
  dragging.value = false
}

function onResizeKeydown(event: KeyboardEvent) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const delta = event.key === 'ArrowLeft' ? 24 : -24
  emit('update:width', Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, props.width + delta)))
}

onMounted(() => {
  window.addEventListener('pointerup', stopResize)
})

onUnmounted(() => {
  clearTimeout(searchTimer)
  clearTimeout(copyTimer)
  window.removeEventListener('pointerup', stopResize)
})
</script>

<template>
  <aside class="workspace-explorer" :style="{ width: `${width}px` }" aria-label="工作区文件浏览器">
    <div
      class="explorer-resize"
      :class="{ dragging }"
      role="separator"
      aria-orientation="vertical"
      aria-label="调整工作区文件栏宽度"
      :aria-valuenow="width"
      :aria-valuemin="MIN_WIDTH"
      :aria-valuemax="MAX_WIDTH"
      tabindex="0"
      @pointerdown="startResize"
      @pointermove="onResizeMove"
      @pointerup="stopResize"
      @keydown="onResizeKeydown"
    ></div>

    <header class="explorer-header">
      <div class="explorer-title">
        <UiIcon name="folder" :size="15" />
        <strong>工作区文件</strong>
      </div>
      <div class="explorer-actions">
        <button type="button" class="icon-button" aria-label="刷新文件树" title="刷新" @click="refreshAll"><UiIcon name="refresh" :size="15" /></button>
        <button type="button" class="icon-button" aria-label="关闭文件浏览器" title="关闭" @click="emit('close')"><UiIcon name="close" :size="16" /></button>
      </div>
    </header>

    <div class="explorer-search">
      <UiIcon name="search" :size="14" />
      <input v-model="search" type="text" placeholder="按文件名快速定位…" aria-label="过滤工作区文件" autocomplete="off" />
      <span v-if="searching" class="loading-indicator" aria-label="搜索中"></span>
      <button v-else-if="search" type="button" class="icon-button" aria-label="清空过滤" @click="search = ''"><UiIcon name="close" :size="13" /></button>
    </div>

    <div class="explorer-body" :class="{ 'has-preview': !!activeFile }">
      <div class="explorer-tree" role="tree" aria-label="目录树">
        <p v-if="!workspace" class="explorer-status">当前会话尚未绑定工作区。</p>
        <template v-else-if="search.trim()">
          <p v-if="searchError" class="explorer-status is-error" role="alert">{{ searchError }}</p>
          <p v-else-if="!searching && !searchResults.length" class="explorer-status">没有匹配的文件或目录</p>
          <div
            v-for="entry in searchResults"
            :key="`${entry.root}:${entry.path}`"
            class="tree-row"
            :class="{ selected: activeFile?.root === entry.root && activeFile?.path === entry.path }"
          >
            <button
              type="button"
              class="tree-row-main"
              :title="mentionLabel(entry, multiRoot)"
              @click="entry.directory ? (search = '', toggleDirectory(entry.root, entry.path)) : openFile(entry.root, entry.path)"
            >
              <UiIcon :name="entry.directory ? 'folder' : 'file'" :size="14" />
              <span class="tree-search-copy">
                <span class="tree-name">{{ entry.path.slice(entry.path.lastIndexOf('/') + 1) }}</span>
                <small class="tree-subpath">{{ mentionLabel(entry, multiRoot) }}</small>
              </span>
            </button>
            <button
              v-if="!mentionDisabled"
              type="button"
              class="icon-button tree-mention"
              aria-label="引用到输入框"
              title="引用到输入框 (@)"
              @click.stop="mentionNode(entry.root, entry.path, entry.directory)"
            ><UiIcon name="at" :size="13" /></button>
          </div>
        </template>
        <template v-else>
          <p v-if="treeError" class="explorer-status is-error" role="alert">{{ treeError }}</p>
          <div
            v-for="row in flattenedTree"
            :key="row.key"
            class="tree-row"
            :class="{
              selected: !row.directory && activeFile?.root === row.root && activeFile?.path === row.path,
              'is-root': row.isRootHeader,
              'is-notice': row.truncatedNotice,
            }"
            :style="{ paddingLeft: `${8 + row.depth * 14}px` }"
          >
            <template v-if="row.truncatedNotice">
              <span class="tree-notice">{{ row.name }}</span>
            </template>
            <template v-else>
              <button
                type="button"
                class="tree-row-main"
                :title="row.path || row.name"
                @click="row.directory ? toggleDirectory(row.root, row.path) : openFile(row.root, row.path)"
              >
                <span v-if="row.directory" class="tree-chevron" :class="{ open: expanded.has(dirKey(row.root, row.path)) }">
                  <UiIcon name="chevron" :size="12" />
                </span>
                <span v-else class="tree-chevron-spacer" aria-hidden="true"></span>
                <UiIcon :name="row.directory ? 'folder' : 'file'" :size="14" />
                <span class="tree-name">{{ row.name }}</span>
                <span v-if="row.directory && loadingDirs.has(dirKey(row.root, row.path))" class="loading-indicator tree-spinner"></span>
              </button>
              <button
                v-if="!mentionDisabled && row.path"
                type="button"
                class="icon-button tree-mention"
                :aria-label="`引用 ${row.name}`"
                title="引用到输入框 (@)"
                @click.stop="mentionNode(row.root, row.path, row.directory)"
              ><UiIcon name="at" :size="13" /></button>
            </template>
          </div>
        </template>
      </div>

      <section v-if="activeFile" class="explorer-preview" aria-label="代码预览">
        <header class="preview-header">
          <div class="preview-meta">
            <strong :title="activeFile.path">{{ activeFile.path.slice(activeFile.path.lastIndexOf('/') + 1) }}</strong>
            <small :title="activeFile.path">{{ activeFile.path }}</small>
          </div>
          <div class="preview-actions">
            <span v-if="preview" class="preview-badge">{{ activeLanguage.label }}</span>
            <span v-if="preview && !preview.binary" class="preview-stats">{{ preview.totalLines }} 行 · {{ formatFileSize(preview.size) }}</span>
            <button
              v-if="!mentionDisabled"
              type="button"
              class="preview-action-btn"
              title="将此文件加入输入框 @ 引用"
              @click="mentionNode(activeFile.root, activeFile.path, false)"
            ><UiIcon name="at" :size="13" /><span>引用</span></button>
            <button
              v-if="preview && !preview.binary"
              type="button"
              class="icon-button"
              :aria-label="copied ? '已复制' : '复制代码'"
              :title="copied ? '已复制' : '复制文件内容'"
              @click="copyPreview"
            ><UiIcon :name="copied ? 'check' : 'copy'" :size="14" /></button>
            <button type="button" class="icon-button" aria-label="关闭代码预览" title="关闭预览" @click="activeFile = null; preview = null"><UiIcon name="close" :size="14" /></button>
          </div>
        </header>

        <div v-if="previewLoading && !preview" class="preview-empty"><span class="loading-indicator"></span><span>正在读取文件…</span></div>
        <div v-else-if="previewError" class="preview-empty is-error" role="alert">{{ previewError }}</div>
        <div v-else-if="preview?.binary" class="preview-empty">
          <UiIcon name="file" :size="20" />
          <p>二进制文件，暂不支持文本预览（{{ formatFileSize(preview.size) }}）</p>
        </div>
        <div v-else-if="preview" class="preview-code-wrap">
          <p v-if="preview.truncated" class="preview-truncated">文件超过 256 KB，当前仅预览前段内容。</p>
          <div class="code-viewer" tabindex="0">
            <table>
              <tbody>
                <tr v-for="(html, index) in highlightedLines" :key="index">
                  <td class="line-no" aria-hidden="true">{{ index + 1 }}</td>
                  <td class="line-code"><code v-html="html || '&nbsp;'"></code></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.workspace-explorer {
  position: relative;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  min-width: 320px;
  max-width: 760px;
  height: 100%;
  border-left: 0.5px solid var(--border);
  background: var(--surface);
  color: var(--text);
  overflow: hidden;
}
.explorer-resize {
  position: absolute;
  top: 0;
  bottom: 0;
  left: -3px;
  width: 6px;
  cursor: col-resize;
  z-index: 4;
}
.explorer-resize:hover,
.explorer-resize.dragging {
  background: var(--accent-soft);
}
.explorer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 10px 9px 14px;
  border-bottom: 0.5px solid var(--border);
  background: var(--surface);
  flex-shrink: 0;
}
.explorer-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: var(--font-caption);
  font-weight: 600;
  color: var(--text);
}
.explorer-title > .ui-icon {
  color: var(--accent);
}
.explorer-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.explorer-actions .icon-button {
  width: 28px;
  height: 28px;
  min-height: 28px;
}
.explorer-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 9px 10px;
  padding: 0 8px 0 10px;
  height: 32px;
  border: 0.5px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--page);
  color: var(--muted);
  flex-shrink: 0;
  transition: border-color .16s ease, box-shadow .16s ease, background-color .16s ease;
}
.explorer-search:focus-within {
  border-color: var(--accent);
  background: var(--surface);
  box-shadow: 0 0 0 3px var(--focus-ring);
}
.explorer-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font-size: var(--font-caption);
  outline: none;
}
.explorer-search input:focus-visible {
  outline: none;
}
.explorer-search .icon-button {
  width: 24px;
  height: 24px;
  min-height: 24px;
  padding: 4px;
}
.explorer-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.explorer-tree {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 2px 6px 10px;
}
.explorer-body.has-preview .explorer-tree {
  flex: 0 0 38%;
  min-height: 120px;
  border-bottom: 0.5px solid var(--border);
}
.explorer-status {
  margin: 16px 10px;
  font-size: var(--font-caption);
  color: var(--muted);
  line-height: 1.6;
}
.explorer-status.is-error,
.preview-empty.is-error {
  color: var(--danger);
}
.tree-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 1px 0;
  border-radius: var(--radius-sm);
  padding-right: 4px;
  transition: background-color .14s ease, color .14s ease;
}
.tree-row:hover {
  background: var(--hover);
}
.tree-row.selected {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 550;
}
.tree-row.selected::before {
  content: '';
  position: absolute;
  left: 0;
  top: 5px;
  bottom: 5px;
  width: 2.5px;
  border-radius: 3px;
  background: var(--accent);
}
.tree-row.is-root {
  margin-top: 4px;
  font-weight: 600;
}
.tree-row-main {
  flex: 1;
  min-width: 0;
  min-height: 28px;
  display: flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: transparent;
  color: inherit;
  padding: 4px 4px;
  text-align: left;
  font-size: var(--font-caption);
  cursor: pointer;
}
.tree-row-main:hover:not(:disabled) {
  background: transparent;
}
.tree-chevron {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: var(--muted);
  transition: transform 0.14s ease;
}
.tree-chevron.open {
  transform: rotate(90deg);
}
.tree-chevron-spacer {
  width: 14px;
  flex-shrink: 0;
}
.tree-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-search-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.tree-subpath {
  font-size: 11px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-mention {
  opacity: 0;
  width: 24px;
  height: 24px;
  min-height: 24px;
  padding: 4px;
  flex-shrink: 0;
}
.tree-row:hover .tree-mention,
.tree-row:focus-within .tree-mention {
  opacity: 1;
}
.tree-notice {
  padding: 5px 6px;
  font-size: 11px;
  color: var(--faint);
}
.tree-spinner {
  margin-left: auto;
}
.explorer-preview {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--code-bg);
}
.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px 8px 12px;
  border-bottom: 0.5px solid var(--border);
  background: var(--surface);
  flex-shrink: 0;
}
.preview-meta {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.preview-meta strong {
  font-size: var(--font-caption);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-meta small {
  font-size: 11px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.preview-actions .icon-button {
  width: 26px;
  height: 26px;
  min-height: 26px;
  padding: 5px;
}
.preview-badge {
  padding: 2px 7px;
  border-radius: 999px;
  border: 0.5px solid var(--border);
  background: var(--subtle);
  font-size: 11px;
  font-weight: 500;
  color: var(--muted);
}
.preview-stats {
  font-size: 11px;
  color: var(--faint);
  font-variant-numeric: tabular-nums;
}
.preview-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 25px;
  min-height: 25px;
  padding: 0 8px;
  border: 0.5px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text);
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
}
.preview-action-btn:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
.preview-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px 16px;
  color: var(--muted);
  font-size: var(--font-caption);
  text-align: center;
}
.preview-empty p {
  margin: 0;
}
.preview-code-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.preview-truncated {
  margin: 0;
  padding: 6px 12px;
  border-bottom: 0.5px solid var(--border);
  background: var(--subtle);
  color: var(--muted);
  font-size: 11px;
}
.code-viewer {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 8px 0;
  font-family: ui-monospace, 'Cascadia Code', SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.62;
}
.code-viewer table {
  width: 100%;
  border-collapse: collapse;
}
.code-viewer tr:hover {
  background: color-mix(in srgb, var(--hover) 65%, transparent);
}
.line-no {
  width: 1%;
  min-width: 40px;
  padding: 0 10px 0 12px;
  border-right: 0.5px solid color-mix(in srgb, var(--border) 70%, transparent);
  text-align: right;
  user-select: none;
  color: var(--faint);
  font-variant-numeric: tabular-nums;
  vertical-align: top;
}
.line-code {
  padding: 0 14px 0 10px;
  white-space: pre;
}
.line-code :deep(.tok-comment) {
  color: var(--faint);
  font-style: italic;
}
.line-code :deep(.tok-keyword) {
  color: var(--accent);
  font-weight: 560;
}
.line-code :deep(.tok-string) {
  color: var(--syntax-string);
}
.line-code :deep(.tok-number) {
  color: var(--syntax-number);
}
.line-code :deep(.tok-annotation) {
  color: var(--syntax-annotation);
}
@media (max-width: 960px) {
  .workspace-explorer {
    position: absolute;
    inset: 0;
    width: 100% !important;
    max-width: none;
    z-index: 15;
    border-left: 0;
  }
  .explorer-resize {
    display: none;
  }
}
</style>

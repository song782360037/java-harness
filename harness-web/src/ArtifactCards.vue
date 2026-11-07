<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from './api'
import { absolutePath, artifactKind, fileName, folderOf, formatSize, numberedLines, stateLabel, type Artifact, type ArtifactContent } from './artifacts'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ runId: string; workspace: string; revision: string }>()
const artifacts = ref<Artifact[]>([])
const notice = ref('')
const error = ref('')
const menu = ref<{ changeId: string; x: number; y: number } | null>(null)
const menuElement = ref<HTMLElement>()
const viewer = ref<HTMLDialogElement>()
const viewing = ref<{ artifact: Artifact; lines: string[] | null; error: string } | null>(null)
const maxLines = 5000
let loading = false, dirty = false, disposed = false, viewToken = 0, noticeTimer: ReturnType<typeof setTimeout> | undefined
let menuTrigger: HTMLElement | null = null, viewerTrigger: HTMLElement | null = null
async function load() {
  if (loading) { dirty = true; return }
  loading = true
  try {
    const values = await api<Artifact[]>(`/runs/${props.runId}/artifacts`)
    if (!disposed) artifacts.value = values
  } catch { /* 产物只是汇总视图，读取失败时保留上次结果，文件修改卡片仍可用。 */ }
  finally { loading = false; if (dirty && !disposed) { dirty = false; void load() } }
}
const available = (artifact: Artifact) => artifact.state === 'FILE'
const shownLines = computed(() => viewing.value?.lines?.slice(0, maxLines) ?? [])
// 菜单按 id 取最新记录，打开期间列表刷新也能反映文件状态。
const menuArtifact = computed(() => artifacts.value.find(value => value.changeId === menu.value?.changeId) ?? null)

function openMenu(artifact: Artifact, event: MouseEvent) {
  const trigger = event.currentTarget as HTMLElement
  if (event.type !== 'contextmenu' && menu.value?.changeId === artifact.changeId) { closeMenu(true); return }
  const anchor = event.type === 'contextmenu' ? null : trigger.getBoundingClientRect()
  const x = anchor ? anchor.right - 180 : event.clientX, y = anchor ? anchor.bottom + 4 : event.clientY
  menuTrigger = event.type === 'contextmenu' ? trigger.querySelector<HTMLElement>('.artifact-more') : trigger
  menu.value = { changeId: artifact.changeId, x: Math.max(8, Math.min(x, window.innerWidth - 188)), y: Math.max(8, Math.min(y, window.innerHeight - 220)) }
  // 磁盘状态不随事件推送，打开菜单时顺带刷新，避免对已删除的文件给出可用操作。
  void load()
  void nextTick(() => menuElement.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus())
}
function closeMenu(restoreFocus = false) {
  if (!menu.value) return
  menu.value = null
  if (restoreFocus) menuTrigger?.focus()
}
function navigateMenu(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); closeMenu(true); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const items = [...(menuElement.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])]
  const index = items.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : (index + (event.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length
  items[next]?.focus()
}
function outside(event: Event) {
  const target = event.target as Node
  if (menu.value && !menuElement.value?.contains(target) && !menuTrigger?.contains(target)) closeMenu()
}
const dismiss = () => closeMenu()

function flash(message: string) {
  notice.value = message; clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = '' }, 2000)
}
async function run(action: (artifact: Artifact) => Promise<void>) {
  const artifact = menuArtifact.value
  closeMenu(true)
  if (!artifact) return
  error.value = ''
  try { await action(artifact) } catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败，请重试。' }
}
const system = (action: 'open' | 'reveal') => run(async artifact => {
  await api(`/file-changes/${artifact.changeId}/open`, { action })
})
const copyPath = () => run(async artifact => {
  const path = absolutePath(props.workspace, artifact.path)
  try { await navigator.clipboard.writeText(path) } catch { throw new Error(`复制失败，请手动复制：${path}`) }
  flash('已复制路径')
})
// 另存为保存的是文件当前内容，借用浏览器下载。
const saveAs = () => run(async artifact => {
  const content = await api<ArtifactContent>(`/file-changes/${artifact.changeId}/content`)
  const url = URL.createObjectURL(new Blob([content.content], { type: 'text/plain;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url; link.download = fileName(artifact.path); link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
})
async function view(artifact: Artifact | null, trigger: HTMLElement | null) {
  closeMenu()
  if (!artifact || !available(artifact)) return
  viewerTrigger = trigger
  const token = ++viewToken
  viewing.value = { artifact, lines: null, error: '' }
  await nextTick()
  viewer.value?.showModal()
  try {
    const content = await api<ArtifactContent>(`/file-changes/${artifact.changeId}/content`)
    if (token === viewToken && viewing.value) viewing.value.lines = numberedLines(content.content)
  } catch (cause) {
    if (token === viewToken && viewing.value) viewing.value.error = cause instanceof Error ? cause.message : '文件读取失败'
  }
}
function closeViewer() { viewToken++; viewing.value = null; viewerTrigger?.focus() }
onMounted(() => {
  void load()
  document.addEventListener('pointerdown', outside); document.addEventListener('focusin', outside)
  window.addEventListener('scroll', dismiss, true); window.addEventListener('resize', dismiss)
})
watch(() => props.revision, load)
onUnmounted(() => {
  disposed = true; clearTimeout(noticeTimer)
  document.removeEventListener('pointerdown', outside); document.removeEventListener('focusin', outside)
  window.removeEventListener('scroll', dismiss, true); window.removeEventListener('resize', dismiss)
})
</script>
<template>
  <section v-if="artifacts.length" class="artifacts" aria-label="本次产物">
    <h3>产物 <span>{{ artifacts.length }}</span></h3>
    <ul class="artifact-list">
      <li v-for="artifact in artifacts" :key="artifact.changeId" class="artifact-card" :class="[artifactKind(artifact.path).tone, { stale: !available(artifact) }]" @contextmenu.prevent="openMenu(artifact, $event)">
        <button type="button" class="artifact-main" :disabled="!available(artifact)" :title="available(artifact) ? '查看内容' : stateLabel(artifact.state)" @click="view(artifact, $event.currentTarget as HTMLElement)">
          <span class="artifact-icon"><UiIcon :name="artifactKind(artifact.path).icon" :size="16" /></span>
          <span class="artifact-text"><strong>{{ fileName(artifact.path) }}</strong><small>{{ available(artifact) ? [folderOf(artifact.path), formatSize(artifact.size), artifact.operation === 'create' ? '新建' : '修改'].filter(Boolean).join(' · ') : stateLabel(artifact.state) }}</small></span>
        </button>
        <button type="button" class="icon-button artifact-more" :aria-label="`更多操作：${artifact.path}`" title="更多操作" aria-haspopup="menu" :aria-expanded="menu?.changeId === artifact.changeId" @click.stop="openMenu(artifact, $event)"><UiIcon name="more" :size="16" /></button>
      </li>
    </ul>
    <p v-if="artifacts.some(artifact => !available(artifact))" class="artifact-notice">产物读取的是当前磁盘文件。文件不存在或无法访问时，可从下方文件修改记录查看本次差异，或在更多操作中复制路径检查。</p>
    <p v-if="error" class="dialog-error" role="alert">{{ error }}</p>
    <p v-else-if="notice" class="artifact-notice" role="status">{{ notice }}</p>
    <div v-if="menu && menuArtifact" ref="menuElement" class="session-menu artifact-menu" :style="{ left: `${menu.x}px`, top: `${menu.y}px` }" role="menu" :aria-label="`${menuArtifact.path} 的操作`" @keydown="navigateMenu">
      <button type="button" role="menuitem" tabindex="-1" :disabled="!available(menuArtifact)" @click="view(menuArtifact, menuTrigger)"><UiIcon name="eye" :size="14" /><span>查看内容</span></button>
      <button type="button" role="menuitem" tabindex="-1" :disabled="!available(menuArtifact) || !menuArtifact.openable" :title="menuArtifact.openable ? undefined : '此类文件可能被系统直接执行，请打开所在文件夹'" @click="system('open')"><UiIcon name="file" :size="14" /><span>打开</span></button>
      <button type="button" role="menuitem" tabindex="-1" :disabled="!available(menuArtifact)" @click="system('reveal')"><UiIcon name="folder" :size="14" /><span>打开所在文件夹</span></button>
      <button type="button" role="menuitem" tabindex="-1" @click="copyPath"><UiIcon name="copy" :size="14" /><span>复制路径</span></button>
      <button type="button" role="menuitem" tabindex="-1" :disabled="!available(menuArtifact)" @click="saveAs"><UiIcon name="down" :size="14" /><span>另存为</span></button>
    </div>
  </section>
  <dialog v-if="viewing" ref="viewer" class="artifact-viewer" :aria-label="`${viewing.artifact.path} 的内容`" @close="closeViewer">
    <header><UiIcon :name="artifactKind(viewing.artifact.path).icon" :size="15" /><strong>{{ viewing.artifact.path }}</strong><button type="button" class="icon-button" aria-label="关闭" title="关闭" @click="viewer?.close()"><UiIcon name="close" :size="15" /></button></header>
    <p v-if="viewing.error" class="dialog-error" role="alert">{{ viewing.error }}</p>
    <p v-else-if="!viewing.lines" class="diff-format">正在读取…</p>
    <div v-else class="artifact-source" tabindex="0">
      <table><tbody><tr v-for="(line, index) in shownLines" :key="index"><td class="line-number">{{ index + 1 }}</td><td class="diff-code">{{ line }}</td></tr></tbody></table>
    </div>
    <p v-if="(viewing.lines?.length ?? 0) > maxLines" class="diff-format">仅显示前 {{ maxLines }} 行，完整内容请{{ viewing.artifact.openable ? '用“打开”查看' : '“另存为”后查看' }}。</p>
  </dialog>
</template>

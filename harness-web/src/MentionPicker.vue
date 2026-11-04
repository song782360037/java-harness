<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from './api'
import { fileMentionQuery, mentionLabel, type FileEntry } from './composer'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ sessionId?: string; workspace: boolean; multiRoot: boolean; disabled: boolean; text: string; input?: HTMLTextAreaElement }>()
const emit = defineEmits<{ select: [entry: FileEntry] }>()
const entries = ref<FileEntry[]>([]), query = ref<string | null>(null), error = ref(''), loading = ref(false), active = ref(0)
const panel = ref<HTMLElement>(), dismissedText = ref<string | null>(null)
const availableHeight = ref(300)
const open = computed(() => query.value !== null)
const ready = computed(() => !!props.sessionId && props.workspace)
const activeId = computed(() => open.value && !loading.value && !error.value && entries.value[active.value] ? `file-mention-${active.value}` : undefined)
let generation = 0, timer: ReturnType<typeof setTimeout> | undefined

// 关闭或切换会话时让进行中的搜索失效。
function close(dismiss = false) {
  if (dismiss) dismissedText.value = props.input?.value ?? props.text
  generation++; clearTimeout(timer); query.value = null; entries.value = []; loading.value = false; error.value = ''
}
// 输入停顿后再搜索，只保留最后一次请求的结果。
function search() {
  const version = ++generation
  clearTimeout(timer)
  if (!ready.value) { entries.value = []; loading.value = false; return }
  loading.value = true; error.value = ''
  timer = setTimeout(async () => {
    try {
      const values = await api<FileEntry[]>(`/sessions/${props.sessionId}/files?q=${encodeURIComponent(query.value || '')}`)
      if (version === generation) entries.value = values
    } catch (cause) { if (version === generation) error.value = cause instanceof Error ? cause.message : '搜索失败，请重试。' }
    finally { if (version === generation) loading.value = false }
  }, 120)
}
// 输入、光标移动及选区变化后，按光标处的 @ 文本更新候选。
function sync() {
  const input = props.input
  const value = input && document.activeElement === input && !props.disabled && dismissedText.value !== input.value
    ? fileMentionQuery(input.value, input.selectionStart, input.selectionEnd) : null
  if (value === null) { if (open.value) close(); return }
  const composerTop = input!.closest('.composer')?.getBoundingClientRect().top || 0
  const boundaryTop = input!.closest('.chat-panel')?.getBoundingClientRect().top || 0
  availableHeight.value = Math.max(0, composerTop - boundaryTop - 16)
  if (query.value === value.query) return
  query.value = value.query
  search()
}
function focus() { dismissedText.value = null; sync() }
function choose(entry: FileEntry) {
  if (props.disabled) return
  close(); emit('select', entry)
}
// 在发送快捷键之前处理候选，输入法确认和修饰键回车继续遵循原来的规则。
function keydown(event: KeyboardEvent): boolean {
  if (!open.value || event.isComposing || event.keyCode === 229 || event.repeat) return false
  if (event.key === 'Escape') { event.preventDefault(); close(true); return true }
  if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return false
  if (['ArrowDown', 'ArrowUp'].includes(event.key) && entries.value.length) {
    event.preventDefault(); active.value = (active.value + (event.key === 'ArrowDown' ? 1 : -1) + entries.value.length) % entries.value.length
    return true
  }
  if (['Enter', 'Tab'].includes(event.key) && entries.value[active.value] && !loading.value) {
    event.preventDefault(); choose(entries.value[active.value]!); return true
  }
  if (event.key === 'Enter' && loading.value) { event.preventDefault(); return true }
  return false
}
function outside(event: PointerEvent) { if (event.target !== props.input && !panel.value?.contains(event.target as Node)) close(true) }
watch(() => props.text, () => { void nextTick(sync) })
watch(() => [props.sessionId, props.disabled, props.workspace], () => { dismissedText.value = null; close() })
watch([query, entries], () => { active.value = 0 })
watch(activeId, () => { void nextTick(() => { if (activeId.value) panel.value?.querySelector(`#${activeId.value}`)?.scrollIntoView({ block: 'nearest' }) }) })
watch(() => props.input, (input, previous) => {
  for (const [name, callback] of [['focus', focus], ['click', focus], ['keyup', sync], ['select', sync], ['blur', sync]] as const) {
    previous?.removeEventListener(name, callback); input?.addEventListener(name, callback)
  }
}, { flush: 'post' })
onMounted(() => { document.addEventListener('pointerdown', outside); window.addEventListener('resize', sync) })
onUnmounted(() => {
  generation++; clearTimeout(timer); document.removeEventListener('pointerdown', outside); window.removeEventListener('resize', sync)
  for (const [name, callback] of [['focus', focus], ['click', focus], ['keyup', sync], ['select', sync], ['blur', sync]] as const) props.input?.removeEventListener(name, callback)
})
defineExpose({ keydown, open, activeId })
</script>
<template>
  <section v-if="open" ref="panel" class="mention-menu" :style="{ maxHeight: `${availableHeight}px` }" aria-label="引用文件">
    <header><span><UiIcon name="at" :size="15" /> 引用文件或目录</span><small>↑↓ 选择 · Enter 引用</small></header>
    <p v-if="!ready" role="status">先为会话选择工作区，再引用其中的文件。</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @mousedown.prevent @click="search">重试</button></p>
    <p v-else-if="loading && !entries.length" role="status">正在搜索…</p>
    <div v-else-if="entries.length" id="file-mention-options" class="mention-options" role="listbox" aria-label="匹配的文件和目录">
      <button v-for="(entry, index) in entries" :id="`file-mention-${index}`" :key="`${entry.root}:${entry.path}`" type="button" role="option" tabindex="-1" :aria-selected="active === index" :class="{ active: active === index }" :title="mentionLabel(entry, multiRoot)" @mouseenter="active = index" @mousedown.prevent @click="choose(entry)"><UiIcon :name="entry.directory ? 'folder' : 'file'" :size="15" /><span><strong>{{ entry.path.slice(entry.path.lastIndexOf('/') + 1) }}</strong><small>{{ mentionLabel(entry, multiRoot) }}</small></span></button>
    </div>
    <p v-else role="status">没有匹配的文件或目录</p>
  </section>
</template>
<style scoped>
.mention-menu { position: absolute; z-index: 12; left: 0; right: 0; bottom: calc(100% + 8px); padding: 6px; background: var(--surface); border: 0.5px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--popover-shadow); color: var(--text); display: flex; flex-direction: column; overflow: hidden; }
header { display: flex; flex-shrink: 0; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 10px; font-size: var(--font-caption); color: var(--muted); }
header > span { display: flex; align-items: center; gap: 6px; font-weight: 550; }
.mention-options { overflow-y: auto; min-height: 0; max-height: min(280px, 36dvh); overscroll-behavior: contain; }
.mention-options button { width: 100%; display: flex; align-items: center; gap: 10px; text-align: left; border: 0; background: transparent; padding: 8px 10px; border-radius: var(--radius-md); color: var(--muted); }
.mention-options button.active { background: var(--accent-soft); color: var(--accent); }
.mention-options button > span { min-width: 0; }
.mention-options strong, .mention-options small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mention-options strong { font-size: var(--font-body); font-weight: 550; color: var(--text); }
.mention-options button.active strong { color: var(--accent); }
.mention-options small { font-size: var(--font-caption); color: var(--muted); margin-top: 3px; }
p { font-size: var(--font-caption); color: var(--muted); padding: 8px 10px; line-height: 1.7; }
p button { padding: 4px 8px; font-size: var(--font-caption); }
</style>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from './api'
import { completePluginCommand, fetchPluginCommands, matchingPluginCommands, slashCommandQuery, type PluginCommand } from './pluginCommands'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ sessionId?: string; disabled: boolean; text: string; input?: HTMLTextAreaElement }>()
const emit = defineEmits<{ select: [value: string] }>()
const commands = ref<PluginCommand[]>([]), query = ref<string | null>(null), error = ref(''), loading = ref(false), active = ref(0)
const panel = ref<HTMLElement>(), dismissedText = ref<string | null>(null)
const availableHeight = ref(300)
const open = computed(() => query.value !== null)
const matching = computed(() => matchingPluginCommands(commands.value, query.value || ''))
const activeId = computed(() => open.value && !loading.value && !error.value && matching.value[active.value] ? `plugin-command-${active.value}` : undefined)
let generation = 0

// 使关闭或切换会话前发出的请求失效，不保留上一范围的候选项。
function close(dismiss = false) {
  if (dismiss) dismissedText.value = props.input?.value ?? props.text
  generation++; query.value = null; commands.value = []; loading.value = false; error.value = ''
}
// 每次唤起重新读取启用选择，安装、停用或范围变化在下一次打开时生效。
async function load() {
  const version = ++generation
  commands.value = []; error.value = ''; loading.value = true
  try { const values = await fetchPluginCommands(api, props.sessionId); if (version === generation) commands.value = values }
  catch { if (version === generation) error.value = '命令读取失败，请重试。' }
  finally { if (version === generation) loading.value = false }
}
// 输入、光标移动及选区变化后，只在首个命令标记中展示补全。
function sync() {
  const input = props.input
  const value = input && document.activeElement === input && !props.disabled && dismissedText.value !== input.value
    ? slashCommandQuery(input.value, input.selectionStart, input.selectionEnd) : null
  if (value === null) { if (open.value) close(); return }
  const composerTop = input!.closest('.composer')?.getBoundingClientRect().top || 0
  const boundaryTop = input!.closest('.chat-panel')?.getBoundingClientRect().top || 0
  availableHeight.value = Math.max(0, composerTop - boundaryTop - 16)
  const opening = !open.value
  query.value = value
  if (opening) void load()
}
// 用户明确回到输入框时，允许再次打开刚关闭的候选菜单。
function focus() { dismissedText.value = null; sync() }
// 选择只填入草稿并恢复光标，发送仍由用户确认。
async function choose(command: PluginCommand) {
  if (props.disabled || loading.value) return
  const value = completePluginCommand(props.input?.value || props.text, command.id)
  if (value.text.length > 4000) { error.value = '填入命令后超过 4000 字符，请先缩短消息。'; return }
  close(true); emit('select', value.text)
  await nextTick(); props.input?.focus(); props.input?.setSelectionRange(value.caret, value.caret)
}
// 在发送快捷键之前处理补全，输入法确认和修饰键回车继续遵循原来的规则。
function keydown(event: KeyboardEvent): boolean {
  if (!open.value || event.isComposing || event.keyCode === 229 || event.repeat) return false
  if (event.key === 'Escape') { event.preventDefault(); close(true); return true }
  if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) { if (event.key === 'Tab') close(true); return false }
  if (event.key === 'Enter' && loading.value) { event.preventDefault(); return true }
  if (['ArrowDown', 'ArrowUp'].includes(event.key) && matching.value.length) {
    event.preventDefault(); active.value = (active.value + (event.key === 'ArrowDown' ? 1 : -1) + matching.value.length) % matching.value.length
    return true
  }
  if (['Enter', 'Tab'].includes(event.key) && matching.value[active.value]) {
    event.preventDefault(); void choose(matching.value[active.value]!); return true
  }
  if (event.key === 'Tab') close(true)
  return false
}
// 点击菜单与输入框之外时关闭，避免占用其他选择器。
function outside(event: PointerEvent) { if (event.target !== props.input && !panel.value?.contains(event.target as Node)) close(true) }
watch(() => props.text, () => { void nextTick(sync) })
watch(() => [props.sessionId, props.disabled], () => { dismissedText.value = null; close() })
watch([query, commands], () => { active.value = 0 })
watch(activeId, () => { void nextTick(() => { if (activeId.value) panel.value?.querySelector(`#${activeId.value}`)?.scrollIntoView({ block: 'nearest' }) }) })
watch(() => props.input, (input, previous) => {
  for (const [name, callback] of [['focus', focus], ['click', focus], ['keyup', sync], ['select', sync], ['blur', sync]] as const) {
    previous?.removeEventListener(name, callback); input?.addEventListener(name, callback)
  }
}, { flush: 'post' })
onMounted(() => { document.addEventListener('pointerdown', outside); window.addEventListener('resize', sync) })
onUnmounted(() => {
  generation++; document.removeEventListener('pointerdown', outside); window.removeEventListener('resize', sync)
  for (const [name, callback] of [['focus', focus], ['click', focus], ['keyup', sync], ['select', sync], ['blur', sync]] as const) props.input?.removeEventListener(name, callback)
})
defineExpose({ keydown, open, activeId })
</script>
<template>
  <section v-if="open" ref="panel" class="slash-command-menu" :style="{ maxHeight: `${availableHeight}px` }" aria-label="插件命令">
    <header><span><UiIcon name="cube" :size="15" /> 插件命令</span><small>↑↓ 选择 · Enter 填入</small></header>
    <p v-if="loading" role="status">正在读取命令…</p>
    <p v-else-if="error" role="alert">{{ error }} <button type="button" @mousedown.prevent @click="load">重试</button></p>
    <div v-else-if="matching.length" id="plugin-command-options" class="slash-command-options" role="listbox" aria-label="可用插件命令">
      <button v-for="(command, index) in matching" :id="`plugin-command-${index}`" :key="command.id" type="button" role="option" tabindex="-1" :aria-selected="active === index" :class="{ active: active === index }" :title="`/${command.id} · ${command.description}`" @mouseenter="active = index" @mousedown.prevent @click="choose(command)"><span><strong>/{{ command.id }}</strong><small>{{ command.description }}</small></span><UiIcon v-if="active === index" name="chevron" :size="15" /></button>
    </div>
    <p v-else role="status">{{ commands.length ? '没有匹配的插件命令，试试其他关键词。' : '暂无可用命令，请在设置 → 扩展中启用带命令的插件。' }}</p>
    <footer>选择后可补充任务要求，再发送消息。<span>Esc 关闭</span></footer>
  </section>
</template>
<style scoped>
.slash-command-menu { position: absolute; z-index: 12; left: 0; right: 0; bottom: calc(100% + 8px); padding: 6px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--popover-shadow); color: var(--text); display: flex; flex-direction: column; overflow: hidden; }
header, footer { display: flex; flex-shrink: 0; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 10px; font-size: calc(12rem / 14); color: var(--muted); }
header > span { display: flex; align-items: center; gap: 6px; font-weight: 550; }
header small, footer { font-size: calc(11rem / 14); }
footer { border-top: 1px solid var(--border); margin-top: 6px; flex-wrap: wrap; }
footer > span { margin-left: auto; }
.slash-command-options { overflow-y: auto; min-height: 0; max-height: min(250px, 32dvh); overscroll-behavior: contain; }
.slash-command-options button { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 10px; text-align: left; border: 0; background: transparent; padding: 10px; border-radius: var(--radius-md); }
.slash-command-options button.active { background: var(--accent-soft); color: var(--accent); }
.slash-command-options button > span { min-width: 0; }
.slash-command-options strong, .slash-command-options small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.slash-command-options strong { font-size: calc(13rem / 14); font-weight: 550; }
.slash-command-options small { font-size: calc(11rem / 14); color: var(--muted); margin-top: 5px; }
p { font-size: calc(12rem / 14); color: var(--muted); padding: 8px 10px; line-height: 1.7; }
p button { padding: 4px 8px; font-size: calc(11rem / 14); }
</style>

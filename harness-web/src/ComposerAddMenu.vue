<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ disabled: boolean; workspace: boolean }>()
const emit = defineEmits<{ files: [files: File[]]; mention: []; command: [] }>()
const open = ref(false), root = ref<HTMLElement>(), picker = ref<HTMLInputElement>(), trigger = ref<HTMLButtonElement>()

function toggle() { open.value = !open.value; if (open.value) void nextTick(() => root.value?.querySelector<HTMLElement>('[role=menuitem]:not(:disabled)')?.focus()) }
function close(restore = false) { open.value = false; if (restore) trigger.value?.focus() }
function act(action: 'files' | 'mention' | 'command') {
  close()
  if (action === 'files') picker.value?.click()
  else if (action === 'mention') emit('mention')
  else emit('command')
}
// 选择后清空文件框，同一文件可以再次选择。
function picked() {
  const files = Array.from(picker.value?.files || [])
  if (picker.value) picker.value.value = ''
  if (files.length) emit('files', files)
}
// 菜单内用方向键移动焦点，Esc 关闭并回到按钮。
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(true); return }
  if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return
  event.preventDefault()
  const items = Array.from(root.value?.querySelectorAll<HTMLElement>('[role=menuitem]:not(:disabled)') || [])
  const index = items.indexOf(document.activeElement as HTMLElement)
  items[(index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
}
function outside(event: PointerEvent) { if (open.value && !root.value?.contains(event.target as Node)) close() }
// Tab 移出菜单或开始提交时收起菜单。
function focusout(event: FocusEvent) { if (open.value && !root.value?.contains(event.relatedTarget as Node | null)) close() }
watch(() => props.disabled, disabled => { if (disabled) close() })
onMounted(() => document.addEventListener('pointerdown', outside))
onUnmounted(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
  <div ref="root" class="composer-add" @keydown="keydown" @focusout="focusout">
    <button ref="trigger" type="button" class="icon-button composer-add-button" :disabled="disabled" aria-haspopup="menu" :aria-expanded="open" aria-label="添加内容" title="添加图片、文件或引用" @click="toggle"><UiIcon name="plus" :size="17" /></button>
    <div v-if="open" class="composer-add-menu" role="menu" aria-label="添加内容">
      <button type="button" role="menuitem" @click="act('files')"><UiIcon name="paperclip" :size="16" /><span><strong>上传图片或文件</strong><small>图片、文本文件，也可粘贴或拖入</small></span></button>
      <button type="button" role="menuitem" :disabled="!workspace" @click="act('mention')"><UiIcon name="at" :size="16" /><span><strong>引用文件或目录</strong><small>{{ workspace ? '从当前工作区选择，或输入 @' : '需要先选择工作区' }}</small></span></button>
      <button type="button" role="menuitem" @click="act('command')"><UiIcon name="cube" :size="16" /><span><strong>插件命令</strong><small>或在开头输入 /</small></span></button>
    </div>
    <input ref="picker" type="file" multiple hidden @change="picked" />
  </div>
</template>
<style scoped>
.composer-add { position: relative; flex-shrink: 0; }
.composer-add-button { width: 32px; height: 32px; border-radius: var(--radius-md); color: var(--muted); }
.composer-add-button[aria-expanded=true] { background: var(--accent-soft); color: var(--accent); }
.composer-add-menu { position: absolute; z-index: 13; left: 0; bottom: calc(100% + 8px); width: max-content; max-width: min(300px, calc(100vw - 48px)); padding: 6px; background: var(--surface); border: 0.5px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--popover-shadow); display: flex; flex-direction: column; }
.composer-add-menu button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 9px 10px; border: 0; background: transparent; border-radius: var(--radius-md); text-align: left; color: var(--muted); }
.composer-add-menu button:hover:not(:disabled), .composer-add-menu button:focus-visible { background: var(--accent-soft); color: var(--accent); outline: 0; }
.composer-add-menu button:disabled { opacity: .5; cursor: not-allowed; }
.composer-add-menu span { min-width: 0; }
.composer-add-menu strong, .composer-add-menu small { display: block; }
.composer-add-menu strong { font-size: var(--font-body); font-weight: 550; color: var(--text); }
.composer-add-menu button:hover:not(:disabled) strong, .composer-add-menu button:focus-visible strong { color: var(--accent); }
.composer-add-menu small { font-size: var(--font-caption); color: var(--muted); margin-top: 3px; }
@media (max-width: 780px) { .composer-add-button { width: 40px; height: 40px; } }
</style>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ id: string; modelValue: string; options: readonly { value: string; label: string }[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const root = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
const menu = ref<HTMLElement>()
const open = ref(false)
const position = ref<Record<string, string>>({})
const selectedLabel = computed(() => props.options.find(option => option.value === props.modelValue)?.label)

// 关闭菜单，选择或取消时返回入口；点击外部时保留外部焦点。
function close(restoreFocus = false) { open.value = false; if (restoreFocus) trigger.value?.focus() }
// 按可用空间向上或向下展开，并聚焦当前选项。
async function show() {
  const bounds = trigger.value?.getBoundingClientRect()
  if (!bounds) return
  const below = window.innerHeight - bounds.bottom - 12
  const above = bounds.top - 12
  const upwards = below < props.options.length * 38 + 12 && above > below
  position.value = { left: `${bounds.left}px`, width: `${bounds.width}px`, maxHeight: `${Math.max(80, upwards ? above : below)}px`,
    ...(upwards ? { bottom: `${window.innerHeight - bounds.top + 6}px` } : { top: `${bounds.bottom + 6}px` }) }
  open.value = true
  await nextTick()
  menu.value?.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus()
}
// 选择后立即保存，保持已有偏好的生效方式。
function choose(value: string) { emit('update:modelValue', value); close(true) }
// 方向键和首尾键移动焦点，Enter 或空格使用按钮原生选择行为。
function navigate(event: KeyboardEvent) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  if (!open.value) { void show(); return }
  const options = [...(menu.value?.querySelectorAll<HTMLButtonElement>('button') || [])]
  const index = options.indexOf(event.target as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
    : event.key === 'ArrowDown' ? (index + 1) % options.length : (index <= 0 ? options.length : index) - 1
  options[next]?.focus()
}
// Tab 离开、点击外部或页面滚动时关闭菜单。
function focusOut(event: FocusEvent) { if (!root.value?.contains(event.relatedTarget as Node)) close() }
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) close() }
function scroll(event: Event) { if (!menu.value?.contains(event.target as Node)) close() }
function resize() { close() }
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('scroll', scroll, true); window.addEventListener('resize', resize) })
onUnmounted(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('scroll', scroll, true); window.removeEventListener('resize', resize) })
</script>
<template>
  <div ref="root" class="preference-select" @focusout="focusOut" @keydown="navigate" @keydown.esc.prevent.stop="close(true)">
    <button :id="id" ref="trigger" type="button" class="preference-select-trigger" aria-haspopup="menu" :aria-expanded="open" :aria-controls="open ? id + '-options' : undefined" :aria-labelledby="id + '-label ' + id + '-value'" :aria-describedby="id + '-help'" @click="open ? close() : show()">
      <span :id="id + '-value'">{{ selectedLabel }}</span><UiIcon name="down" :size="14" />
    </button>
    <div v-if="open" :id="id + '-options'" ref="menu" class="selector-popover preference-select-menu" :style="position" role="menu" :aria-labelledby="id + '-label'">
      <button v-for="option in options" :key="option.value" type="button" class="selector-option" role="menuitemradio" :aria-checked="option.value === modelValue" tabindex="-1" @click="choose(option.value)"><span>{{ option.label }}</span><UiIcon v-if="option.value === modelValue" name="check" :size="15" /></button>
    </div>
  </div>
</template>

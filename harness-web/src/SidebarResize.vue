<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
const props = defineProps<{ width: number }>()
const emit = defineEmits<{ 'update:width': [value: number] }>()
const dragging = ref(false)
let pointerId: number | null = null
let startX = 0
let startWidth = 0
let activeHandle: HTMLElement | null = null
// 鼠标离开分隔条后仍接收拖动事件，松开或取消时结束。
function start(event: PointerEvent) {
  if (event.button !== 0 || pointerId !== null) return
  event.preventDefault()
  const handle = event.currentTarget as HTMLElement
  handle.setPointerCapture(event.pointerId)
  handle.focus({ preventScroll: true })
  activeHandle = handle
  pointerId = event.pointerId; startX = event.clientX; startWidth = props.width
  dragging.value = true
  document.addEventListener('keydown', cancel)
}
// 将拖动宽度限制在侧栏允许的范围内。
function move(event: PointerEvent) {
  if (event.pointerId === pointerId) update(startWidth + event.clientX - startX)
}
// 指针释放和捕获丢失都停止本次拖动。
function stop(event: PointerEvent) {
  if (event.pointerId !== pointerId) return
  pointerId = null; dragging.value = false
  activeHandle = null
  document.removeEventListener('keydown', cancel)
}
// Escape 撤销本次拖动，并释放指针捕获。
function cancel(event: KeyboardEvent) {
  if (event.key !== 'Escape' || pointerId === null) return
  event.preventDefault(); event.stopPropagation()
  update(startWidth)
  const id = pointerId, handle = activeHandle
  pointerId = null; dragging.value = false; activeHandle = null
  document.removeEventListener('keydown', cancel)
  if (handle?.hasPointerCapture(id)) handle.releasePointerCapture(id)
}
onUnmounted(() => document.removeEventListener('keydown', cancel))
// 键盘与指针共用相同的宽度边界。
function update(value: number) { emit('update:width', Math.max(200, Math.min(400, value))) }
// 方向键微调，Home 和 End 分别跳到最小和最大宽度。
function keydown(event: KeyboardEvent) {
  const widths: Record<string, number> = { ArrowLeft: props.width - 10, ArrowRight: props.width + 10, Home: 200, End: 400 }
  const value = widths[event.key]
  if (value === undefined) return
  event.preventDefault(); update(value)
}
</script>
<template>
  <div class="sidebar-resize" :class="{ dragging }" role="separator" tabindex="0" aria-label="调整侧栏宽度" aria-orientation="vertical" aria-valuemin="200" aria-valuemax="400" :aria-valuenow="width" @pointerdown="start" @pointermove="move" @pointerup="stop" @pointercancel="stop" @lostpointercapture="stop" @keydown="keydown"></div>
</template>

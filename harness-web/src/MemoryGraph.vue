<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { memoryCamera, memoryLayout, type MemoryEntry, type MemoryRelation } from './memory'

const props = defineProps<{ entries: MemoryEntry[]; relations: MemoryRelation[]; selectedId: string; group: string }>()
const emit = defineEmits<{ select: [id: string] }>()
const svg = ref<SVGSVGElement>()
const camera = ref({ x: 0, y: 0, zoom: 1 })
const size = ref({ width: 800, height: 600 })
const positions = ref<Record<string, { x: number; y: number }>>({})
const highlightedId = ref('')
const layout = computed(() => memoryLayout(props.entries, size.value.width, size.value.height))
const topicCharacters = computed(() => {
  const columns = Math.max(1, Math.ceil(Math.sqrt(layout.value.centers.length * size.value.width / Math.max(1, size.value.height))))
  return Math.max(2, Math.floor((size.value.width - 48) / columns / 14) - 1)
})
const nodes = computed(() => layout.value.nodes.map(node => ({ ...node, ...positions.value[node.id] })))
const nodeMap = computed(() => new Map(nodes.value.map(node => [node.id, node])))
const selected = computed(() => nodeMap.value.get(props.selectedId))
const topicLabels = computed(() => layout.value.centers.filter(center => topicCharacters.value > 3 || center.name === props.group
  || center.name === selected.value?.group || center.name === nodeMap.value.get(highlightedId.value)?.group))
const related = computed(() => {
  const ids = new Set<string>([props.selectedId])
  props.relations.forEach(edge => {
    if (edge.fromId === props.selectedId) ids.add(edge.toId)
    if (edge.toId === props.selectedId) ids.add(edge.fromId)
  })
  if (selected.value) nodes.value.forEach(node => {
    if (node.tags.some(tag => selected.value!.tags.includes(tag))) ids.add(node.id)
  })
  return ids
})
const edges = computed(() => props.relations.filter(edge => nodeMap.value.has(edge.fromId) && nodeMap.value.has(edge.toId)))
const tagEdges = computed(() => selected.value ? nodes.value.filter(node => node.id !== props.selectedId
  && node.tags.some(tag => selected.value!.tags.includes(tag))) : [])
let drag: { pointer: number; id: string; x: number; y: number; moved: boolean } | undefined
let suppressClick = false

/** 把屏幕坐标转换为 SVG 坐标，适配窗口尺寸和缩放。 */
function point(event: PointerEvent) {
  const matrix = svg.value?.getScreenCTM()
  return matrix ? new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse()) : new DOMPoint()
}
function start(event: PointerEvent, id = '') {
  if (event.button !== 0 || drag) return
  const p = point(event)
  drag = { pointer: event.pointerId, id, x: p.x, y: p.y, moved: false }
  suppressClick = false
  svg.value?.setPointerCapture(event.pointerId)
}
function move(event: PointerEvent) {
  if (!drag || drag.pointer !== event.pointerId) return
  const p = point(event)
  const dx = p.x - drag.x, dy = p.y - drag.y
  if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 3) return
  drag.moved = true
  if (drag.id) {
    const node = nodeMap.value.get(drag.id)
    if (node) positions.value[drag.id] = { x: node.x + dx / camera.value.zoom, y: node.y + dy / camera.value.zoom }
  } else { camera.value.x += dx; camera.value.y += dy }
  drag.x = p.x; drag.y = p.y
}
function stop(event: PointerEvent) {
  if (!drag || drag.pointer !== event.pointerId) return
  suppressClick = drag.moved
  if (!drag.moved && drag.id) emit('select', drag.id)
  if (!drag.moved && !drag.id) emit('select', '')
  drag = undefined
  if (svg.value?.hasPointerCapture(event.pointerId)) svg.value.releasePointerCapture(event.pointerId)
}
function activate(event: MouseEvent, id: string) { if (event.detail === 0 && !suppressClick) emit('select', id) }
function zoom(factor: number) {
  const minimum = Math.min(.3, memoryCamera(layout.value, size.value.width, size.value.height).zoom)
  const value = Math.max(minimum, Math.min(3.5, camera.value.zoom * factor))
  const ratio = value / camera.value.zoom
  camera.value.x = size.value.width / 2 - (size.value.width / 2 - camera.value.x) * ratio
  camera.value.y = size.value.height / 2 - (size.value.height / 2 - camera.value.y) * ratio
  camera.value.zoom = value
}
function reset() { camera.value = memoryCamera(layout.value, size.value.width, size.value.height); positions.value = {} }
watch(() => `${size.value.width}:${size.value.height}:${layout.value.nodes.map(node => `${node.id}:${node.group}`).join('|')}`, reset, { immediate: true })
watch(() => props.entries.map(item => item.id).join(), () => { suppressClick = false })
const observer = new ResizeObserver(records => {
  const bounds = records[0]?.contentRect
  if (bounds) size.value = { width: Math.max(1, bounds.width), height: Math.max(1, bounds.height) }
})
onMounted(() => { if (svg.value) observer.observe(svg.value) })
onUnmounted(() => observer.disconnect())
</script>

<template>
  <div class="memory-graph">
    <svg ref="svg" :viewBox="`0 0 ${size.width} ${size.height}`" role="group" aria-label="记忆关系图，可拖动节点和画布，滚轮缩放"
      @pointerdown="start($event)" @pointermove="move" @pointerup="stop" @pointercancel="drag = undefined"
      @wheel.prevent="zoom($event.deltaY < 0 ? 1.08 : 1 / 1.08)">
      <g :transform="`translate(${camera.x} ${camera.y}) scale(${camera.zoom})`">
        <g class="memory-cluster-lines" aria-hidden="true">
          <line v-for="node in nodes" :key="node.id" :x1="node.x" :y1="node.y" :x2="layout.centers.find(c => c.name === node.group)?.x"
            :y2="layout.centers.find(c => c.name === node.group)?.y" :style="{ stroke: node.color }" :opacity="selectedId ? 0.06 : 0.22" />
        </g>
        <g class="memory-relation-lines">
          <line v-for="edge in edges" :key="`${edge.fromId}:${edge.toId}`" :x1="nodeMap.get(edge.fromId)!.x" :y1="nodeMap.get(edge.fromId)!.y"
            :x2="nodeMap.get(edge.toId)!.x" :y2="nodeMap.get(edge.toId)!.y" :style="{ stroke: nodeMap.get(edge.fromId)!.color }"
            :opacity="!selectedId || edge.fromId === selectedId || edge.toId === selectedId ? 0.7 : 0.1"><title>{{ edge.label }}</title></line>
          <line v-for="node in tagEdges" :key="`tag:${node.id}`" :x1="selected!.x" :y1="selected!.y" :x2="node.x" :y2="node.y"
            :style="{ stroke: node.color }" stroke-dasharray="4 6" opacity="0.55"><title>相同标签</title></line>
        </g>
        <text v-for="center in topicLabels" :key="center.name" :x="center.x" :y="center.labelY"
          class="memory-cluster-label" :style="{ fill: center.color, fontSize: `${14 / camera.zoom}px` }" :opacity="group && group !== center.name ? 0.25 : 0.9"><title>{{ center.name }}</title>{{ center.name.length > topicCharacters ? center.name.slice(0, topicCharacters) + '…' : center.name }}</text>
        <g v-for="node in nodes" :key="node.id" :transform="`translate(${node.x} ${node.y})`" class="memory-node"
          :class="{ 'is-selected': node.id === selectedId }" :style="{ color: node.color }"
          :opacity="(selectedId && !related.has(node.id)) || (group && node.group !== group) ? 0.15 : 1"
          role="button" tabindex="0" :aria-label="node.title" :aria-pressed="node.id === selectedId"
          @pointerdown.stop="start($event, node.id)" @click.stop="activate($event, node.id)"
          @mouseenter="highlightedId = node.id" @mouseleave="highlightedId = ''" @focus="highlightedId = node.id" @blur="highlightedId = ''"
          @keydown.enter.prevent="emit('select', node.id)" @keydown.space.prevent="emit('select', node.id)">
          <title>{{ node.title }}</title>
          <circle r="19" fill="transparent" />
          <circle :r="node.id === selectedId ? 17 : 10" fill="currentColor" :opacity="node.id === selectedId ? 0.16 : 0.08" />
          <circle class="memory-node-core" :r="node.id === selectedId ? 9 : 5" stroke="currentColor" :stroke-dasharray="node.status !== 'ACTIVE' ? '2 2' : undefined" />
          <circle r="2" fill="currentColor" />
          <text v-if="node.id === selectedId || node.id === highlightedId" :x="node.x * camera.zoom + camera.x > size.width / 2 ? -13 : 13" :text-anchor="node.x * camera.zoom + camera.x > size.width / 2 ? 'end' : 'start'" y="5" fill="currentColor" :style="{ fontSize: `${12 / camera.zoom}px` }">{{ node.title.length > 16 ? node.title.slice(0, 16) + '…' : node.title }}</text>
        </g>
      </g>
    </svg>
    <div class="memory-graph-controls"><button aria-label="缩小图谱" @click="zoom(1 / 1.2)">−</button><span>{{ Math.round(camera.zoom * 100) }}%</span><button aria-label="放大图谱" @click="zoom(1.2)">＋</button><button @click="reset">复位</button></div>
    <div class="memory-graph-hint">悬停查看标题，点击查看详情</div>
    <div class="memory-graph-legend"><span><i></i>已生效</span><span><i class="candidate"></i>未生效</span><span><b></b>显式关系</span><span><b class="dashed"></b>相同标签</span></div>
  </div>
</template>

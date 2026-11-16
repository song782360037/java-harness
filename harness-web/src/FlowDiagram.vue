<script setup lang="ts">
import { computed } from 'vue'
import { layoutFlow, NODE_HEIGHT, NODE_WIDTH, type FlowDefinition } from './flows'
import UiIcon from './UiIcon.vue'
/** counts 为各节点上在途事项的数量；currentKey 标出所选事项当前所在的节点。 */
const props = defineProps<{
  definition: FlowDefinition; counts?: Record<string, { total: number; waiting: boolean }>
  selectedKey?: string; currentKey?: string; interactive?: boolean
}>()
const emit = defineEmits<{ select: [key: string] }>()
const layout = computed(() => layoutFlow(props.definition))
const hint = { TRIGGER: '触发', AGENT: '岗位', HUMAN: '需要你处理', END: '终点' }
const icon = { TRIGGER: 'reasoning', AGENT: 'cube', HUMAN: 'shield', END: 'check' }
</script>

<template>
  <div class="flow-diagram" :style="{ width: `${layout.width}px`, height: `${layout.height}px` }">
    <div v-for="lane in layout.lanes" :key="lane.title" class="flow-lane" :class="{ human: lane.human }" :style="{ left: `${lane.x}px`, width: `${lane.width}px` }"><span>{{ lane.title }}</span></div>
    <svg :width="layout.width" :height="layout.height" :viewBox="`0 0 ${layout.width} ${layout.height}`" aria-hidden="true">
      <g v-for="(item, index) in layout.edges" :key="index" class="flow-edge" :class="{ back: item.back }"><path :d="item.curve" /><path class="flow-arrow" :d="item.arrow" /></g>
    </svg>
    <component :is="interactive ? 'button' : 'div'" v-for="item in layout.nodes" :key="item.node.key" :type="interactive ? 'button' : undefined" class="flow-node"
      :class="[item.node.type.toLowerCase(), { selected: item.node.key === selectedKey, current: item.node.key === currentKey }]"
      :style="{ left: `${item.x}px`, top: `${item.y}px`, width: `${NODE_WIDTH}px`, height: `${NODE_HEIGHT}px` }"
      :title="item.node.description || undefined" :aria-pressed="interactive ? item.node.key === selectedKey : undefined" @click="interactive && emit('select', item.node.key)">
      <strong><UiIcon :name="icon[item.node.type]" :size="13" /><span>{{ item.node.name }}</span></strong>
      <small>{{ item.node.description || hint[item.node.type] }}</small>
      <span v-if="counts?.[item.node.key]" class="flow-node-count" :class="{ waiting: counts[item.node.key].waiting }" :aria-label="`${counts[item.node.key].total} 件事项在此`">{{ counts[item.node.key].total }}</span>
    </component>
    <template v-for="(item, index) in layout.edges" :key="index"><span v-if="item.edge.label" class="flow-edge-label" :style="{ left: `${item.labelX}px`, top: `${item.labelY}px` }">{{ item.edge.label }}</span></template>
  </div>
</template>

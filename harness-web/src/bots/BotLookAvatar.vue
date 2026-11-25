<script setup lang="ts">
import { computed } from 'vue'
import { getBotAvatar, renderBotAvatarInner, type BotShape, type BotStatus } from './api'

const props = withDefaults(defineProps<{
  shape: BotShape | string
  color: string
  size?: number
  status?: BotStatus
  progress?: number
  showRing?: boolean
}>(), {
  size: 36,
  status: 'IDLE',
  progress: 0,
  showRing: false,
})

const radius = 22
const circumference = 2 * Math.PI * radius
const dashOffset = computed(() => circumference * (1 - Math.max(0, Math.min(1, props.progress))))
const innerSvg = computed(() => renderBotAvatarInner(props.shape, props.color))
const avatarLabel = computed(() => getBotAvatar(props.shape).name)
</script>

<template>
  <span
    class="bot-avatar"
    :style="{ width: `${size}px`, height: `${size}px`, '--bot-color': color }"
    :data-avatar="avatarLabel"
  >
    <svg class="bot-avatar-svg" viewBox="0 0 48 48" :width="size" :height="size" aria-hidden="true">
      <circle cx="24" cy="24" :r="showRing ? 20 : 22" :fill="color" fill-opacity="0.11" />
      <circle v-if="showRing" cx="24" cy="24" :r="radius" class="bot-ring-track" />
      <circle
        v-if="showRing"
        cx="24"
        cy="24"
        :r="radius"
        class="bot-ring-progress"
        :stroke-dasharray="circumference"
        :stroke-dashoffset="dashOffset"
      />
      <g
        class="bot-shape-glyph"
        :transform="showRing ? 'translate(2.4, 2.4) scale(0.9)' : undefined"
        v-html="innerSvg"
      />
    </svg>
    <span v-if="status === 'WORKING' || status === 'REPLYING'" class="bot-status-dot is-working" title="干活中"></span>
    <span v-else-if="status === 'QUEUED'" class="bot-status-dot is-queued" title="排队中"></span>
    <span v-else-if="status === 'FAILED'" class="bot-status-dot is-failed" title="任务失败">!</span>
  </span>
</template>

<script setup lang="ts">
import UiIcon from '../UiIcon.vue'
import type { BotMiniApp } from './api'

defineProps<{
  apps: { app: BotMiniApp; hot: boolean }[]
}>()

const emit = defineEmits<{
  restore: [appId: string]
  close: [appId: string]
}>()
</script>

<template>
  <div v-if="apps.length" class="miniapp-dock" aria-label="已收起的小程序">
    <div v-for="item in apps" :key="item.app.id" class="miniapp-dock-pill">
      <button type="button" class="miniapp-dock-open" @click="emit('restore', item.app.id)">
        <span class="miniapp-dock-dot" :class="{ 'is-hot': item.hot }" :title="item.hot ? '热保活中（实时接收新数据）' : '已挂起'"></span>
        <strong>{{ item.app.name }}</strong>
        <small>v{{ item.app.version }}</small>
      </button>
      <button type="button" class="miniapp-dock-close" aria-label="关闭小程序" @click="emit('close', item.app.id)">
        <UiIcon name="close" :size="12" />
      </button>
    </div>
  </div>
</template>

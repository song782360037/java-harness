<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from './api'
import type { MemoryUsage } from './memory'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ runId: string; revision: string }>()
const entries = ref<MemoryUsage[]>([])
const error = ref('')
let version = 0
const previewText = computed(() => entries.value.map(entry => entry.title).filter(Boolean).join(' · '))
/** 仅展示后端持久化的本次输入快照。 */
async function load() {
  const current = ++version
  try {
    const value = await api<MemoryUsage[]>(`/runs/${props.runId}/memories`)
    if (current === version) { entries.value = value; error.value = '' }
  } catch (cause) { if (current === version) error.value = cause instanceof Error ? cause.message : '记忆记录读取失败' }
}
watch(() => `${props.runId}:${props.revision}`, load, { immediate: true })
</script>
<template>
  <details v-if="entries.length" class="memory-usage">
    <summary class="memory-usage-trigger">
      <UiIcon name="memory" :size="14" class="memory-usage-icon" />
      <span class="memory-usage-label">本次引用记忆</span>
      <span class="memory-usage-count">{{ entries.length }}</span>
      <span v-if="previewText" class="memory-usage-preview">{{ previewText }}</span>
      <UiIcon name="chevron" :size="13" class="memory-usage-chevron" />
    </summary>
    <div class="memory-usage-panel">
      <ul class="memory-usage-list">
        <li v-for="entry in entries" :key="entry.memoryId" class="memory-usage-item">
          <div class="memory-usage-item-head">
            <strong class="memory-usage-title">{{ entry.title }}</strong>
            <span class="memory-usage-version" :title="`版本 ${entry.revision}`">v{{ entry.revision }}</span>
          </div>
          <p class="memory-usage-content">{{ entry.content }}</p>
        </li>
      </ul>
      <p v-if="error" class="memory-usage-error" role="alert"><span>{{ error }}</span><button type="button" @click="load">重试</button></p>
    </div>
  </details>
  <p v-else-if="error" class="run-notice" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
</template>

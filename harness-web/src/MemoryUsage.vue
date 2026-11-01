<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from './api'
import type { MemoryUsage } from './memory'
const props = defineProps<{ runId: string; revision: string }>()
const entries = ref<MemoryUsage[]>([])
const error = ref('')
let version = 0
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
  <details v-if="entries.length" class="memory-usage"><summary>本次引用的记忆 <span>{{ entries.length }}</span></summary>
    <div v-for="entry in entries" :key="entry.memoryId"><strong>{{ entry.title }}</strong><small>版本 {{ entry.revision }}</small><p>{{ entry.content }}</p></div>
    <p v-if="error" role="alert">{{ error }} <button @click="load">重试</button></p>
  </details>
  <p v-else-if="error" class="run-notice" role="alert">{{ error }} <button @click="load">重试</button></p>
</template>

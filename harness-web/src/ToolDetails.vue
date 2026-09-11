<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api, type ToolExecution } from './api'
const props = defineProps<{ runId: string; revision: string }>()
const tools = ref<ToolExecution[]>([])
const error = ref('')
let loading = false
let dirty = false
const labels: Record<string, string> = { PENDING: '等待执行', RUNNING: '正在执行', COMPLETED: '已完成', FAILED: '执行失败', SKIPPED: '本步未执行', CANCELLED: '已取消', INTERRUPTED: '已中断' }
async function load() {
  if (loading) { dirty = true; return }
  loading = true
  try { tools.value = await api<ToolExecution[]>(`/runs/${props.runId}/tools`); error.value = '' }
  catch { error.value = '工具记录读取失败' }
  finally { loading = false; if (dirty) { dirty = false; void load() } }
}
onMounted(load)
watch(() => props.revision, load)
</script>
<template>
  <div v-if="tools.length || error" class="tool-list" aria-label="工具执行记录">
    <p v-if="error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <details v-for="tool in tools" :key="tool.callId" class="tool-card">
      <summary>第 {{ tool.step }} 步 · {{ tool.name }} · {{ labels[tool.status] || tool.status }}</summary>
      <p>调用 ID：{{ tool.callId }}</p>
      <strong>参数</strong><pre>{{ tool.arguments }}</pre>
      <strong>结果</strong><pre>{{ tool.output || '等待工具结果…' }}</pre>
    </details>
  </div>
</template>
<style scoped>
.tool-list { margin-top: 16px; font-size: 12px; }
.tool-card { margin-top: 8px; padding: 12px; border-radius: 8px; background: var(--subtle); border: 1px solid var(--border); }
summary { cursor: pointer; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 260px; overflow-y: auto; font: inherit; }
</style>

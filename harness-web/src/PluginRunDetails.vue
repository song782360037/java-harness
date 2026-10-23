<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api } from './api'
interface Approval { id: string; pluginName: string; pluginVersion: string; serverName: string; toolName: string; arguments: string; status: string; detail: string }
const props = defineProps<{ runId: string; revision: string; active: boolean }>()
const emit = defineEmits<{ activity: [value: string] }>()
const approvals = ref<Approval[]>([]), error = ref(''), busy = ref(false)
const labels: Record<string, string> = { PENDING: '等待审批', APPROVED: '已批准', RUNNING: '调用中', COMPLETED: '已完成', FAILED: '调用失败', UNKNOWN: '结果未知', CANCELLED: '已取消', INTERRUPTED: '已中断', REJECTED: '已拒绝' }
let disposed = false, loading = false, dirty = false, generation = 0
async function load() {
  if (loading) { dirty = true; return }
  loading = true; const version = generation
  try {
    const decisions = await api<Approval[]>(`/runs/${props.runId}/external-approvals`)
    if (!disposed && version === generation) { approvals.value = decisions; if (error.value === '插件记录读取失败，请刷新重试。') error.value = '' }
  } catch { if (!disposed) error.value = '插件记录读取失败，请刷新重试。' }
  finally { loading = false; if (dirty && !disposed) { dirty = false; void load() } }
}
async function decide(value: Approval, approved: boolean) {
  busy.value = true; generation++
  try { await api(`/external-approvals/${value.id}/decision`, { approved }) }
  catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '审批失败' }
  finally { busy.value = false; if (!disposed) void load() }
}
watch(() => props.revision, load)
watch(approvals, values => emit('activity', values.some(value => value.status === 'PENDING') ? '等待插件工具审批' : values.some(value => ['APPROVED', 'RUNNING'].includes(value.status)) ? '正在调用插件工具' : ''), { immediate: true })
onMounted(load)
onUnmounted(() => { disposed = true })
</script>
<template>
  <section v-if="approvals.length || error" class="command-reviews" aria-label="插件与外部工具审批">
    <p v-if="error" role="alert" class="dialog-error">{{ error }} <button type="button" @click="load">刷新</button></p>
    <article v-for="value in approvals" :key="value.id" class="command-card">
      <details :open="['PENDING', 'APPROVED', 'RUNNING', 'UNKNOWN'].includes(value.status)" class="command-details">
        <summary><span>{{ value.pluginName }} · {{ value.toolName }}</span><span class="command-status">{{ labels[value.status] || value.status }}</span></summary>
        <p class="command-purpose">{{ value.pluginName }} {{ value.pluginVersion }} / {{ value.serverName }} / {{ value.toolName }}</p>
        <pre class="command-source"><code>{{ value.arguments }}</code></pre>
        <p v-if="value.status === 'PENDING'" class="command-notice">仅批准当前版本、工具和固定参数。外部工具可能产生副作用；等待审批计入任务超时。</p>
        <footer><span role="status">{{ value.detail }}</span><div v-if="value.status === 'PENDING'"><button type="button" :disabled="busy" @click="decide(value, false)">拒绝</button><button type="button" class="primary" :disabled="busy || !active" @click="decide(value, true)">批准本次调用</button></div></footer>
      </details>
    </article>
  </section>
</template>

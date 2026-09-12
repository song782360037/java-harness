<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api, type CommandExecution } from './api'
const props = defineProps<{ runId: string; revision: string; active: boolean }>()
const emit = defineEmits<{ activity: [value: string] }>()
const commands = ref<CommandExecution[]>([])
const error = ref('')
const busy = ref(false)
const labels: Record<string, string> = { PENDING: '等待审批', APPROVED: '已批准', RUNNING: '执行中', STOPPING: '正在停止',
  COMPLETED: '已完成', FAILED: '执行失败', REJECTED: '已拒绝', CANCELLED: '已停止', TIMED_OUT: '已超时', OUTPUT_LIMIT: '输出已截断', INTERRUPTED: '已中断' }
let loading = false, dirty = false, disposed = false, version = 0
async function load() {
  if (loading) { dirty = true; return }
  loading = true
  const current = version
  try {
    const values = await api<CommandExecution[]>(`/runs/${props.runId}/commands`)
    if (!disposed && current === version) {
      commands.value = values
      if (error.value === '命令记录读取失败，请刷新重试。') error.value = ''
    }
  } catch { if (!disposed) error.value = '命令记录读取失败，请刷新重试。' }
  finally { loading = false; if (dirty && !disposed) { dirty = false; void load() } }
}
async function decide(command: CommandExecution, action: 'approve' | 'reject' | 'stop') {
  if (busy.value) return
  busy.value = true; error.value = ''; version++
  try {
    const value = await api<CommandExecution>(`/commands/${command.id}/${action}`, {})
    if (!disposed) commands.value = commands.value.map(item => item.id === value.id ? value : item)
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '操作结果暂时无法确认，请刷新记录。' }
  finally { busy.value = false; if (!disposed) void load() }
}
function retry() { error.value = ''; void load() }
onMounted(load)
watch(() => props.revision, load)
watch(commands, values => emit('activity', values.some(value => value.status === 'PENDING') ? '等待命令审批'
  : values.some(value => ['APPROVED', 'RUNNING', 'STOPPING'].includes(value.status)) ? '正在执行命令' : ''), { immediate: true })
onUnmounted(() => { disposed = true })
</script>
<template>
  <section v-if="commands.length || error" class="command-reviews" aria-label="命令执行与审批">
    <h3>终端 <span>{{ commands.length }}</span></h3>
    <p v-if="error" class="dialog-error" role="alert">{{ error }} <button type="button" @click="retry">刷新记录</button></p>
    <article v-for="command in commands" :key="command.id" class="command-card" :aria-label="`${command.purpose} · ${labels[command.status] || command.status}`">
      <header><strong>{{ command.shell }}</strong><span class="command-status" :data-status="command.status">{{ labels[command.status] || command.status }}</span></header>
      <p class="command-purpose">{{ command.purpose }}</p>
      <pre class="command-source" aria-label="待执行命令"><code>{{ command.command }}</code></pre>
      <div class="command-meta"><span>{{ command.directory }}</span><span>超时 {{ command.timeoutSeconds }} 秒<span v-if="command.exitCode !== null"> · 退出码 {{ command.exitCode }}</span></span></div>
      <p v-if="command.status === 'PENDING'" class="command-notice">批准后以本机服务权限运行，可修改文件或访问网络。等待审批计入任务超时。</p>
      <details v-if="command.stdout || command.stderr" open class="command-output">
        <summary>终端输出</summary>
        <div v-if="command.stdout"><span>stdout</span><pre tabindex="0" aria-label="标准输出">{{ command.stdout }}</pre></div>
        <div v-if="command.stderr"><span>stderr</span><pre tabindex="0" aria-label="错误输出">{{ command.stderr }}</pre></div>
      </details>
      <footer>
        <span role="status">{{ command.detail || (command.status === 'PENDING' ? '确认命令后允许本次执行。' : '') }}</span>
        <div v-if="command.status === 'PENDING'">
          <button type="button" :disabled="busy" @click="decide(command, 'reject')">拒绝</button>
          <button type="button" class="primary" :disabled="busy || !active" @click="decide(command, 'approve')">批准并执行</button>
        </div>
        <button v-else-if="['APPROVED', 'RUNNING', 'STOPPING'].includes(command.status)" type="button" :disabled="busy || command.status === 'STOPPING'" @click="decide(command, 'stop')">停止命令</button>
      </footer>
    </article>
  </section>
</template>

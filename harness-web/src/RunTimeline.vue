<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, isActive, type Run, type RunStep, type SubagentRun, type ToolExecution } from './api'
import { buildTimeline, elapsedSeconds, exploreSummary, formatDuration, isBusy, latestTodos, toolLabel } from './executionTimeline'
import MarkdownContent from './MarkdownContent.vue'
import UiIcon from './UiIcon.vue'

const props = defineProps<{ run: Run; revision: string; subagents?: SubagentRun[] }>()
const emit = defineEmits<{ answer: [value: string]; loaded: [] }>()
const steps = ref<RunStep[]>([])
const tools = ref<ToolExecution[]>([])
// 首次加载前无法区分"旧记录"和"尚未读取"，避免先把整段输出当回答显示。
const loaded = ref(false)
const error = ref('')
// 用户手动展开或收起后不再自动切换。
const userOpen = ref<boolean | null>(null)
const now = ref(Date.now())
let loading = false, dirty = false, disposed = false
let ticker: ReturnType<typeof setInterval> | undefined
let throttle: ReturnType<typeof setTimeout> | undefined

const active = computed(() => isActive(props.run))
const timeline = computed(() => loaded.value || error.value ? buildTimeline(props.run.output, steps.value, tools.value, active.value) : { items: [], answer: '' })
const todos = computed(() => latestTodos(tools.value))
const todosDone = computed(() => todos.value.filter(todo => todo.status === 'completed').length)
const open = computed(() => userOpen.value ?? (active.value || !timeline.value.answer.trim()))
const elapsed = computed(() => elapsedSeconds(steps.value, now.value))
const summary = computed(() => {
  const parts = [active.value ? '正在处理' : elapsed.value ? `已处理 ${formatDuration(elapsed.value)}` : '执行过程']
  if (active.value && elapsed.value) parts[0] += ` ${formatDuration(elapsed.value)}`
  const calls = tools.value.filter(tool => tool.name !== 'update_todos').length
  if (calls) parts.push(`${calls} 次工具调用`)
  return parts.join(' · ')
})
const statusLabels: Record<string, string> = { PENDING: '等待执行', FAILED: '失败', SKIPPED: '未执行', CANCELLED: '已取消', INTERRUPTED: '已中断' }
/** delegate_task 调用对应的子智能体运行；子运行创建前为空。 */
const childOf = (tool: ToolExecution) => props.subagents?.find(value => value.parentCallId === tool.callId)
const childRevision = (child: SubagentRun) => `${props.revision}:${child.run.status}:${child.run.output.length}`
const stopping = ref<Record<string, boolean>>({})
const stopErrors = ref<Record<string, string>>({})
async function stopChild(child: SubagentRun) {
  stopping.value[child.run.id] = true; stopErrors.value[child.run.id] = ''
  try { await api<Run>(`/runs/${child.run.id}/cancel`, {}) } catch (cause) {
    stopping.value[child.run.id] = false
    stopErrors.value[child.run.id] = cause instanceof Error ? `停止失败：${cause.message}` : '停止失败，请重试。'
  }
}
const duration = (tool: ToolExecution) => tool.startedAt && tool.finishedAt ? Math.round((Date.parse(tool.finishedAt) - Date.parse(tool.startedAt)) / 1000) : 0

async function load() {
  if (loading) { dirty = true; return }
  loading = true
  try {
    const [nextSteps, nextTools] = await Promise.all([api<RunStep[]>(`/runs/${props.run.id}/steps`), api<ToolExecution[]>(`/runs/${props.run.id}/tools`)])
    if (!disposed) {
      steps.value = nextSteps; tools.value = nextTools; error.value = ''
      loaded.value = true
      await nextTick()
      if (!disposed) emit('loaded')
    }
  } catch { if (!disposed) error.value = '执行过程读取失败，暂按完整输出显示。' }
  finally { loading = false; if (dirty && !disposed) { dirty = false; void load() } }
}
// 流式思考会频繁推送事件，合并 300ms 内的变化再读取。
function scheduleLoad() {
  if (throttle) return
  throttle = setTimeout(() => { throttle = undefined; void load() }, 300)
}
// 运行中每秒刷新耗时，结束后停止计时。
watch(active, value => {
  clearInterval(ticker); ticker = undefined
  if (value) ticker = setInterval(() => { now.value = Date.now() }, 1000)
}, { immediate: true })
watch(() => props.revision, scheduleLoad)
// 读取完成前不上报，避免父组件误判为"没有回答"。
watch(() => [loaded.value || !!error.value, timeline.value.answer] as const, ([ready, value]) => { if (ready) emit('answer', value) }, { immediate: true })
onMounted(load)
onUnmounted(() => { disposed = true; clearInterval(ticker); clearTimeout(throttle) })
</script>

<template>
  <section v-if="todos.length" class="run-todos" aria-label="任务进度">
    <p class="run-todos-title">任务进度 <span>{{ todosDone }}/{{ todos.length }}</span></p>
    <ol>
      <li v-for="(todo, index) in todos" :key="index" :data-status="todo.status">
        <span v-if="todo.status === 'in_progress' && active" class="loading-indicator"></span>
        <span v-else class="todo-mark" aria-hidden="true">{{ todo.status === 'completed' ? '✓' : '' }}</span>
        <span class="todo-content">{{ todo.content }}</span>
        <span class="sr-only">{{ todo.status === 'completed' ? '已完成' : todo.status === 'in_progress' ? '进行中' : '待处理' }}</span>
      </li>
    </ol>
  </section>
  <section v-if="timeline.items.length || active || error" class="run-process" aria-label="执行过程">
    <button type="button" class="process-toggle" :aria-expanded="open" :aria-controls="`process-${run.id}`" @click="userOpen = !open">
      <span v-if="active" class="loading-indicator"></span>
      <UiIcon :name="open ? 'down' : 'chevron'" :size="13" />
      <span>{{ summary }}</span>
    </button>
    <p v-if="error" class="process-error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <div v-if="open" :id="`process-${run.id}`" class="process-body">
      <template v-for="item in timeline.items" :key="item.key">
        <details v-if="item.kind === 'reasoning'" class="process-reasoning" :open="item.live">
          <summary><UiIcon name="reasoning" :size="13" /><span>{{ item.live ? '正在思考…' : `思考了 ${formatDuration(item.seconds)}` }}</span></summary>
          <p>{{ item.text }}</p>
        </details>
        <MarkdownContent v-else-if="item.kind === 'text'" class="process-text" :content="item.text" />
        <details v-else-if="item.kind === 'explore'" class="process-group">
          <summary><span v-if="item.tools.some(isBusy)" class="loading-indicator"></span><UiIcon v-else name="search" :size="13" /><span>{{ exploreSummary(item.tools) }}</span></summary>
          <div class="process-group-items">
            <details v-for="tool in item.tools" :key="tool.callId" class="process-tool">
              <summary><UiIcon :name="toolLabel(tool).icon" :size="13" /><span>{{ toolLabel(tool).verb }}</span><code v-if="toolLabel(tool).target" :title="toolLabel(tool).target">{{ toolLabel(tool).target }}</code><span v-if="statusLabels[tool.status]" class="tool-status" :class="tool.status.toLowerCase()">{{ statusLabels[tool.status] }}</span></summary>
              <pre>{{ tool.output || '等待工具结果…' }}</pre>
            </details>
          </div>
        </details>
        <details v-else-if="item.tool.name === 'delegate_task' && childOf(item.tool)" class="process-tool process-subagent">
          <summary>
            <span v-if="isActive(childOf(item.tool)!.run)" class="loading-indicator"></span><UiIcon v-else :name="toolLabel(item.tool).icon" :size="13" />
            <span>{{ toolLabel(item.tool).verb }}</span><code v-if="toolLabel(item.tool).target" :title="toolLabel(item.tool).target">{{ toolLabel(item.tool).target }}</code>
            <span v-if="statusLabels[item.tool.status]" class="tool-status" :class="item.tool.status.toLowerCase()">{{ statusLabels[item.tool.status] }}</span>
            <button v-if="isActive(childOf(item.tool)!.run)" type="button" class="subagent-stop" :disabled="stopping[childOf(item.tool)!.run.id] || childOf(item.tool)!.run.status === 'CANCELLING'" @click.stop.prevent="stopChild(childOf(item.tool)!)">停止</button>
            <small v-else-if="duration(item.tool) > 1">{{ formatDuration(duration(item.tool)) }}</small>
          </summary>
          <div class="subagent-body">
            <RunTimeline :run="childOf(item.tool)!.run" :revision="childRevision(childOf(item.tool)!)" />
            <p v-if="childOf(item.tool)!.run.detail" :class="['FAILED', 'TIMED_OUT', 'INTERRUPTED'].includes(childOf(item.tool)!.run.status) ? 'process-error' : 'subagent-notice'">{{ childOf(item.tool)!.run.detail }}</p>
            <p v-if="stopErrors[childOf(item.tool)!.run.id]" class="process-error" role="alert">{{ stopErrors[childOf(item.tool)!.run.id] }}</p>
          </div>
        </details>
        <details v-else class="process-tool">
          <summary>
            <span v-if="isBusy(item.tool)" class="loading-indicator"></span><UiIcon v-else :name="toolLabel(item.tool).icon" :size="13" />
            <span>{{ toolLabel(item.tool).verb }}</span><code v-if="toolLabel(item.tool).target" :title="toolLabel(item.tool).target">{{ toolLabel(item.tool).target }}</code>
            <span v-if="statusLabels[item.tool.status]" class="tool-status" :class="item.tool.status.toLowerCase()">{{ statusLabels[item.tool.status] }}</span>
            <small v-if="duration(item.tool) > 1">{{ formatDuration(duration(item.tool)) }}</small>
          </summary>
          <template v-if="toolLabel(item.tool).raw"><strong>参数</strong><pre>{{ item.tool.arguments }}</pre><strong>结果</strong></template>
          <pre>{{ item.tool.output || (isBusy(item.tool) ? '等待工具结果…' : '无输出') }}</pre>
        </details>
      </template>
    </div>
  </section>
  <MarkdownContent v-if="timeline.answer" :content="timeline.answer" />
</template>

<style scoped>
.run-todos { margin-bottom: 12px; padding: 10px 14px; border: 0.5px solid var(--border); border-radius: var(--radius-lg); background: var(--surface); font-size: var(--font-caption); }
.run-todos-title { margin: 0 0 6px; font-weight: 600; color: var(--text); }
.run-todos-title span { margin-left: 6px; color: var(--muted); font-weight: 400; font-variant-numeric: tabular-nums; }
.run-todos ol { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 4px; }
.run-todos li { display: flex; align-items: center; gap: 8px; line-height: 1.6; color: var(--muted); }
.run-todos li[data-status="in_progress"] { color: var(--text); font-weight: 550; }
.run-todos li[data-status="completed"] .todo-content { text-decoration: line-through; color: var(--faint); }
.todo-mark { display: inline-flex; align-items: center; justify-content: center; width: 14px; height: 14px; flex-shrink: 0; border: 0.5px solid var(--border-strong); border-radius: 50%; font-size: 10px; color: var(--success); }
.run-todos li[data-status="in_progress"] .todo-mark { border-color: var(--accent); }
.todo-content { min-width: 0; overflow-wrap: anywhere; }
.run-process { margin-bottom: 16px; font-size: var(--font-caption); color: var(--muted); }
.process-toggle { display: inline-flex; align-items: center; gap: 7px; min-height: 30px; padding: 4px 8px 4px 4px; border: 0; background: transparent; color: var(--muted); font-size: var(--font-caption); border-radius: var(--radius-md); }
.process-toggle:hover { color: var(--text); background: var(--hover); }
.process-body { margin: 6px 0 0 10px; padding-left: 14px; border-left: 0.5px solid var(--border-strong); display: flex; flex-direction: column; gap: 4px; }
summary { display: flex; align-items: center; gap: 7px; min-height: 28px; cursor: pointer; list-style: none; line-height: 1.6; border-radius: var(--radius-sm); }
summary::-webkit-details-marker { display: none; }
summary:hover { color: var(--text); }
summary > span, summary > code { min-width: 0; }
summary .ui-icon, summary .loading-indicator { flex-shrink: 0; }
code { font: var(--font-caption)/1.6 ui-monospace, 'Cascadia Code', Consolas, monospace; color: var(--text); overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
small { margin-left: auto; color: var(--faint); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.process-reasoning p { margin: 2px 0 8px 20px; color: var(--faint); font-style: italic; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.75; max-height: 320px; overflow-y: auto; }
.process-text { margin: 6px 0; color: var(--text); }
.process-group-items { margin: 2px 0 6px 6px; padding-left: 12px; border-left: 0.5px solid var(--border); }
.tool-status { padding: 0 6px; border-radius: var(--radius-sm); background: var(--subtle); color: var(--faint); flex-shrink: 0; }
.tool-status.failed { background: var(--danger-bg); color: var(--danger); }
.tool-status.pending { background: var(--warning-bg); color: var(--warning); }
.process-tool strong { display: block; margin: 6px 0 4px 20px; color: var(--muted); font-weight: 550; }
pre { margin: 4px 0 8px 20px; padding: 10px 12px; border: 0.5px solid var(--border); border-radius: var(--radius-md); background: var(--subtle); color: var(--muted); white-space: pre-wrap; overflow-wrap: anywhere; max-height: 260px; overflow-y: auto; font: var(--font-caption)/1.7 ui-monospace, 'Cascadia Code', Consolas, monospace; }
.process-error { margin: 4px 0; color: var(--danger); }
.subagent-notice { margin: 4px 0; color: var(--muted); }
.subagent-body { margin: 4px 0 8px 6px; padding-left: 12px; border-left: 0.5px solid var(--border); }
.subagent-stop { margin-left: auto; padding: 0 8px; min-height: 22px; border: 0.5px solid var(--border-strong); border-radius: var(--radius-sm); background: transparent; color: var(--muted); font-size: var(--font-caption); flex-shrink: 0; }
.subagent-stop:hover:not(:disabled) { color: var(--danger); border-color: var(--danger); }
</style>

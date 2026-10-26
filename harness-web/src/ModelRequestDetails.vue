<script setup lang="ts">
import { ref, watch } from 'vue'
import { api, effortLabel, type ModelRequest } from './api'
import { workspaceSummary } from './workspaces'

const props = defineProps<{ runId: string; revision: string }>()
const requests = ref<ModelRequest[]>([])
const open = ref(false)
const loading = ref(false)
const error = ref('')
let dirty = false
async function load() {
  if (!open.value) return
  if (loading.value) { dirty = true; return }
  loading.value = true
  error.value = ''
  try {
    requests.value = await api<ModelRequest[]>(`/runs/${props.runId}/model-requests`)
  } catch { error.value = '读取失败，请重试。' }
  finally {
    loading.value = false
    if (dirty) { dirty = false; void load() }
  }
}
function toggle(event: Event) {
  open.value = (event.target as HTMLDetailsElement).open
  if (open.value) void load()
}
watch(() => props.revision, load)
</script>

<template>
  <details class="request-details" @toggle="toggle">
    <summary>查看模型输入</summary>
    <p>调用前保存的消息快照，不代表上游已成功接收。工具结果会随下一次请求发送。</p>
    <p v-if="loading">正在读取…</p>
    <p v-if="error" role="alert">{{ error }} <button @click="load">重试</button></p>
    <details v-for="request in requests" :key="request.step" class="request-message">
      <summary>请求 {{ request.step }} · {{ request.messages.length + (request.instructions ? 1 : 0) }} 条消息{{ request.finalAnswerOnly ? ' · 收尾回答' : '' }}</summary>
      <p>工作区目录：{{ workspaceSummary(request.workspace) || '未配置' }} · {{ request.finalAnswerOnly ? '工具调用已关闭' : `可用工具：${request.tools.map(tool => tool.name).join('、') || '无'}` }}</p>
      <p>思考强度：{{ effortLabel(request.reasoningEffort) }}</p>
      <div v-if="request.instructions" class="request-message"><strong>系统指引</strong><pre>{{ request.instructions }}</pre></div>
      <div v-for="(message, index) in request.messages" :key="index" class="request-message">
        <strong>{{ message.role === 'tool' ? '工具结果' : message.role === 'user' ? '用户' : '助手' }}</strong>
        <span v-if="message.toolCallId"> · {{ message.toolCallId }}</span>
        <pre>{{ message.content }}</pre>
        <div v-for="call in message.toolCalls" :key="call.id">
          <strong>{{ call.name }} · {{ call.id }}</strong><pre>{{ call.arguments }}</pre>
        </div>
      </div>
    </details>
  </details>
</template>

<style scoped>
.request-details { margin-top: 16px; padding: 12px 14px; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--surface); font-size: calc(12rem / 14); line-height: 1.75; color: var(--muted); }
summary { cursor: pointer; color: var(--text); font-weight: 550; }
.request-message { margin-top: 12px; padding: 10px 12px; border: 1px solid var(--border); border-radius: var(--radius-md); }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: calc(11rem / 14)/1.75 ui-monospace, 'Cascadia Code', Consolas, monospace; max-height: 220px; overflow-y: auto; padding: 10px; border-radius: var(--radius-sm); background: var(--subtle); color: var(--text); }
</style>

<script setup lang="ts">
import { ref } from 'vue'
import { api } from './api'

const props = defineProps<{ runId: string }>()
const messages = ref<{ role: string; content: string }[] | null>(null)
const loading = ref(false)
const error = ref('')
async function load(event: Event) {
  if (!(event.target as HTMLDetailsElement).open || messages.value || loading.value) return
  loading.value = true
  error.value = ''
  try {
    const request = await api<{ messages: { role: string; content: string }[] }>(`/runs/${props.runId}/model-request`)
    messages.value = request.messages
  } catch { error.value = '读取失败，请收起后重新展开。' }
  finally { loading.value = false }
}
</script>

<template>
  <details class="request-details" @toggle="load">
    <summary>查看本次模型输入</summary>
    <p>这是调用前保存的消息快照，不代表上游已成功接收。</p>
    <p v-if="loading">正在读取…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-for="(message, index) in messages" :key="index" class="request-message">
      <strong>{{ message.role === 'user' ? '用户' : '助手' }}</strong>
      <pre>{{ message.content }}</pre>
    </div>
  </details>
</template>

<style scoped>
.request-details { margin-top: 12px; font-size: 12px; color: #56657b; }
summary { cursor: pointer; }
.request-message { margin-top: 12px; padding: 10px; border: 1px solid #e9eef8; border-radius: 8px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; max-height: 220px; overflow-y: auto; }
</style>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from './api'
import type { FileEntry } from './composer'
import UiIcon from './UiIcon.vue'

const props = defineProps<{
  text: string; sessionId: string; workspace: string; workspaceId: string; modelProfileId: string; reasoningEffort: string
  mentions: FileEntry[]; attachmentIds: string[]; disabled: boolean
}>()
const emit = defineEmits<{ apply: [text: string]; busy: [value: boolean]; error: [message: string] }>()
interface Result { prompt: string; historyTurns: number; memories: { id: string; title: string }[] }
const busy = ref(false)
const request = computed(() => ({ sessionId: props.sessionId, prompt: props.text, modelProfileId: props.modelProfileId,
  reasoningEffort: props.reasoningEffort, mentions: props.mentions, attachmentIds: props.attachmentIds }))
const identity = computed(() => JSON.stringify([request.value, props.workspaceId, props.workspace]))
let controller: AbortController | null = null

watch(busy, value => emit('busy', value), { flush: 'sync' })

// 草稿、模型、附件或会话变化时，立即使旧请求失效并恢复编辑。
function invalidate() {
  controller?.abort(); controller = null
  busy.value = false; emit('error', '')
}
watch([identity, () => props.disabled], invalidate, { flush: 'sync' })
onUnmounted(invalidate)

// 仅将仍属于当前草稿的结果自动填回输入框。
async function optimize() {
  if (busy.value || props.disabled || !props.text.trim()) return
  const captured = identity.value
  const active = new AbortController()
  controller = active; busy.value = true; emit('error', '')
  try {
    const value = await api<Result>('/composer/optimize', request.value, active.signal)
    if (controller === active && captured === identity.value && !props.disabled) {
      controller = null; busy.value = false
      emit('apply', value.prompt)
    }
  } catch (cause) {
    if (controller === active && !active.signal.aborted) emit('error', cause instanceof Error ? cause.message : '优化失败，请重试')
  } finally {
    if (controller === active) { busy.value = false; controller = null }
  }
}
</script>
<template>
  <button type="button" class="icon-button optimize-trigger" :disabled="disabled || busy || !text.trim()" aria-label="优化提示词" title="结合对话上下文和相关记忆优化提示词" @click="optimize"><UiIcon name="wand" :size="18" /></button>
</template>
<style scoped>
.optimize-trigger { width: 32px; height: 32px; flex-shrink: 0; color: var(--muted); border-radius: var(--radius-md); }
@media (max-width: 780px) { .optimize-trigger { width: 40px; height: 40px; } }
</style>

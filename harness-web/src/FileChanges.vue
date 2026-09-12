<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api, type FileChange } from './api'
import FileChangeCard from './FileChangeCard.vue'
const props = defineProps<{ runId: string; revision: string; canApply: boolean }>()
const changes = ref<FileChange[]>([])
const error = ref('')
const busy = ref(false)
let loading = false, dirty = false, disposed = false, version = 0
async function load() {
  if (loading) { dirty = true; return }
  loading = true
  const current = version
  try {
    const values = await api<FileChange[]>(`/runs/${props.runId}/file-changes`)
    if (!disposed && current === version) {
      changes.value = values
      if (error.value === '文件修改记录读取失败，请重试。') error.value = ''
    }
  } catch { if (!disposed) error.value = '文件修改记录读取失败，请重试。' }
  finally { loading = false; if (dirty && !disposed) { dirty = false; void load() } }
}
async function review(change: FileChange, action: 'apply' | 'reject') {
  if (busy.value) return
  busy.value = true; error.value = ''; version++
  try {
    const saved = await api<FileChange>(`/file-changes/${change.id}/${action}`, {})
    if (!disposed) changes.value = changes.value.map(value => value.id === saved.id ? saved : value)
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '操作结果暂时无法确认，请刷新记录。' }
  finally { busy.value = false; if (!disposed) void load() }
}
function retry() { error.value = ''; void load() }
onMounted(load)
watch(() => props.revision, load)
onUnmounted(() => { disposed = true })
</script>
<template>
  <section v-if="changes.length || error" class="file-changes" aria-label="文件修改审核">
    <h3>文件修改 <span>{{ changes.length }}</span></h3>
    <p v-if="error" class="dialog-error" role="alert">{{ error }} <button type="button" @click="retry">刷新记录</button></p>
    <FileChangeCard v-for="change in changes" :key="change.id" :change="change" :busy="busy" :can-apply="canApply" @apply="review(change, 'apply')" @reject="review(change, 'reject')" />
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { api, type Run, type Snapshot } from './api'
import { avatarText, taskLabel, type GroupMember, type GroupSnapshot, type GroupTask } from './groups'
import FileChanges from './FileChanges.vue'
import Commands from './Commands.vue'
import RunTimeline from './RunTimeline.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ snapshot: GroupSnapshot; member: GroupMember; runId: string | null; task: GroupTask | null; label: string }>()
const emit = defineEmits<{ close: []; mention: [member: GroupMember] }>()
const run = computed(() => props.runId ? props.snapshot.runs.find(value => value.id === props.runId) ?? null : null)
const revision = computed(() => run.value ? `${run.value.status}:${run.value.activity}` : '')
// 运行记录从成员的隐藏会话读取，运行状态变化时重新读取；只采用最后一次请求的结果。
const process = ref<Run | null>(null)
const error = ref('')
let seq = 0
watch(() => run.value ? `${run.value.id}:${run.value.status}` : '', async key => {
  const current = ++seq
  process.value = null; error.value = ''
  if (!key || !run.value) return
  const { id, sessionId } = run.value
  try {
    const value = await api<Snapshot>(`/sessions/${sessionId}`)
    if (current === seq) process.value = value.runs.find(entry => entry.id === id) ?? null
  } catch (cause) { if (current === seq) error.value = cause instanceof Error ? cause.message : '读取过程失败' }
}, { immediate: true })
// 窄屏下面板位于房间下方，打开或换人时把焦点移入面板。
const closeButton = ref<HTMLButtonElement>()
watch(() => props.member.id, async () => { await nextTick(); closeButton.value?.focus() }, { immediate: true })
</script>

<template>
  <aside class="studio-panel" :aria-label="`${member.name}的详情`" @keydown.esc="emit('close')">
    <header class="studio-panel-header">
      <span class="group-avatar" :style="{ background: member.color }" aria-hidden="true">{{ avatarText(member.name) }}</span>
      <span class="studio-panel-title"><strong>{{ member.name }}</strong><small>{{ label }}</small></span>
      <button ref="closeButton" type="button" class="icon-button" aria-label="关闭详情" title="关闭详情" @click="emit('close')"><UiIcon name="close" :size="15" /></button>
    </header>
    <div class="studio-panel-body">
      <p v-if="member.focus" class="studio-panel-focus">{{ member.focus }}</p>
      <button type="button" class="studio-panel-mention" :disabled="!member.enabled" @click="emit('mention', member)"><UiIcon name="at" :size="14" />@{{ member.name }}</button>
      <section v-if="task" class="studio-panel-task">
        <h3>{{ task.title }}<span class="group-task-status">{{ taskLabel(task.status) }}</span></h3>
        <p>{{ task.instruction }}</p>
        <small v-if="task.attempts > 1 || task.detail"><span v-if="task.attempts > 1">第 {{ task.attempts }} 次尝试</span>{{ task.detail }}</small>
      </section>
      <template v-if="run">
        <template v-if="run.workspace">
          <!-- activity 是会话级序号，同一成员的两次运行可能 revision 相同，按运行重建组件以免沿用旧列表。 -->
          <Commands :key="`commands-${run.id}`" :run-id="run.id" :revision="revision" :active="run.status === 'RUNNING'" />
          <FileChanges :key="`changes-${run.id}`" :run-id="run.id" :revision="revision" :can-apply="run.status === 'COMPLETED' && !snapshot.busy" :can-revert="!snapshot.busy" />
        </template>
        <section class="studio-panel-process">
          <h3>执行过程</h3>
          <RunTimeline v-if="process" :run="process" :revision="revision" />
          <p v-else-if="error" class="field-note">{{ error }}</p>
          <p v-else class="field-note">正在读取过程…</p>
        </section>
      </template>
      <p v-else class="field-note">{{ task?.status === 'PENDING' ? '任务尚未开始执行。' : '暂无执行记录。' }}</p>
    </div>
  </aside>
</template>

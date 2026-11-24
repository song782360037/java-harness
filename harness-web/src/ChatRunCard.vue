<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { effortShortLabel, isActive, type ModelProfile, type Run, type RunStatus, type SubagentRun } from './api'
import { isImage, type Attachment } from './composer'
import { workspaceSummary } from './workspaces'
import ArtifactCards from './ArtifactCards.vue'
import Commands from './Commands.vue'
import ExternalAgentRequests from './ExternalAgentRequests.vue'
import FileChanges from './FileChanges.vue'
import MemoryUsage from './MemoryUsage.vue'
import PluginRunDetails from './PluginRunDetails.vue'
import Questions from './Questions.vue'
import RunTimeline from './RunTimeline.vue'
import UiIcon from './UiIcon.vue'

const props = defineProps<{
  run: Run
  fresh: boolean
  attachments: Attachment[]
  subagents?: SubagentRun[]
  profiles: ModelProfile[]
  connected: boolean
  sessionWorkspace: string
  sessionLocked: boolean
  toolRevision: number
  stepRevision: number
  changeRevision: number
  commandRevision: number
  questionRevision: number
}>()

const emit = defineEmits<{
  freshEnd: []
  timelineLoaded: []
}>()

const labels: Record<RunStatus, string> = {
  QUEUED: '排队中', RUNNING: '正在执行', CANCELLING: '正在停止', COMPLETED: '已完成',
  CANCELLED: '已停止', FAILED: '执行失败', TIMED_OUT: '已超时', INTERRUPTED: '已中断',
}

const answer = ref<string>()
const questionActivity = ref('')
const commandActivity = ref('')
const pluginActivity = ref('')
const copied = ref(false)
const copyError = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined

const active = computed(() => isActive(props.run))
const effectiveAnswer = computed(() => answer.value ?? props.run.output)
const activityLabel = computed(() =>
  props.run.status === 'RUNNING'
    ? questionActivity.value || pluginActivity.value || commandActivity.value || labels[props.run.status]
    : labels[props.run.status],
)
// 上下文字符数随每个新步骤变化，SSE 静默失效时快照轮询仍能触发时间线刷新。
const timelineRevision = computed(() =>
  `${props.run.status}:${props.run.context?.characters ?? 0}:${props.toolRevision}:${props.stepRevision}:${props.connected}`,
)
const modelLabel = computed(() => {
  if (props.run.provider === 'external-acp') return props.run.model
  const profile = props.profiles.find(item => item.id === props.run.modelProfileId)
  return profile?.displayName || profile?.modelName || props.run.model
})
const contextTitle = computed(() => {
  const context = props.run.context
  const compacted = context?.compactedTurns ?? 0
  const parts = context
    ? [
        `携带 ${context.includedTurns} 轮历史`,
        compacted ? `已压缩 ${compacted} 轮为摘要` : '',
        context.droppedTurns > compacted ? `已裁剪 ${context.droppedTurns - compacted} 轮` : '',
        context.excludedTurns ? `已跳过 ${context.excludedTurns} 条` : '',
      ]
    : []
  if (props.run.workspace) parts.push(`工作区：${workspaceSummary(props.run.workspace)}`)
  return parts.filter(Boolean).join('，')
})

async function copyAnswer() {
  try {
    await navigator.clipboard.writeText(effectiveAnswer.value)
    copied.value = true
    copyError.value = ''
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copied.value = false }, 2000)
  } catch {
    copyError.value = '复制失败，请选择回答内容手动复制。'
  }
}

onUnmounted(() => clearTimeout(copyTimer))
</script>

<template>
  <article class="run-card" :class="{ 'is-fresh': fresh }">
    <div class="user-message-wrap" @animationend="emit('freshEnd')"><p class="user-message">{{ run.prompt }}</p><ul v-if="attachments.length" class="message-attachments" aria-label="消息附件"><li v-for="item in attachments" :key="item.id"><a :href="`/api/attachments/${item.id}/content`" target="_blank" rel="noopener" :title="item.name"><img v-if="isImage(item.mediaType)" :src="`/api/attachments/${item.id}/content`" :alt="item.name" loading="lazy" /><template v-else><UiIcon name="file" :size="15" /><span>{{ item.name }}</span></template></a></li></ul></div>
    <section class="response" :aria-label="run.model + ' 的回答'">
      <div class="assistant-heading"><UiIcon v-if="run.provider === 'external-acp'" name="puzzle" :size="22" /><img v-else class="brand-icon" src="/harness-icon.svg" width="22" height="22" alt="" /><strong>{{ run.provider === 'external-acp' ? run.model : 'Harness' }}</strong><span v-if="active" class="working-label"><span class="activity-dot"></span>{{ activityLabel }}</span></div>
      <RunTimeline :run="run" :revision="timelineRevision" :subagents="subagents" @answer="answer = $event" @loaded="emit('timelineLoaded')" />
      <MemoryUsage v-if="run.provider !== 'external-acp'" :run-id="run.id" :revision="`${run.status}:${run.context ? 'prepared' : 'pending'}`" />
      <p v-if="active ? !run.output && (questionActivity || pluginActivity || commandActivity) : !effectiveAnswer" class="output-placeholder">{{ active ? `${activityLabel}，请查看下方操作卡片。` : '本次没有生成回答。' }}</p>
      <p v-if="run.detail" class="run-notice" :class="{ 'has-error': ['FAILED', 'TIMED_OUT', 'INTERRUPTED'].includes(run.status) }">{{ run.detail }}</p>
      <ExternalAgentRequests v-if="run.provider === 'external-acp'" :run-id="run.id" :revision="`${run.status}:${questionRevision}:${connected}`" :active="run.status === 'RUNNING'" @activity="questionActivity = $event" />
      <template v-else>
        <ArtifactCards v-if="run.workspace" :run-id="run.id" :workspace="run.workspace" :revision="`${run.status}:${changeRevision}:${connected}`" />
        <FileChanges v-if="run.workspace" :run-id="run.id" :revision="`${run.status}:${changeRevision}:${connected}`" :can-apply="run.status === 'COMPLETED' && !sessionLocked && sessionWorkspace === run.workspace" :can-revert="!active && !sessionLocked && sessionWorkspace === run.workspace" />
        <Questions :run-id="run.id" :revision="`${run.status}:${questionRevision}:${connected}`" :active="run.status === 'RUNNING'" @activity="questionActivity = $event" />
        <Commands v-if="run.workspace" :run-id="run.id" :revision="`${run.status}:${commandRevision}:${connected}`" :active="run.status === 'RUNNING'" @activity="commandActivity = $event" />
        <PluginRunDetails :run-id="run.id" :revision="`${run.status}:${commandRevision}:${connected}`" :active="run.status === 'RUNNING'" @activity="pluginActivity = $event" />
      </template>
      <div v-if="!active" class="response-footer"><button class="icon-button" :disabled="!effectiveAnswer" :aria-label="copied ? '已复制回答' : '复制回答'" :title="copied ? '已复制' : '复制回答'" @click="copyAnswer"><UiIcon :name="copied ? 'check' : 'copy'" :size="15" /></button><span :title="contextTitle || undefined">{{ modelLabel }}<template v-if="run.provider !== 'external-acp'"> · {{ effortShortLabel(run.reasoningEffort) }}</template></span><time>{{ new Date(run.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</time></div>
      <p v-if="copyError" role="status" class="run-notice">{{ copyError }}</p>
    </section>
  </article>
</template>

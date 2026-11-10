<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import UiIcon from './UiIcon.vue'
import { taskActivity, taskActivityError } from './taskActivity'
import { taskSourceLabel, taskStatusLabel, taskTone, type TaskActivity } from './taskActivityModel'
const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ openTask: [task: TaskActivity] }>()
const panel = ref<HTMLDialogElement>()
const trigger = ref<HTMLButtonElement>()
const tab = ref<'active' | 'recent'>('active')
const items = computed(() => taskActivity.value[tab.value])
const waiting = computed(() => taskActivity.value.active.filter(task => task.status.startsWith('WAITING_')).length)
const idle = computed(() => !taskActivity.value.active.length && !taskActivityError.value)
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
/** 原生对话框提供焦点圈定与 Escape 关闭，关闭后恢复入口焦点。 */
async function show() { panel.value?.showModal(); await nextTick(); panel.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus() }
function close() { panel.value?.close(); trigger.value?.focus() }
function navigate(task: TaskActivity) { if (props.disabled) return; close(); emit('openTask', task) }
</script>
<template>
  <div class="task-center">
    <button ref="trigger" type="button" class="task-center-trigger" :class="{ 'needs-attention': waiting, 'is-idle': idle }" :title="idle ? '任务中心' : undefined" aria-haspopup="dialog" aria-controls="task-center-dialog" @click="show">
      <UiIcon :name="taskActivityError ? 'warning' : 'clock'" :size="16" /><span :class="{ 'sr-only': idle }">{{ taskActivityError ? '任务状态异常' : `活动中 ${taskActivity.active.length}` }}</span><span v-if="waiting" class="task-waiting-count" :aria-label="`${waiting} 个任务待处理`">{{ waiting }}</span>
    </button>
    <dialog id="task-center-dialog" ref="panel" class="task-center-dialog" aria-labelledby="task-center-title" @click="$event.target === panel && close()" @cancel.prevent="close">
      <header class="task-center-heading"><h2 id="task-center-title">任务中心</h2><button type="button" class="icon-button" aria-label="关闭任务中心" @click="close"><UiIcon name="close" :size="18" /></button></header>
      <div class="task-center-tabs"><div class="segmented task-center-segmented" role="group" aria-label="任务列表">
        <button type="button" :aria-pressed="tab === 'active'" @click="tab = 'active'">进行中 <span>{{ taskActivity.active.length }}</span></button>
        <button type="button" :aria-pressed="tab === 'recent'" @click="tab = 'recent'">最近结束</button>
      </div></div>
      <p v-if="taskActivityError" class="task-center-error" role="status">{{ taskActivityError }}，正在重试</p>
      <p v-if="disabled" class="task-center-error" role="status">当前操作完成后可跳转任务</p>
      <div class="task-center-list">
        <p v-if="!items.length" class="task-center-empty">{{ taskActivityError ? '暂时无法读取任务' : tab === 'active' ? '当前没有进行中的任务' : '暂无最近结束的任务' }}</p>
        <button v-for="task in items" :key="task.id" type="button" class="task-center-item" :disabled="disabled" @click="navigate(task)">
          <span class="task-state-icon" :class="taskTone(task.status)"><UiIcon :name="task.status === 'COMPLETED' ? 'check' : task.status.startsWith('WAITING_') ? 'shield' : taskTone(task.status) === 'error' ? 'warning' : 'clock'" :size="17" /></span>
          <span class="task-item-copy"><strong>{{ task.title }}</strong><span class="task-item-meta"><span>{{ taskSourceLabel(task.source) }}</span><time :datetime="task.updatedAt">{{ time(task.updatedAt) }}</time></span></span>
          <span class="task-state-label" :class="taskTone(task.status)">{{ taskStatusLabel(task.status) }}</span><UiIcon name="chevron" :size="14" />
        </button>
      </div>
      <footer class="task-center-footer">{{ tab === 'active' ? '等待审批和回答的任务优先显示' : '显示最近 50 个结束的任务' }}</footer>
    </dialog>
  </div>
</template>

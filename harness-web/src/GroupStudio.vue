<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { avatarText, hostLabel, memberStates, roundNotice, roundSpeakers, taskLabel, taskRelations, taskSteps,
  type GroupMember, type GroupSnapshot, type GroupTask, type MemberState, type RunApprovals } from './groups'
import { StudioScene, type SceneWorker } from './studioScene'
import GroupStudioPanel from './GroupStudioPanel.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ snapshot: GroupSnapshot; approvals: Record<string, RunApprovals> }>()
const emit = defineEmits<{ mention: [member: GroupMember] }>()
// 选中成员时跟随其最新运行；选中任务时固定查看该任务的运行。
const selection = ref<{ memberId: string; taskKey: string | null } | null>(null)
const states = computed(() => memberStates(props.snapshot, props.approvals))
const steps = computed(() => taskSteps(props.snapshot.tasks))
const finished = computed(() => props.snapshot.tasks.filter(task => task.status === 'DONE').length)
const speakers = computed(() => roundSpeakers(props.snapshot))
const host = computed(() => hostLabel(props.snapshot))
const notice = computed(() => roundNotice(props.snapshot.round))
const taskMode = computed(() => props.snapshot.group.mode === 'TASKS')
const selectedState = computed(() => states.value.find(state => state.member.id === selection.value?.memberId))
const selectedTask = computed(() => selection.value?.taskKey ? props.snapshot.tasks.find(task => task.key === selection.value!.taskKey) ?? null : null)
const relations = computed(() => selectedTask.value ? taskRelations(props.snapshot.tasks, selectedTask.value.key) : null)
// 关联任务的负责人也高亮，便于看出接力关系。
const relatedMembers = computed(() => {
  const keys = relations.value ? [...relations.value.upstream, ...relations.value.downstream] : []
  return new Set(props.snapshot.tasks.filter(task => keys.includes(task.key)).map(task => task.memberId))
})
const attention = computed(() => states.value.filter(state => state.attention).map(state => `${state.member.name}：${state.label}`).join('；'))
const memberColor = (id: string) => props.snapshot.members.find(member => member.id === id)?.color || 'var(--muted)'
const deskLabel = (state: MemberState) => `${state.member.name}：${state.label}${state.task ? `，${state.task.title}` : ''}`
const progress = computed(() => taskMode.value && props.snapshot.tasks.length ? `任务 ${finished.value} / ${props.snapshot.tasks.length}` : `主持人：${host.value}`)
const workers = computed<SceneWorker[]>(() => [
  { id: 'host', name: '主持人', template: '', color: '#3f8f8a', kind: 'host', label: host.value, attention: false, busy: props.snapshot.busy, selected: false, related: false },
  ...states.value.map(state => ({
    id: state.member.id, name: state.member.name, template: state.member.template, color: state.member.color, kind: state.kind, label: state.label,
    attention: state.attention, busy: false, selected: selection.value?.memberId === state.member.id, related: relatedMembers.value.has(state.member.id),
  })),
])

// 打开详情的按钮；面板关闭后把焦点还给它，场景内点击则还给画布容器。
let opener: HTMLElement | null = null
function pickMember(id: string, event?: Event) {
  if (!props.snapshot.members.some(member => member.id === id)) return
  opener = (event?.currentTarget as HTMLElement | undefined) ?? stage.value ?? null
  selection.value = selection.value?.memberId === id && !selection.value.taskKey ? null : { memberId: id, taskKey: null }
}
function pickTask(task: GroupTask, event: Event) {
  opener = event.currentTarget as HTMLElement
  selection.value = selection.value?.taskKey === task.key ? null : { memberId: task.memberId, taskKey: task.key }
}
async function closePanel() {
  selection.value = null
  await nextTick()
  if (opener?.isConnected) opener.focus()
}
function taskClass(task: GroupTask) {
  const value = selection.value
  return [`is-${task.status.toLowerCase()}`, {
    'is-selected': value?.taskKey === task.key,
    'is-upstream': relations.value?.upstream.has(task.key),
    'is-downstream': relations.value?.downstream.has(task.key),
    'is-mine': !!value && !value.taskKey && task.memberId === value.memberId,
  }]
}
// 成员被移除或任务计划换成新一轮后，收起失效的选择，焦点按关闭面板的方式归还。
watch(() => props.snapshot, value => {
  const current = selection.value
  if (!current) return
  if (!value.members.some(member => member.id === current.memberId)
    || (current.taskKey && !value.tasks.some(task => task.key === current.taskKey && task.memberId === current.memberId))) void closePanel()
})

const stage = ref<HTMLElement>()
const canvas = ref<HTMLElement>()
const sceneError = ref(false)
const dark = ref(document.documentElement.dataset.theme === 'dark')
let scene: StudioScene | null = null
// 主题切换只改 data-theme 属性，监听它来重建场景配色。
const themeObserver = new MutationObserver(() => { dark.value = document.documentElement.dataset.theme === 'dark' })
onMounted(() => {
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  try {
    scene = new StudioScene(canvas.value!, id => pickMember(id), matchMedia('(prefers-reduced-motion: reduce)').matches)
    scene.update(workers.value, props.snapshot.group.name, progress.value, dark.value)
  } catch {
    // 设备不支持 WebGL 时只保留任务板与详情。
    sceneError.value = true
  }
})
watch([workers, progress, dark], () => scene?.update(workers.value, props.snapshot.group.name, progress.value, dark.value))
onUnmounted(() => { themeObserver.disconnect(); scene?.dispose(); scene = null })
const resetView = () => scene?.resetView()
</script>

<template>
  <div class="studio">
    <section ref="stage" class="studio-stage" aria-label="3D 工作室" tabindex="-1">
      <div ref="canvas" class="studio-canvas"></div>
      <p v-if="sceneError" class="studio-fallback">当前设备不支持 3D 显示，可在任务板和详情中查看协作进度。</p>
      <p class="sr-only" aria-live="polite">{{ attention }}</p>
      <ul class="sr-only" aria-label="成员">
        <li v-for="state in states" :key="state.member.id"><button type="button" :aria-pressed="selection?.memberId === state.member.id && !selection.taskKey" @click="pickMember(state.member.id, $event)">{{ deskLabel(state) }}</button></li>
      </ul>
      <div class="studio-hud is-left">
        <details v-if="taskMode && snapshot.tasks.length" class="studio-card">
          <summary>任务板<span>{{ finished }} / {{ snapshot.tasks.length }} 已完成</span></summary>
          <ol class="studio-steps">
            <li v-for="(step, index) in steps" :key="index">
              <span class="group-task-step">第 {{ index + 1 }} 步</span>
              <ul>
                <li v-for="task in step" :key="task.id">
                  <button type="button" class="studio-task" :class="taskClass(task)" :style="{ '--member': memberColor(task.memberId) }" :aria-pressed="selection?.taskKey === task.key" @click="pickTask(task, $event)">
                    <span class="group-task-status">{{ taskLabel(task.status) }}</span>
                    <strong>{{ task.title }}</strong>
                    <small>{{ task.memberName }}<template v-if="task.attempts > 1"> · 第 {{ task.attempts }} 次尝试</template><template v-if="relations?.upstream.has(task.key)"> · 上游</template><template v-if="relations?.downstream.has(task.key)"> · 下游</template></small>
                  </button>
                </li>
              </ul>
            </li>
          </ol>
        </details>
        <details v-else-if="!taskMode && speakers.length" class="studio-card" open>
          <summary>本轮发言<span>{{ speakers.length }} 次</span></summary>
          <ol class="studio-relay">
            <li v-for="item in speakers" :key="item.id"><span class="group-avatar small" :style="{ background: memberColor(item.memberId || '') }" aria-hidden="true">{{ avatarText(item.authorName) }}</span>{{ item.authorName }}</li>
          </ol>
        </details>
        <p v-if="notice" class="studio-card studio-notice" :class="{ 'has-error': notice.error }">{{ notice.text }}</p>
      </div>
      <div class="studio-hud is-right">
        <button v-if="!sceneError" type="button" class="studio-tool" title="重置视角" aria-label="重置视角" @click="resetView"><UiIcon name="refresh" :size="15" /></button>
      </div>
      <p v-if="!sceneError" class="studio-hint" aria-hidden="true">拖动旋转 · 滚轮缩放 · 点击成员查看详情</p>
    </section>
    <GroupStudioPanel v-if="selectedState" :snapshot="snapshot" :member="selectedState.member" :run-id="selection?.taskKey ? selectedTask?.runId ?? null : selectedState.run?.id ?? null" :task="selection?.taskKey ? selectedTask : selectedState.task" :label="selectedState.label" @close="closePanel" @mention="emit('mention', $event)" />
  </div>
</template>

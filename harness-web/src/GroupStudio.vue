<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { activeMemberCount, avatarText, hostLabel, isActiveMemberState, memberStates, roundNotice, roundSpeakers, taskLabel, taskRelations, taskSteps,
  type GroupMember, type GroupSnapshot, type GroupTask, type MemberState, type RunApprovals } from './groups'
import GroupStudioPanel from './GroupStudioPanel.vue'
import StudioMember from './StudioMember.vue'
import { studioPose, studioSeats } from './studioOffice'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ snapshot: GroupSnapshot; approvals: Record<string, RunApprovals> }>()
const emit = defineEmits<{ mention: [member: GroupMember] }>()
// 选中成员时跟随其最新运行；选中任务时固定查看该任务的运行。
const selection = ref<{ memberId: string; taskKey: string | null } | null>(null)
const states = computed(() => memberStates(props.snapshot, props.approvals))
const activeCount = computed(() => activeMemberCount(states.value))
const steps = computed(() => taskSteps(props.snapshot.tasks))
const finished = computed(() => props.snapshot.tasks.filter(task => task.status === 'DONE').length)
const percent = computed(() => props.snapshot.tasks.length ? Math.round(finished.value / props.snapshot.tasks.length * 100) : 0)
const speakers = computed(() => roundSpeakers(props.snapshot))
const host = computed(() => hostLabel(props.snapshot))
const notice = computed(() => roundNotice(props.snapshot.round))
const taskMode = computed(() => props.snapshot.group.mode === 'TASKS')
const selectedState = computed(() => states.value.find(state => state.member.id === selection.value?.memberId))
const selectedTask = computed(() => selection.value?.taskKey ? props.snapshot.tasks.find(task => task.key === selection.value!.taskKey) ?? null : null)
const relations = computed(() => selectedTask.value ? taskRelations(props.snapshot.tasks, selectedTask.value.key) : null)
const attentionStates = computed(() => states.value.filter(state => state.attention))
const attention = computed(() => attentionStates.value.map(state => `${state.member.name}：${state.label}`).join('；'))
const memberColor = (id: string) => props.snapshot.members.find(member => member.id === id)?.color || 'var(--muted)'
const deskLabel = (state: MemberState) => `${state.member.name}：${state.label}${state.task ? `，${state.task.title}` : ''}`
const selectedRunId = computed(() => selection.value?.taskKey ? selectedTask.value?.runId ?? null : selectedState.value?.run?.id ?? null)
const upstreamTitles = (task: GroupTask) => task.dependsOn.map(key => props.snapshot.tasks.find(value => value.key === key)?.title).filter(Boolean).join('、')
const seats = computed(() => studioSeats(states.value))
const activity = computed(() => props.snapshot.messages.filter(message => message.author !== 'USER').slice(-6).reverse())
const latestMessage = computed(() => activity.value[0])
const workingMotion = (state: MemberState) => ['working', 'finishing', 'reviewing'].includes(state.kind)
const activityTime = (value: string) => new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })

function taskClass(task: GroupTask) {
  return [`is-${task.status.toLowerCase()}`, {
    'is-selected': selection.value?.taskKey === task.key,
    'is-upstream': relations.value?.upstream.has(task.key),
    'is-downstream': relations.value?.downstream.has(task.key),
  }]
}

let opener: HTMLElement | null = null
function pickMember(id: string, event?: Event) {
  if (!props.snapshot.members.some(member => member.id === id)) return
  opener = (event?.currentTarget as HTMLElement | undefined) ?? null
  selection.value = { memberId: id, taskKey: null }
  void revealPanel()
}
function pickTask(task: GroupTask, event: Event) {
  opener = event.currentTarget as HTMLElement
  selection.value = { memberId: task.memberId, taskKey: task.key }
  void revealPanel()
}
async function revealPanel() {
  if (window.innerWidth > 920) return
  await nextTick()
  document.querySelector('.studio-panel:not(.studio-panel-placeholder)')?.scrollIntoView({ block: 'start' })
}
async function closePanel() {
  selection.value = null
  await nextTick()
  if (opener?.isConnected) opener.focus()
}
// 成员被移除或任务计划换成新一轮后，收起失效的选择。
watch(() => props.snapshot, value => {
  const current = selection.value
  if (!current) return
  if (!value.members.some(member => member.id === current.memberId)
    || (current.taskKey && !value.tasks.some(task => task.key === current.taskKey && task.memberId === current.memberId))) void closePanel()
})
</script>

<template>
  <div class="studio studio-office" :class="{ 'has-selection': selectedState }" @keydown.esc="closePanel">
    <p class="sr-only" aria-live="polite">{{ attention }}</p>
    <section class="studio-workspace" aria-label="协作工作室">
      <header class="studio-overview">
        <span class="studio-team-mark"><UiIcon name="gamepad" :size="20" /></span>
        <div class="studio-overview-title"><strong>协作工坊</strong><span>{{ snapshot.group.name }}</span></div>
        <span class="studio-live" :class="{ 'is-busy': snapshot.busy }"><i></i>{{ snapshot.busy ? `${activeCount} 人协作中` : `${states.length} 位成员待命` }}</span>
        <div class="studio-overall"><span>{{ taskMode ? '整体进度' : host }}</span><progress v-if="taskMode" :value="finished" :max="Math.max(1, snapshot.tasks.length)" :aria-label="`已完成 ${finished} 个任务，共 ${snapshot.tasks.length} 个`"></progress><strong v-if="taskMode">{{ percent }}%</strong></div>
      </header>

      <section class="studio-room-shell" aria-label="像素协作办公室">
        <div class="studio-room-viewport">
          <div class="studio-room">
            <img class="studio-room-background" src="/studio/pixel-office-wide.png" width="1983" height="793" alt="像素办公室，设有编码工位、规划白板、评审桌和休息区" draggable="false" />
            <button v-for="seat in seats" :key="seat.state.member.id" type="button" class="studio-member" :class="{ 'is-selected': selection?.memberId === seat.state.member.id, 'has-attention': seat.state.attention, 'is-disabled': !seat.state.member.enabled }" :style="{ left: `${seat.x}%`, top: `${seat.y}%`, zIndex: seat.y, '--member-color': seat.state.member.color }" :aria-label="deskLabel(seat.state)" :aria-pressed="selection?.memberId === seat.state.member.id && !selection.taskKey" :title="deskLabel(seat.state)" @click="pickMember(seat.state.member.id, $event)">
              <span class="studio-member-nameplate"><strong><i :style="{ background: seat.state.member.color }"></i>{{ seat.state.member.name }}</strong><small :class="{ 'is-active': isActiveMemberState(seat.state), 'is-attention': seat.state.attention }">{{ seat.state.label }}</small></span>
              <StudioMember :name="seat.state.member.name" :template="seat.state.member.template" :pose="studioPose(seat.state)" :active="workingMotion(seat.state)" />
            </button>
          </div>
        </div>
        <div v-if="!states.length" class="studio-room-empty">添加成员，组建你的协作团队</div>
        <nav class="studio-roster" aria-label="团队成员">
          <button v-for="state in states" :key="state.member.id" type="button" :class="{ 'is-selected': selection?.memberId === state.member.id, 'has-attention': state.attention, 'is-disabled': !state.member.enabled }" :aria-label="deskLabel(state)" :aria-pressed="selection?.memberId === state.member.id && !selection.taskKey" :title="deskLabel(state)" @click="pickMember(state.member.id, $event)"><i :style="{ background: state.member.color }"></i><span>{{ state.member.name }}</span><small>{{ state.label }}</small></button>
        </nav>
      </section>
      <footer class="studio-activity-bar"><span><UiIcon name="chart" :size="15" />协作动态</span><p v-if="latestMessage"><strong>{{ latestMessage.authorName }}</strong>{{ latestMessage.content }}</p><p v-else>{{ snapshot.busy ? '等待成员更新进展' : '等待开始本轮协作' }}</p><span v-if="attentionStates.length" class="studio-activity-attention"><UiIcon name="warning" :size="14" />{{ attentionStates.length }} 项待处理</span></footer>
    </section>

    <div class="studio-sidebar">
      <section class="studio-board" aria-labelledby="studio-board-title">
        <div class="studio-section-heading"><h2 id="studio-board-title">{{ taskMode ? '任务进度' : '本轮讨论' }}</h2><span>{{ taskMode ? `${finished} / ${snapshot.tasks.length}` : `${speakers.length} 次发言` }}</span></div>
        <template v-if="taskMode">
          <ol v-if="steps.length" class="studio-flow">
            <li v-for="(step, index) in steps" :key="index" class="studio-flow-step">
              <span class="studio-step-label">第 {{ index + 1 }} 步</span>
              <div class="studio-flow-tasks">
                <template v-for="task in step" :key="task.id">
                  <button type="button" class="studio-task" :class="taskClass(task)" :aria-pressed="selection?.taskKey === task.key" @click="pickTask(task, $event)">
                    <span class="studio-task-top"><span class="studio-task-icon"><UiIcon :name="task.status === 'DONE' ? 'check' : task.status === 'RUNNING' ? 'code' : 'clock'" :size="16" /></span><strong>{{ task.title }}</strong></span>
                    <span class="group-task-status">{{ taskLabel(task.status) }}</span>
                    <span class="studio-task-owner"><span class="group-avatar small" :style="{ background: memberColor(task.memberId) }" aria-hidden="true">{{ avatarText(task.memberName) }}</span>{{ task.memberName }}</span>
                    <span v-if="task.attempts > 1 || task.dependsOn.length || relations?.upstream.has(task.key) || relations?.downstream.has(task.key)" class="studio-task-meta"><span v-if="task.attempts > 1">第 {{ task.attempts }} 次尝试</span><span v-if="relations?.upstream.has(task.key)">上游</span><span v-if="relations?.downstream.has(task.key)">下游</span><span v-if="task.dependsOn.length" :title="upstreamTitles(task)">上游：{{ upstreamTitles(task) }}</span></span>
                  </button>
                  <span v-if="step.length > 1 && task !== step.at(-1)" class="studio-parallel-label">并行</span>
                </template>
              </div>
              <span v-if="index < steps.length - 1" class="studio-flow-arrow" aria-hidden="true"><UiIcon name="chevron" :size="16" /></span>
            </li>
          </ol>
          <div v-else class="studio-empty"><UiIcon name="branch" :size="24" /><strong>{{ snapshot.busy ? '主持人正在拆分任务' : '等待开始本轮协作' }}</strong><p>{{ snapshot.busy ? '任务计划和依赖关系将在这里展示。' : '向团队发送消息，开始本轮协作。' }}</p></div>
        </template>
        <ol v-else-if="speakers.length" class="studio-discussion">
          <li v-for="item in speakers" :key="item.id"><button type="button" :disabled="!snapshot.members.some(member => member.id === item.memberId)" @click="pickMember(item.memberId || '', $event)"><span class="group-avatar" :style="{ background: memberColor(item.memberId || '') }" aria-hidden="true">{{ avatarText(item.authorName) }}</span><strong>{{ item.authorName }}</strong><span>{{ item.content }}</span></button></li>
        </ol>
        <div v-else class="studio-empty"><UiIcon name="chat" :size="24" /><strong>{{ snapshot.busy ? '主持人正在安排发言' : '等待开始本轮讨论' }}</strong><p>成员发言后，可从这里查看各自的执行详情。</p></div>
        <div v-if="attentionStates.length" class="studio-attention">
          <span class="studio-attention-icon"><UiIcon name="warning" :size="17" /></span><strong>需要你的处理</strong>
          <div><button v-for="state in attentionStates" :key="state.member.id" type="button" @click="pickMember(state.member.id, $event)">{{ state.member.name }}：{{ state.label }}<UiIcon name="chevron" :size="13" /></button></div>
        </div>
        <p v-if="notice" class="studio-notice" :class="{ 'has-error': notice.error }">{{ notice.text }}</p>
      </section>

      <GroupStudioPanel v-if="selectedState" :key="selectedState.member.id" :snapshot="snapshot" :member="selectedState.member" :run-id="selectedRunId" :task="selectedTask || selectedState.task" :label="selectedState.label" @close="closePanel" @mention="emit('mention', $event)" />
      <section v-else class="studio-recent" aria-labelledby="studio-recent-title">
        <div class="studio-section-heading"><h2 id="studio-recent-title">最近活动</h2><UiIcon name="clock" :size="16" /></div>
        <ol v-if="activity.length" class="studio-activity-list"><li v-for="item in activity" :key="item.id"><button type="button" :disabled="!snapshot.members.some(member => member.id === item.memberId)" @click="pickMember(item.memberId || '', $event)"><i :style="{ background: memberColor(item.memberId || '') }"></i><strong>{{ item.authorName }}</strong><time :datetime="item.createdAt">{{ activityTime(item.createdAt) }}</time><span>{{ item.content }}</span></button></li></ol>
        <p v-else class="studio-activity-empty">暂无活动</p>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { ApprovalMode, ModelProfile, Workspace } from '../api'
import UiIcon from '../UiIcon.vue'
import BotAvatarPicker from './BotAvatarPicker.vue'
import BotBadge from './BotBadge.vue'
import BotSkillOfferCard from './BotSkillOfferCard.vue'
import {
  botApi,
  formatRoutineSchedule,
  miniAppIconName,
  type BotCard,
  type BotDraft,
  type BotExternalAgent,
  type BotPlaybook,
  type BotRoutine,
  type BotRoutineDraft,
  type BotShape,
  type BotSkillOffer,
  type BotSkillSummary,
  type BotSkillView,
  type BotThreadSnapshot,
  type OutfitId,
} from './api'

const props = defineProps<{
  thread: BotThreadSnapshot | null
  allBots?: BotCard[]
  workspaces: Workspace[]
  profiles: ModelProfile[]
  skills: BotSkillSummary[]
  externalAgents: BotExternalAgent[]
  drawerMode?: boolean
  width?: number
}>()

const emit = defineEmits<{
  close: []
  updateBot: [botId: string, draft: BotDraft]
  uploadAvatar: [botId: string, blob: Blob, filename: string]
  deleteAvatar: [botId: string]
  archiveBot: [botId: string]
  openTask: [taskId: string]
  cancelTask: [taskId: string]
  savePlaybook: [botId: string, draft: { id?: string; name: string; whenToUse: string; steps: string; notes: string }]
  deletePlaybook: [playbookId: string]
  importSkill: [botId: string, skillId: string]
  skillsChanged: []
  viewOfferSkillFiles: [offerId: string, skillName: string]
  viewInstalledSkillFiles: [botId: string, skillName: string]
  removeBotSkills: [callerBotId: string, names: string[], targetBotId: string]
  saveRoutine: [botId: string, draft: BotRoutineDraft]
  toggleRoutine: [routineId: string, enabled: boolean]
  runRoutine: [routineId: string]
  deleteRoutine: [routineId: string]
  previewFile: [botId: string, relativePath: string]
  openApp: [appId: string]
  deleteApp: [appId: string]
  addFact: [botId: string, scope: 'USER' | 'BOT', content: string]
  deleteFact: [factId: string]
  approveCommand: [commandId: string]
  rejectCommand: [commandId: string]
  openSession: [sessionId: string]
}>()

const tab = ref<'activity' | 'playbooks' | 'apps' | 'files' | 'settings'>('activity')
const abilitySubTab = ref<'flows' | 'skills'>('flows')
const fileFilter = ref<'all' | 'media' | 'docs'>('all')
const highlightedPlaybookId = ref('')
const highlightedSkillKey = ref('')
const addingFact = ref(false)
const factForm = reactive({ scope: 'BOT' as 'USER' | 'BOT', content: '' })

// Native Bot Skills Panel State
const skillFileInputRef = ref<HTMLInputElement>()
const showSkillSourceForm = ref(false)
const skillSourceInput = ref('')
const skillTargetBotId = ref('')
const skillScanBusy = ref(false)
const skillPanelError = ref('')
const panelOffer = ref<BotSkillOffer | null>(null)
const panelOfferBusy = ref(false)
const confirmingRemoveKey = ref('')

function isImagePath(path: string): boolean {
  return /\.(png|jpe?g|webp|gif)$/i.test(path)
}

const bot = computed(() => props.thread?.card.bot || null)
const installedSkills = computed<BotSkillView[]>(() => props.thread?.skills || [])

const filteredFiles = computed(() => {
  const files = props.thread?.files || []
  if (fileFilter.value === 'media') return files.filter(f => isImagePath(f.relativePath))
  if (fileFilter.value === 'docs') return files.filter(f => !isImagePath(f.relativePath))
  return files
})

interface SkillCollectionGroup {
  collection: string
  skills: BotSkillView[]
}

interface SkillOwnerGroup {
  botId: string
  botName: string
  collections: SkillCollectionGroup[]
  total: number
}

const groupedSkills = computed<SkillOwnerGroup[]>(() => {
  const list = installedSkills.value
  if (!list.length) return []
  const byOwner = new Map<string, { botId: string; botName: string; items: BotSkillView[] }>()
  for (const sk of list) {
    const existing = byOwner.get(sk.botId)
    if (existing) existing.items.push(sk)
    else byOwner.set(sk.botId, { botId: sk.botId, botName: sk.botName || sk.botId, items: [sk] })
  }
  const currentId = bot.value?.id || ''
  const ownerList = [...byOwner.values()].sort((a, b) => {
    if (a.botId === currentId) return -1
    if (b.botId === currentId) return 1
    return a.botName.localeCompare(b.botName)
  })
  return ownerList.map(owner => {
    const byCol = new Map<string, BotSkillView[]>()
    for (const sk of owner.items) {
      const col = sk.sourceRepo || ''
      const arr = byCol.get(col)
      if (arr) arr.push(sk)
      else byCol.set(col, [sk])
    }
    const collections = [...byCol.entries()]
      .sort((a, b) => (a[0] === '' ? 1 : b[0] === '' ? -1 : a[0].localeCompare(b[0])))
      .map(([collection, skills]) => ({ collection, skills }))
    return {
      botId: owner.botId,
      botName: owner.botName,
      collections,
      total: owner.items.length,
    }
  })
})

function focusPlaybook(playbookId: string) {
  tab.value = 'playbooks'
  abilitySubTab.value = 'flows'
  highlightedPlaybookId.value = playbookId
  window.setTimeout(() => {
    if (highlightedPlaybookId.value === playbookId) highlightedPlaybookId.value = ''
  }, 2600)
}

function focusSkill(botId: string, skillName: string) {
  tab.value = 'playbooks'
  abilitySubTab.value = 'skills'
  const key = `${botId}:${skillName}`
  highlightedSkillKey.value = key
  window.setTimeout(() => {
    if (highlightedSkillKey.value === key) highlightedSkillKey.value = ''
  }, 2800)
}

async function onSkillArchivePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !bot.value) return
  const targetId = bot.value.main && skillTargetBotId.value ? skillTargetBotId.value : bot.value.id
  skillPanelError.value = ''
  skillScanBusy.value = true
  try {
    panelOffer.value = await botApi.uploadSkillArchive(targetId, file)
  } catch (cause) {
    skillPanelError.value = cause instanceof Error ? cause.message : '扫描压缩包失败'
  } finally {
    skillScanBusy.value = false
  }
}

async function scanSkillSource() {
  if (!bot.value || !skillSourceInput.value.trim()) return
  const targetId = bot.value.main && skillTargetBotId.value ? skillTargetBotId.value : bot.value.id
  skillPanelError.value = ''
  skillScanBusy.value = true
  try {
    panelOffer.value = await botApi.offerSkillFromSource(bot.value.id, skillSourceInput.value.trim(), targetId)
    skillSourceInput.value = ''
    showSkillSourceForm.value = false
  } catch (cause) {
    skillPanelError.value = cause instanceof Error ? cause.message : '扫描技能来源失败'
  } finally {
    skillScanBusy.value = false
  }
}

async function answerPanelOffer(offerId: string, names: string[]) {
  if (!panelOffer.value) return
  panelOfferBusy.value = true
  skillPanelError.value = ''
  try {
    const ownerId = panelOffer.value.targetBotId || bot.value?.id || ''
    panelOffer.value = await botApi.answerSkillOffer(ownerId, offerId, names)
    emit('skillsChanged')
  } catch (cause) {
    skillPanelError.value = cause instanceof Error ? cause.message : '安装技能失败'
  } finally {
    panelOfferBusy.value = false
  }
}

function requestRemoveSkill(key: string, names: string[], targetBotId: string) {
  if (!bot.value) return
  if (confirmingRemoveKey.value !== key) {
    confirmingRemoveKey.value = key
    return
  }
  confirmingRemoveKey.value = ''
  emit('removeBotSkills', bot.value.id, names, targetBotId)
}

function formatSkillDate(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1}/${d.getDate()}`
}

function submitFact() {
  if (!bot.value || !factForm.content.trim()) return
  emit('addFact', bot.value.id, factForm.scope, factForm.content.trim())
  factForm.content = ''
  addingFact.value = false
}

defineExpose({ focusPlaybook, focusSkill })
const editingPlaybook = ref<BotPlaybook | null>(null)
const addingPlaybook = ref(false)
const showSkillImporter = ref(false)
const pbForm = reactive({ name: '', whenToUse: '', steps: '', notes: '' })

const addingRoutine = ref(false)
const editingRoutine = ref<BotRoutine | null>(null)
const routineForm = reactive({
  name: '',
  instruction: '',
  scheduleKind: 'DAILY',
  scheduleTime: '09:00',
  scheduleWeekday: 1,
  cron: '',
  playbookId: '',
  modelProfileId: '',
})

const botForm = reactive({
  name: '',
  roleSummary: '',
  systemPrompt: '',
  shape: 'orb' as BotShape,
  color: '#276649',
  pinned: false,
  workspaceId: '',
  chatProfileId: '',
  taskProfileId: '',
  externalAgentId: '',
  approvalMode: 'SMART' as ApprovalMode,
})

watch(() => bot.value?.id, () => {
  panelOffer.value = null
  skillPanelError.value = ''
  confirmingRemoveKey.value = ''
  skillTargetBotId.value = bot.value?.id || ''
})

watch(bot, value => {
  if (!value) return
  botForm.name = value.name
  botForm.roleSummary = value.roleSummary
  botForm.systemPrompt = value.systemPrompt
  botForm.shape = value.shape
  botForm.color = value.color
  botForm.pinned = value.pinned
  botForm.workspaceId = value.workspaceId || ''
  botForm.chatProfileId = value.chatProfileId || ''
  botForm.taskProfileId = value.taskProfileId || ''
  botForm.externalAgentId = value.externalAgentId || ''
  botForm.approvalMode = value.approvalMode || 'SMART'
}, { immediate: true })

function quickUpdateBot(partial: Partial<BotDraft>) {
  if (!bot.value) return
  emit('updateBot', bot.value.id, { ...partial, revision: bot.value.revision })
}

function quickUpdateOutfit(outfit: OutfitId[]) {
  quickUpdateBot({ outfit })
}

function saveSettings() {
  if (!bot.value) return
  emit('updateBot', bot.value.id, {
    name: botForm.name.trim(),
    roleSummary: botForm.roleSummary.trim(),
    systemPrompt: botForm.systemPrompt.trim(),
    shape: botForm.shape,
    color: botForm.color,
    pinned: botForm.pinned,
    workspaceId: botForm.workspaceId,
    chatProfileId: botForm.chatProfileId,
    taskProfileId: botForm.taskProfileId,
    externalAgentId: botForm.externalAgentId,
    approvalMode: botForm.approvalMode,
    revision: bot.value.revision,
  })
}

function startNewRoutine() {
  editingRoutine.value = null
  routineForm.name = ''
  routineForm.instruction = ''
  routineForm.scheduleKind = 'DAILY'
  routineForm.scheduleTime = '09:00'
  routineForm.scheduleWeekday = 1
  routineForm.cron = ''
  routineForm.playbookId = ''
  routineForm.modelProfileId = ''
  addingRoutine.value = true
}

function editRoutine(r: BotRoutine) {
  editingRoutine.value = r
  routineForm.name = r.name
  routineForm.instruction = r.instruction
  routineForm.scheduleKind = r.scheduleKind
  routineForm.scheduleTime = r.scheduleTime || '09:00'
  routineForm.scheduleWeekday = r.scheduleWeekday
  routineForm.cron = r.cron || ''
  routineForm.playbookId = r.playbookId || ''
  routineForm.modelProfileId = r.modelProfileId || ''
  addingRoutine.value = true
}

function submitRoutine() {
  if (!bot.value || !routineForm.name.trim() || !routineForm.instruction.trim()) return
  emit('saveRoutine', bot.value.id, {
    id: editingRoutine.value?.id,
    name: routineForm.name.trim(),
    instruction: routineForm.instruction.trim(),
    scheduleKind: routineForm.scheduleKind,
    scheduleTime: routineForm.scheduleTime,
    scheduleWeekday: routineForm.scheduleWeekday,
    cron: routineForm.cron.trim(),
    playbookId: routineForm.playbookId,
    modelProfileId: routineForm.modelProfileId,
    enabled: editingRoutine.value?.enabled ?? true,
    revision: editingRoutine.value?.revision ?? 0,
  })
  addingRoutine.value = false
  editingRoutine.value = null
}

function formatNextRun(iso: string | null): string {
  if (!iso) return '未排期'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function startNewPlaybook() {
  editingPlaybook.value = null
  pbForm.name = ''
  pbForm.whenToUse = ''
  pbForm.steps = ''
  pbForm.notes = ''
  addingPlaybook.value = true
}

function editPlaybook(pb: BotPlaybook) {
  editingPlaybook.value = pb
  pbForm.name = pb.name
  pbForm.whenToUse = pb.whenToUse
  pbForm.steps = pb.steps
  pbForm.notes = pb.notes
  addingPlaybook.value = true
}

function submitPlaybook() {
  if (!bot.value || !pbForm.name.trim() || !pbForm.steps.trim()) return
  emit('savePlaybook', bot.value.id, {
    id: editingPlaybook.value?.id,
    name: pbForm.name.trim(),
    whenToUse: pbForm.whenToUse.trim(),
    steps: pbForm.steps.trim(),
    notes: pbForm.notes.trim(),
  })
  addingPlaybook.value = false
  editingPlaybook.value = null
}
</script>

<template>
  <aside
    v-if="thread"
    class="bot-detail-panel"
    :style="!drawerMode && width ? { width: `${width}px`, flexBasis: `${width}px` } : undefined"
    aria-label="Bot 详情面板"
  >
    <div class="bot-detail-drawer-bar">
      <strong>{{ thread.card.bot.name }} · 工牌与详情</strong>
      <button type="button" class="icon-button" aria-label="关闭详情面板" @click="emit('close')">
        <UiIcon name="close" :size="15" />
      </button>
    </div>
    <div class="bot-detail-scroll">
      <BotBadge
        :card="thread.card"
        :turn-count="thread.entries.filter(e => e.kind === 'ASSISTANT').length"
        :playbook-count="thread.playbooks.length"
        :skill-count="installedSkills.length"
        :routine-count="thread.routines.length"
        :file-count="thread.files.length"
        :app-count="thread.apps.length"
        @jump-tab="tab = $event"
        @rename="quickUpdateBot({ name: $event })"
        @update-look="(s, c) => quickUpdateBot({ shape: s, color: c })"
        @update-outfit="quickUpdateOutfit"
        @upload-avatar="(b, f) => bot && emit('uploadAvatar', bot.id, b, f)"
        @delete-avatar="bot && emit('deleteAvatar', bot.id)"
      />

      <nav class="bot-detail-tabs is-five" role="tablist">
        <button type="button" role="tab" :aria-selected="tab === 'activity'" @click="tab = 'activity'">近况</button>
        <button type="button" role="tab" :aria-selected="tab === 'playbooks'" @click="tab = 'playbooks'">能力</button>
        <button type="button" role="tab" :aria-selected="tab === 'apps'" @click="tab = 'apps'">小程序</button>
        <button type="button" role="tab" :aria-selected="tab === 'files'" @click="tab = 'files'">资料库</button>
        <button type="button" role="tab" :aria-selected="tab === 'settings'" @click="tab = 'settings'">配置</button>
      </nav>

      <div class="bot-detail-body">
      <section v-if="tab === 'activity'" class="bot-panel-section">
        <div class="bot-panel-label">快捷配置（改选立即生效）</div>
        <div class="bot-kv-list is-editable">
          <label class="bot-kv-row">
            <span>聊天模型</span>
            <select
              class="bot-kv-select"
              :value="bot?.chatProfileId || ''"
              @change="quickUpdateBot({ chatProfileId: ($event.target as HTMLSelectElement).value })"
            >
              <option value="">继承主模型</option>
              <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.displayName }}</option>
            </select>
          </label>
          <label class="bot-kv-row">
            <span>任务模型</span>
            <select
              class="bot-kv-select"
              :value="bot?.taskProfileId || ''"
              @change="quickUpdateBot({ taskProfileId: ($event.target as HTMLSelectElement).value })"
            >
              <option value="">继承主模型</option>
              <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.displayName }}</option>
            </select>
          </label>
          <label class="bot-kv-row">
            <span>审批模式</span>
            <select
              class="bot-kv-select"
              :value="thread.card.bot.approvalMode"
              @change="quickUpdateBot({ approvalMode: ($event.target as HTMLSelectElement).value as ApprovalMode })"
            >
              <option value="SMART">SMART（智能）</option>
              <option value="MANUAL">MANUAL（手动）</option>
              <option value="FULL_ACCESS">FULL_ACCESS</option>
            </select>
          </label>
          <label class="bot-kv-row">
            <span>项目工作区</span>
            <select
              class="bot-kv-select"
              :value="bot?.workspaceId || ''"
              @change="quickUpdateBot({ workspaceId: ($event.target as HTMLSelectElement).value })"
            >
              <option value="">仅资料库</option>
              <option v-for="ws in workspaces" :key="ws.id" :value="ws.id">{{ ws.name }}</option>
            </select>
          </label>
        </div>

        <div class="bot-panel-row-head">
          <span class="bot-panel-label">例行任务（{{ thread.routines.length }}）</span>
          <button type="button" class="bot-mini-btn" @click="startNewRoutine">+ 新建</button>
        </div>

        <form v-if="addingRoutine" class="bot-form" @submit.prevent="submitRoutine">
          <label>名称<input v-model="routineForm.name" required maxlength="100" placeholder="例如：每日晨报整理" /></label>
          <label>执行指令<textarea v-model="routineForm.instruction" rows="3" required placeholder="到点要做什么…"></textarea></label>
          <label>频率
            <select v-model="routineForm.scheduleKind">
              <option value="DAILY">每天</option>
              <option value="WEEKDAYS">工作日（周一至周五）</option>
              <option value="WEEKLY">每周</option>
              <option value="HOURLY">每小时</option>
              <option value="CUSTOM">自定义 Cron</option>
              <option value="MANUAL">仅手动触发</option>
            </select>
          </label>
          <label v-if="['DAILY', 'WEEKDAYS', 'WEEKLY', 'HOURLY'].includes(routineForm.scheduleKind)">
            {{ routineForm.scheduleKind === 'HOURLY' ? '时间（仅取分钟 HH:mm）' : '时间（HH:mm）' }}
            <input v-model="routineForm.scheduleTime" placeholder="09:00" />
          </label>
          <label v-if="routineForm.scheduleKind === 'WEEKLY'">星期
            <select v-model.number="routineForm.scheduleWeekday">
              <option :value="1">周一</option><option :value="2">周二</option><option :value="3">周三</option>
              <option :value="4">周四</option><option :value="5">周五</option><option :value="6">周六</option>
              <option :value="0">周日</option>
            </select>
          </label>
          <label v-if="routineForm.scheduleKind === 'CUSTOM'">5 段 Cron
            <input v-model="routineForm.cron" placeholder="0 9 * * 1-5" />
          </label>
          <label v-if="thread.playbooks.length">按做法执行（可选）
            <select v-model="routineForm.playbookId">
              <option value="">不指定做法</option>
              <option v-for="pb in thread.playbooks" :key="pb.id" :value="pb.id">{{ pb.name }}</option>
            </select>
          </label>
          <div class="bot-form-actions">
            <button type="button" @click="addingRoutine = false">取消</button>
            <button type="submit" class="is-primary">保存例行</button>
          </div>
        </form>

        <div v-if="!thread.routines.length && !addingRoutine" class="bot-panel-empty">
          暂无例行任务，可在聊天里说「每天早上 9 点帮我整理简报」或点上方新建。
        </div>
        <div v-for="r in thread.routines" :key="r.id" class="bot-task-card">
          <div class="bot-task-card-top">
            <strong>{{ r.name }}</strong>
            <span class="bot-task-pill" :class="r.enabled ? 'st-completed' : ''">{{ r.enabled ? '启用中' : '已暂停' }}</span>
          </div>
          <p class="bot-task-sum">{{ formatRoutineSchedule(r) }} · 下次 {{ formatNextRun(r.nextRunAt) }}</p>
          <div class="bot-task-card-actions">
            <button type="button" @click="emit('toggleRoutine', r.id, !r.enabled)">{{ r.enabled ? '暂停' : '启用' }}</button>
            <button type="button" @click="emit('runRoutine', r.id)">立即跑</button>
            <button type="button" @click="editRoutine(r)">编辑</button>
            <button type="button" class="is-danger" @click="emit('deleteRoutine', r.id)">删除</button>
          </div>
        </div>

        <div class="bot-panel-label">后台任务记录（{{ thread.tasks.length }}）</div>
        <div v-if="!thread.tasks.length" class="bot-panel-empty">暂无后台任务记录</div>
        <div v-for="task in thread.tasks" :key="task.id" class="bot-task-card">
          <div class="bot-task-card-top">
            <strong>{{ task.title }}</strong>
            <span class="bot-task-pill" :class="`st-${task.status.toLowerCase()}`">{{ task.status }}</span>
          </div>
          <p v-if="(task.status === 'RUNNING' || task.status === 'QUEUED') && task.statusText" class="bot-task-stage">
            进度：{{ task.statusText }}
          </p>
          <p v-if="task.errorDetail" class="bot-task-err">{{ task.errorDetail }}</p>
          <p v-else-if="task.summary" class="bot-task-sum">{{ task.summary.slice(0, 120) }}</p>
          <div class="bot-task-card-actions">
            <button type="button" @click="emit('openTask', task.id)">查看详情</button>
            <button type="button" @click="emit('openSession', task.sessionId)">审批/会话</button>
            <button v-if="task.status === 'RUNNING' || task.status === 'QUEUED'" type="button" class="is-danger" @click="emit('cancelTask', task.id)">停止</button>
          </div>
        </div>

        <div class="bot-panel-row-head">
          <span class="bot-panel-label">长期记忆（专属 {{ thread.botFacts.length }} · 共享 {{ thread.userFacts.length }}）</span>
          <button type="button" class="bot-mini-btn" @click="addingFact = !addingFact">+ 记住</button>
        </div>
        <form v-if="addingFact" class="bot-form" @submit.prevent="submitFact">
          <label>范围
            <select v-model="factForm.scope">
              <option value="BOT">仅当前 Bot 专属</option>
              <option value="USER">关于我（全团队共享）</option>
            </select>
          </label>
          <label>记忆内容<textarea v-model="factForm.content" rows="2" required maxlength="500" placeholder="例如：周报用表格输出，优先标明风险项…"></textarea></label>
          <div class="bot-form-actions">
            <button type="button" @click="addingFact = false">取消</button>
            <button type="submit" class="is-primary">保存记忆</button>
          </div>
        </form>
        <div v-if="!thread.botFacts.length && !thread.userFacts.length && !addingFact" class="bot-panel-empty">
          暂无长期记忆；在聊天里说「记住…」，Bot 会自动沉淀到这里。
        </div>
        <div v-if="thread.botFacts.length" class="bot-facts-group">
          <small class="bot-facts-sub">Bot 专属记忆</small>
          <div v-for="fact in thread.botFacts" :key="fact.id" class="bot-fact-row">
            <span>{{ fact.content }}</span>
            <button type="button" class="icon-button" title="遗忘此记忆" @click="emit('deleteFact', fact.id)">
              <UiIcon name="close" :size="12" />
            </button>
          </div>
        </div>
        <div v-if="thread.userFacts.length" class="bot-facts-group">
          <small class="bot-facts-sub">关于用户（全团队共享）</small>
          <div v-for="fact in thread.userFacts" :key="fact.id" class="bot-fact-row is-shared">
            <span>{{ fact.content }}</span>
            <button type="button" class="icon-button" title="遗忘此共享记忆" @click="emit('deleteFact', fact.id)">
              <UiIcon name="close" :size="12" />
            </button>
          </div>
        </div>
      </section>

      <section v-else-if="tab === 'playbooks'" class="bot-panel-section">
        <div class="bot-ability-subtabs" role="tablist" aria-label="能力分类">
          <button
            type="button"
            role="tab"
            :aria-selected="abilitySubTab === 'flows'"
            @click="abilitySubTab = 'flows'"
          >
            做法 ({{ thread.playbooks.length }})
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="abilitySubTab === 'skills'"
            @click="abilitySubTab = 'skills'"
          >
            技能 ({{ installedSkills.length }})
          </button>
        </div>

        <!-- Sub-tab 1: Playbooks (SOP) -->
        <template v-if="abilitySubTab === 'flows'">
          <div class="bot-panel-row-head">
            <span class="bot-panel-label">已学会的做法（SOP）</span>
            <div class="bot-row-btns">
              <button type="button" class="bot-mini-btn" @click="showSkillImporter = !showSkillImporter">全局导入</button>
              <button type="button" class="bot-mini-btn" @click="startNewPlaybook">+ 新增</button>
            </div>
          </div>

          <div v-if="showSkillImporter" class="bot-skill-import-box">
            <div class="bot-panel-label">从全局工作台技能导入为做法</div>
            <div v-if="!skills.length" class="bot-panel-empty">暂无可导入的全局技能</div>
            <div v-for="sk in skills" :key="sk.id" class="bot-skill-row">
              <div>
                <strong>{{ sk.title }}</strong>
                <small>{{ sk.description }}</small>
              </div>
              <button type="button" class="bot-mini-btn" @click="emit('importSkill', thread.card.bot.id, sk.id); showSkillImporter = false">
                导入
              </button>
            </div>
          </div>

          <form v-if="addingPlaybook" class="bot-form" @submit.prevent="submitPlaybook">
            <label>名称<input v-model="pbForm.name" required maxlength="80" placeholder="例如：发版说明整理" /></label>
            <label>适用场景<input v-model="pbForm.whenToUse" maxlength="400" placeholder="什么时候按这个做法执行" /></label>
            <label>步骤<textarea v-model="pbForm.steps" rows="4" required placeholder="分步写出执行规范…"></textarea></label>
            <label>注意事项<textarea v-model="pbForm.notes" rows="2" placeholder="可选边界条件"></textarea></label>
            <div class="bot-form-actions">
              <button type="button" @click="addingPlaybook = false">取消</button>
              <button type="submit" class="is-primary">保存做法</button>
            </div>
          </form>

          <div v-if="!thread.playbooks.length && !addingPlaybook" class="bot-panel-empty">
            做成一件事后对 Bot 说「以后都这么做」，它会把做法沉淀到这里。
          </div>
          <div v-for="pb in thread.playbooks" :key="pb.id" class="bot-playbook-card" :class="{ 'is-highlighted': pb.id === highlightedPlaybookId }">
            <div class="bot-playbook-head">
              <strong>{{ pb.name }}</strong>
              <small>用过 {{ pb.useCount }} 次</small>
            </div>
            <p v-if="pb.whenToUse" class="bot-playbook-when">{{ pb.whenToUse }}</p>
            <pre class="bot-playbook-steps">{{ pb.steps }}</pre>
            <div class="bot-task-card-actions">
              <button type="button" @click="editPlaybook(pb)">编辑</button>
              <button type="button" class="is-danger" @click="emit('deletePlaybook', pb.id)">删除</button>
            </div>
          </div>
        </template>

        <!-- Sub-tab 2: Native Bot Skills (SKILL.md packages) -->
        <template v-else>
          <input
            ref="skillFileInputRef"
            type="file"
            accept=".zip,.skill"
            hidden
            @change="onSkillArchivePicked"
          />
          <div class="bot-panel-row-head">
            <span class="bot-panel-label">
              {{ thread.card.bot.main ? '团队已装技能' : '专属技能包' }}（{{ installedSkills.length }}）
            </span>
            <div class="bot-row-btns">
              <button
                type="button"
                class="bot-mini-btn"
                :disabled="skillScanBusy"
                @click="skillFileInputRef?.click()"
              >
                {{ skillScanBusy ? '扫描中…' : '上传包' }}
              </button>
              <button
                type="button"
                class="bot-mini-btn"
                @click="showSkillSourceForm = !showSkillSourceForm"
              >
                + 链接/路径
              </button>
            </div>
          </div>

          <form v-if="showSkillSourceForm" class="bot-form" @submit.prevent="scanSkillSource">
            <label v-if="thread.card.bot.main && allBots?.length">安装目标成员
              <select v-model="skillTargetBotId">
                <option v-for="b in allBots" :key="b.bot.id" :value="b.bot.id">
                  {{ b.bot.name }}{{ b.bot.main ? '（总管）' : '' }}
                </option>
              </select>
            </label>
            <label>GitHub 仓库/目录链接或本地目录
              <input
                v-model="skillSourceInput"
                required
                placeholder="https://github.com/owner/repo 或 本地技能文件夹路径"
              />
            </label>
            <div class="bot-form-actions">
              <button type="button" @click="showSkillSourceForm = false">取消</button>
              <button type="submit" class="is-primary" :disabled="skillScanBusy || !skillSourceInput.trim()">
                {{ skillScanBusy ? '扫描中…' : '扫描技能' }}
              </button>
            </div>
          </form>

          <p v-if="skillPanelError" class="dialog-error">{{ skillPanelError }}</p>

          <div v-if="panelOffer" class="bot-panel-offer-wrap">
            <BotSkillOfferCard
              :offer="panelOffer"
              :current-bot-id="thread.card.bot.id"
              :busy="panelOfferBusy"
              @answer="answerPanelOffer"
              @view-files="(id, name) => emit('viewOfferSkillFiles', id, name)"
            />
            <button
              v-if="panelOffer.installed !== null"
              type="button"
              class="bot-mini-btn"
              style="align-self: flex-end;"
              @click="panelOffer = null"
            >
              收起提示
            </button>
          </div>

          <div v-if="!installedSkills.length && !panelOffer" class="bot-panel-empty">
            尚未安装原生技能包（SKILL.md）。你可以上传 <code>.zip/.skill</code> 包、粘贴 GitHub 链接，或在聊天里直接把技能仓库发给 Bot 让它扫描安装。
          </div>

          <div v-for="owner in groupedSkills" :key="owner.botId" class="bot-skill-owner-group">
            <div v-if="thread.card.bot.main && (groupedSkills.length > 1 || owner.botId !== thread.card.bot.id)" class="bot-skill-owner-head">
              <strong>{{ owner.botName }}</strong>
              <small>{{ owner.total }} 个技能</small>
            </div>

            <div v-for="col in owner.collections" :key="`${owner.botId}:${col.collection}`" class="bot-skill-col-group">
              <div v-if="col.collection" class="bot-skill-col-head">
                <span :title="col.collection">{{ col.collection }}</span>
                <button
                  v-if="col.skills.length > 1"
                  type="button"
                  class="bot-link-btn is-danger"
                  @click="requestRemoveSkill(`col:${owner.botId}:${col.collection}`, col.skills.map(s => s.name), owner.botId)"
                >
                  {{ confirmingRemoveKey === `col:${owner.botId}:${col.collection}` ? '再点确认整套卸载' : '整套卸载' }}
                </button>
              </div>

              <div
                v-for="sk in col.skills"
                :key="sk.id"
                class="bot-skill-card"
                :class="{ 'is-highlighted': highlightedSkillKey === `${sk.botId}:${sk.name}` }"
              >
                <div class="bot-skill-card-head">
                  <UiIcon name="skill" :size="14" />
                  <strong>{{ sk.name }}</strong>
                  <span v-if="sk.version" class="miniapp-ver">v{{ sk.version }}</span>
                  <span v-if="sk.scripts.length" class="skill-script-badge is-mini">
                    {{ sk.scripts.length }} 脚本
                  </span>
                </div>
                <p class="bot-skill-card-desc">{{ sk.description }}</p>
                <div class="bot-skill-card-meta">
                  <span>{{ sk.fileCount }} 个文件 · 用过 {{ sk.useCount }} 次</span>
                  <span v-if="sk.lastUsedAt">最近 {{ formatSkillDate(sk.lastUsedAt) }}</span>
                </div>
                <div class="bot-task-card-actions">
                  <button type="button" @click="emit('viewInstalledSkillFiles', sk.botId, sk.name)">查看文件</button>
                  <button
                    type="button"
                    class="is-danger"
                    @click="requestRemoveSkill(sk.id, [sk.name], sk.botId)"
                  >
                    {{ confirmingRemoveKey === sk.id ? '确认卸载' : '卸载' }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </template>
      </section>

      <section v-else-if="tab === 'apps'" class="bot-panel-section">
        <div class="bot-panel-label">交互小程序（{{ thread.apps.length }}）</div>
        <div v-if="!thread.apps.length" class="bot-panel-empty">
          暂无小程序。你可以让 Bot 在后台把报表、清单或工具做成一个可点的小程序。
        </div>
        <div v-for="app in thread.apps" :key="app.id" class="bot-task-card">
          <div class="bot-task-card-top">
            <span class="miniapp-list-title">
              <span class="miniapp-tile-dot" :style="{ background: app.color || '#2d5d9c' }">
                <UiIcon :name="miniAppIconName(app.icon)" :size="10" />
              </span>
              <strong>{{ app.name }}</strong>
            </span>
            <span class="bot-task-pill st-completed">v{{ app.version }}</span>
          </div>
          <p v-if="app.description" class="bot-task-sum">{{ app.description }}</p>
          <div class="bot-task-card-actions">
            <button type="button" @click="emit('openApp', app.id)">打开</button>
            <button type="button" class="is-danger" @click="emit('deleteApp', app.id)">删除</button>
          </div>
        </div>
      </section>

      <section v-else-if="tab === 'files'" class="bot-panel-section">
        <div class="bot-panel-row-head">
          <span class="bot-panel-label">资料库文件（{{ thread.files.length }}）</span>
          <div class="bot-file-filters">
            <button type="button" :class="{ active: fileFilter === 'all' }" @click="fileFilter = 'all'">全部</button>
            <button type="button" :class="{ active: fileFilter === 'media' }" @click="fileFilter = 'media'">图片</button>
            <button type="button" :class="{ active: fileFilter === 'docs' }" @click="fileFilter = 'docs'">文档</button>
          </div>
        </div>
        <div v-if="!filteredFiles.length" class="bot-panel-empty">
          {{ thread.files.length ? '当前分类下暂无文件' : '该 Bot 尚未在资料库中写入文件' }}
        </div>
        <button
          v-for="file in filteredFiles"
          :key="file.id"
          type="button"
          class="bot-file-row is-clickable"
          @click="emit('previewFile', thread.card.bot.id, file.relativePath)"
        >
          <img
            v-if="isImagePath(file.relativePath)"
            :src="botApi.rawFileUrl(thread.card.bot.id, file.relativePath)"
            :alt="file.relativePath"
            class="bot-file-thumb"
            loading="lazy"
          />
          <UiIcon v-else name="file" :size="15" />
          <div>
            <strong>{{ file.relativePath }}</strong>
            <small>{{ file.sizeBytes }} bytes · 点击预览</small>
          </div>
        </button>
      </section>

      <form v-else class="bot-form" @submit.prevent="saveSettings">
        <label>名称<input v-model="botForm.name" required maxlength="30" /></label>
        <label>职责定位<input v-model="botForm.roleSummary" maxlength="200" /></label>
        <label>人设与工作指令<textarea v-model="botForm.systemPrompt" rows="4"></textarea></label>

        <div class="bot-picker-group">
          <span>形象与主题色</span>
          <BotAvatarPicker v-model:shape="botForm.shape" v-model:color="botForm.color" compact />
        </div>

        <label>绑定项目工作区
          <select v-model="botForm.workspaceId">
            <option value="">不绑定（仅使用 Bot 资料库）</option>
            <option v-for="ws in workspaces" :key="ws.id" :value="ws.id">{{ ws.name }}</option>
          </select>
        </label>

        <label>聊天模型
          <select v-model="botForm.chatProfileId">
            <option value="">继承全局主模型</option>
            <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.displayName }}</option>
          </select>
        </label>

        <label>后台任务模型
          <select v-model="botForm.taskProfileId">
            <option value="">继承全局主模型</option>
            <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.displayName }}</option>
          </select>
        </label>

        <label>外部 ACP 智能体（可选，需绑定工作区生效）
          <select v-model="botForm.externalAgentId">
            <option value="">不使用（走内置模型与资料库）</option>
            <option v-for="agent in externalAgents" :key="agent.id" :value="agent.id">
              /{{ agent.alias }} · {{ agent.name }}{{ agent.enabled ? '' : '（未启用）' }}
            </option>
          </select>
        </label>

        <label>工作区与命令审批模式
          <select v-model="botForm.approvalMode">
            <option value="SMART">SMART（智能审批 · 推荐）</option>
            <option value="MANUAL">MANUAL（全部手动审批）</option>
            <option value="FULL_ACCESS">FULL_ACCESS（完全访问）</option>
          </select>
        </label>

        <label class="bot-checkbox">
          <input v-model="botForm.pinned" type="checkbox" :disabled="thread.card.bot.main" />
          <span>在左侧栏置顶</span>
        </label>

        <div class="bot-form-actions">
          <button
            v-if="!thread.card.bot.main"
            type="button"
            class="is-danger"
            @click="emit('archiveBot', thread.card.bot.id)"
          >归档移除</button>
          <button type="submit" class="is-primary">保存配置</button>
        </div>
      </form>
      </div>
    </div>
  </aside>
</template>

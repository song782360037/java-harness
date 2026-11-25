<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import type { ApprovalMode, ModelProfile, Workspace } from '../api'
import UiIcon from '../UiIcon.vue'
import BotAvatarPicker from './BotAvatarPicker.vue'
import BotChatPane from './BotChatPane.vue'
import BotDetailPanel from './BotDetailPanel.vue'
import BotOnboardingModal from './BotOnboardingModal.vue'
import BotShareCard from './BotShareCard.vue'
import BotSidebar from './BotSidebar.vue'
import MiniAppDock from './MiniAppDock.vue'
import MiniAppWindow from './MiniAppWindow.vue'
import {
  botApi,
  type BotCard,
  type BotDraft,
  type BotEntry,
  type BotExternalAgent,
  type BotFileContent,
  type BotLayoutItem,
  type BotMiniAppBundle,
  type BotRoleTemplate,
  type BotRoutineDraft,
  type BotSection,
  type BotShape,
  type BotSkillSummary,
  type BotTaskDetail,
  type BotThreadSnapshot,
  type BotWorkspaceSnapshot,
  type IslandNotice,
} from './api'

const props = defineProps<{
  workspaces: Workspace[]
  profiles: ModelProfile[]
  sidebarCollapsed: boolean
}>()

const emit = defineEmits<{
  expandSidebar: []
  openSession: [sessionId: string]
  openDevices: []
  openModels: []
}>()

const workspace = ref<BotWorkspaceSnapshot | null>(null)
const selectedBotId = ref('')
const thread = ref<BotThreadSnapshot | null>(null)
const detailDrawerQuery = typeof window !== 'undefined' ? window.matchMedia('(max-width: 1159px)') : null
const compactRosterQuery = typeof window !== 'undefined' ? window.matchMedia('(max-width: 899px)') : null
const detailDrawerMode = ref(detailDrawerQuery ? detailDrawerQuery.matches : false)
const compactRosterMode = ref(compactRosterQuery ? compactRosterQuery.matches : false)
const detailOpen = ref(!detailDrawerMode.value)
const rosterDrawerOpen = ref(false)
const showGuideModal = ref(false)
const onboardingBusy = ref(false)
const sending = ref(false)
const error = ref('')
const hasConfiguredModel = computed(() => props.profiles.some(p => !!p.model?.configured))
const mainBotName = computed(() => workspace.value?.bots.find(b => b.bot.main)?.bot.name || '小满')

function syncLayoutQueries() {
  if (detailDrawerQuery) {
    const narrow = detailDrawerQuery.matches
    if (narrow && !detailDrawerMode.value) detailOpen.value = false
    detailDrawerMode.value = narrow
  }
  if (compactRosterQuery) {
    const compact = compactRosterQuery.matches
    if (!compact) rosterDrawerOpen.value = false
    compactRosterMode.value = compact
  }
}
const activeNotice = ref<IslandNotice | null>(null)
const recentNotices = ref<IslandNotice[]>([])
let noticeTimer: number | undefined
let eventSource: EventSource | null = null
let reconnectTimer: number | undefined

const showCreateDialog = ref(false)
const createForm = reactive({
  name: '',
  roleSummary: '',
  systemPrompt: '',
  shape: 'cube' as BotShape,
  color: '#2d5d9c',
  workspaceId: '',
  approvalMode: 'SMART' as ApprovalMode,
})

const showManualTaskDialog = ref(false)
const taskForm = reactive({ title: '', instruction: '', playbookId: '' })

const inspectingTask = ref<BotTaskDetail | null>(null)
const skills = ref<BotSkillSummary[]>([])
const externalAgents = ref<BotExternalAgent[]>([])
const previewingFile = ref<BotFileContent | null>(null)
const sharingEntries = ref<BotEntry[] | null>(null)

interface MountedMiniApp {
  bundle: BotMiniAppBundle
  minimized: boolean
  hot: boolean
  suspendTimer?: number
}
const openMiniApps = ref<MountedMiniApp[]>([])
const dockedApps = computed(() =>
  openMiniApps.value.filter(item => item.minimized).map(item => ({ app: item.bundle.app, hot: item.hot })),
)

const showOnboarding = computed(() => !!workspace.value && (!workspace.value.meta.onboarded || showGuideModal.value))

async function loadWorkspace(preferBotId?: string) {
  try {
    const snap = await botApi.workspace()
    workspace.value = snap
    recentNotices.value = snap.notices
    if (snap.bots.length) {
      const target = preferBotId
        || (snap.bots.some(b => b.bot.id === selectedBotId.value) ? selectedBotId.value : snap.bots[0].bot.id)
      selectedBotId.value = target
      await loadThread(target)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载 Bot 工作台失败'
  }
}

async function loadThread(botId: string) {
  if (!botId) return
  try {
    thread.value = await botApi.thread(botId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载 Bot 对话失败'
  }
}

async function selectBot(botId: string) {
  selectedBotId.value = botId
  if (compactRosterMode.value) rosterDrawerOpen.value = false
  await loadThread(botId)
  try {
    await botApi.markSeen(botId)
  } catch { /* ignore */ }
}

function showIslandNotice(notice: IslandNotice) {
  activeNotice.value = notice
  clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => {
    if (activeNotice.value?.id === notice.id) activeNotice.value = null
  }, 4500)
}

function connectEvents() {
  eventSource?.close()
  clearTimeout(reconnectTimer)
  const source = new EventSource('/api/bots/events')
  eventSource = source
  source.addEventListener('bot-event', event => {
    try {
      const payload = JSON.parse((event as MessageEvent).data) as { version: string; notices?: IslandNotice[] }
      if (payload.notices?.length) {
        recentNotices.value = [...recentNotices.value, ...payload.notices].slice(-30)
        showIslandNotice(payload.notices[payload.notices.length - 1])
      }
      void refreshLive()
    } catch { /* ignore */ }
  })
  source.onerror = () => {
    source.close()
    reconnectTimer = window.setTimeout(connectEvents, 2000)
  }
}

async function refreshLive() {
  try {
    const snap = await botApi.workspace()
    workspace.value = snap
    if (selectedBotId.value) {
      thread.value = await botApi.thread(selectedBotId.value)
    }
    // Live-push updated data to any hot-mounted Mini Apps (open or within 5-min dock window)
    for (const mounted of openMiniApps.value) {
      if (!mounted.hot) continue
      const matching = thread.value?.apps.find(a => a.id === mounted.bundle.app.id)
      if (matching && matching.updatedAt !== mounted.bundle.app.updatedAt) {
        try {
          mounted.bundle = await botApi.miniApp(matching.id)
        } catch { /* ignore */ }
      }
    }
  } catch { /* retry on next event */ }
}

async function sendMessage(payload: { content: string; quoteSeq: number | null }) {
  if (!selectedBotId.value) return
  sending.value = true
  error.value = ''
  try {
    await botApi.postMessage(selectedBotId.value, {
      requestId: crypto.randomUUID(),
      content: payload.content,
      quoteSeq: payload.quoteSeq,
    })
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '发送消息失败'
  } finally {
    sending.value = false
  }
}

async function stopChat() {
  if (!selectedBotId.value) return
  await botApi.stop(selectedBotId.value)
  await refreshLive()
}

async function deleteEntries(seqs: number[]) {
  if (!selectedBotId.value) return
  await botApi.deleteEntries(selectedBotId.value, seqs)
  await refreshLive()
}

async function submitCreateBot() {
  if (!createForm.name.trim()) return
  error.value = ''
  try {
    const created = await botApi.createBot({
      name: createForm.name.trim(),
      roleSummary: createForm.roleSummary.trim(),
      systemPrompt: createForm.systemPrompt.trim(),
      shape: createForm.shape,
      color: createForm.color,
      workspaceId: createForm.workspaceId,
      approvalMode: createForm.approvalMode,
    })
    showCreateDialog.value = false
    createForm.name = ''
    createForm.roleSummary = ''
    createForm.systemPrompt = ''
    await loadWorkspace(created.bot.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建 Bot 失败'
  }
}

async function updateBot(botId: string, draft: BotDraft) {
  error.value = ''
  try {
    await botApi.updateBot(botId, draft)
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '更新 Bot 失败'
  }
}

async function archiveBot(botId: string) {
  error.value = ''
  try {
    await botApi.archiveBot(botId)
    await loadWorkspace()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '归档 Bot 失败'
  }
}

async function createSection(name: string) {
  await botApi.createSection(name)
  await refreshLive()
}

async function toggleSection(sec: BotSection) {
  await botApi.updateSection(sec.id, { collapsed: !sec.collapsed })
  await refreshLive()
}

async function deleteSection(sectionId: string) {
  await botApi.deleteSection(sectionId)
  await refreshLive()
}

async function togglePin(card: BotCard) {
  await botApi.updateBot(card.bot.id, { pinned: !card.bot.pinned, revision: card.bot.revision })
  await refreshLive()
}

async function reorder(items: BotLayoutItem[]) {
  await botApi.updateLayout(items)
  await refreshLive()
}

async function submitManualTask() {
  if (!selectedBotId.value || !taskForm.title.trim() || !taskForm.instruction.trim()) return
  error.value = ''
  try {
    await botApi.startTask(selectedBotId.value, {
      title: taskForm.title.trim(),
      instruction: taskForm.instruction.trim(),
      playbookId: taskForm.playbookId || undefined,
    })
    showManualTaskDialog.value = false
    taskForm.title = ''
    taskForm.instruction = ''
    taskForm.playbookId = ''
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '启动后台任务失败'
  }
}

async function inspectTask(taskId: string) {
  try {
    inspectingTask.value = await botApi.taskDetail(taskId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载任务详情失败'
  }
}

async function cancelTask(taskId: string) {
  await botApi.cancelTask(taskId)
  if (inspectingTask.value?.task.id === taskId) {
    inspectingTask.value = await botApi.taskDetail(taskId)
  }
  await refreshLive()
}

async function savePlaybook(botId: string, draft: { id?: string; name: string; whenToUse: string; steps: string; notes: string }) {
  await botApi.savePlaybook(botId, draft)
  await refreshLive()
}

async function deletePlaybook(playbookId: string) {
  await botApi.deletePlaybook(playbookId)
  await refreshLive()
}

async function importSkill(botId: string, skillId: string) {
  error.value = ''
  try {
    await botApi.importSkill(botId, skillId)
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '导入技能失败'
  }
}

async function saveRoutine(botId: string, draft: BotRoutineDraft) {
  error.value = ''
  try {
    await botApi.saveRoutine(botId, draft)
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存例行任务失败'
  }
}

async function toggleRoutine(routineId: string, enabled: boolean) {
  error.value = ''
  try {
    await botApi.toggleRoutine(routineId, enabled)
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '切换例行任务状态失败'
  }
}

async function runRoutine(routineId: string) {
  error.value = ''
  try {
    await botApi.runRoutine(routineId)
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '立即运行例行任务失败'
  }
}

async function deleteRoutine(routineId: string) {
  error.value = ''
  try {
    await botApi.deleteRoutine(routineId)
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除例行任务失败'
  }
}

async function previewFile(botId: string, relativePath: string) {
  error.value = ''
  try {
    previewingFile.value = await botApi.fileContent(botId, relativePath)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取资料库文件失败'
  }
}

async function openMiniApp(appId: string) {
  error.value = ''
  const existing = openMiniApps.value.find(item => item.bundle.app.id === appId)
  if (existing) {
    clearTimeout(existing.suspendTimer)
    existing.minimized = false
    if (!existing.hot) {
      try { existing.bundle = await botApi.miniApp(appId) } catch { /* ignore */ }
      existing.hot = true
    }
    return
  }
  try {
    const bundle = await botApi.miniApp(appId)
    openMiniApps.value = [...openMiniApps.value, { bundle, minimized: false, hot: true }]
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '打开小程序失败'
  }
}

function minimizeMiniApp(appId: string) {
  const target = openMiniApps.value.find(item => item.bundle.app.id === appId)
  if (!target) return
  target.minimized = true
  clearTimeout(target.suspendTimer)
  // 5 分钟内热保活并持续接收新数据，之后挂起释放 iframe 内存
  target.suspendTimer = window.setTimeout(() => {
    target.hot = false
  }, 5 * 60_000)
}

function closeMiniApp(appId: string) {
  const target = openMiniApps.value.find(item => item.bundle.app.id === appId)
  if (target) clearTimeout(target.suspendTimer)
  openMiniApps.value = openMiniApps.value.filter(item => item.bundle.app.id !== appId)
}

async function deleteMiniApp(appId: string) {
  error.value = ''
  try {
    closeMiniApp(appId)
    await botApi.deleteMiniApp(appId)
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除小程序失败'
  }
}

async function sendFromMiniApp(botId: string, text: string) {
  try {
    await botApi.postMessage(botId, { requestId: crypto.randomUUID(), content: text })
    await refreshLive()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '小程序发送消息失败'
  }
}

async function loadMetaLists() {
  try { skills.value = await botApi.skills() } catch { /* ignore */ }
  try { externalAgents.value = await botApi.externalAgents() } catch { /* ignore */ }
}

async function completeOnboarding(payload: { mainBotName: string; userDisplayName: string; templateKeys: string[]; skip: boolean }) {
  error.value = ''
  onboardingBusy.value = true
  try {
    const snap = await botApi.onboard({
      mainBotName: payload.skip ? '' : payload.mainBotName,
      userDisplayName: payload.skip ? '' : payload.userDisplayName,
      templateKeys: payload.skip ? [] : payload.templateKeys,
    })
    workspace.value = snap
    showGuideModal.value = false
    if (snap.bots.length) await selectBot(snap.bots[0].bot.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '初始化团队失败'
  } finally {
    onboardingBusy.value = false
  }
}

async function recruitTemplates(picked: BotRoleTemplate[]) {
  if (!picked.length) {
    showGuideModal.value = false
    return
  }
  error.value = ''
  onboardingBusy.value = true
  try {
    let lastId = selectedBotId.value
    for (const tpl of picked) {
      const created = await botApi.createBot({
        name: tpl.name,
        roleSummary: tpl.roleSummary,
        systemPrompt: tpl.systemPrompt,
        shape: tpl.shape,
        color: tpl.color,
        approvalMode: 'SMART',
      })
      lastId = created.bot.id
    }
    showGuideModal.value = false
    await loadWorkspace(lastId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '批量招募角色失败'
  } finally {
    onboardingBusy.value = false
  }
}

async function openTask(botId: string) {
  if (!workspace.value) await loadWorkspace(botId)
  else await selectBot(botId)
}

defineExpose({ openTask })

watch(() => props.workspaces, () => { /* reactive prop */ })

onMounted(() => {
  void loadWorkspace()
  void loadMetaLists()
  connectEvents()
  detailDrawerQuery?.addEventListener('change', syncLayoutQueries)
  compactRosterQuery?.addEventListener('change', syncLayoutQueries)
})

onUnmounted(() => {
  eventSource?.close()
  clearTimeout(reconnectTimer)
  clearTimeout(noticeTimer)
  detailDrawerQuery?.removeEventListener('change', syncLayoutQueries)
  compactRosterQuery?.removeEventListener('change', syncLayoutQueries)
  for (const item of openMiniApps.value) clearTimeout(item.suspendTimer)
})
</script>

<template>
  <section class="bots-page" aria-label="Bot 工作台">
    <div v-if="error" class="bots-error-bar" role="alert">
      <span>{{ error }}</span>
      <button type="button" @click="error = ''">关闭</button>
    </div>

    <div class="bots-columns" :class="{ 'is-detail-drawer': detailDrawerMode, 'is-roster-drawer': compactRosterMode }">
      <div
        v-if="compactRosterMode && rosterDrawerOpen"
        class="bot-drawer-scrim"
        @click="rosterDrawerOpen = false"
      ></div>
      <BotSidebar
        v-show="!compactRosterMode || rosterDrawerOpen"
        :class="{ 'is-drawer-open': compactRosterMode && rosterDrawerOpen }"
        :bots="workspace?.bots || []"
        :sections="workspace?.sections || []"
        :selected-bot-id="selectedBotId"
        :outer-sidebar-collapsed="sidebarCollapsed"
        @select="selectBot"
        @create-bot="showCreateDialog = true"
        @create-section="createSection"
        @toggle-section="toggleSection"
        @delete-section="deleteSection"
        @toggle-pin="togglePin"
        @reorder="reorder"
        @expand-outer-sidebar="emit('expandSidebar')"
        @open-devices="emit('openDevices')"
        @open-guide="showGuideModal = true"
      />

      <BotChatPane
        :thread="thread"
        :all-bots="workspace?.bots || []"
        :island-notice="activeNotice"
        :recent-notices="recentNotices"
        :sending="sending"
        :detail-open="detailOpen"
        :detail-drawer-mode="detailDrawerMode"
        :compact-layout="compactRosterMode"
        :has-configured-model="hasConfiguredModel"
        @send="sendMessage"
        @stop="stopChat"
        @delete-entries="deleteEntries"
        @select-bot="selectBot"
        @open-task="inspectTask"
        @cancel-task="cancelTask"
        @toggle-detail="detailOpen = !detailOpen"
        @toggle-roster="rosterDrawerOpen = !rosterDrawerOpen"
        @open-manual-task="showManualTaskDialog = true"
        @open-models="emit('openModels')"
        @share-entries="sharingEntries = $event"
        @open-app="openMiniApp"
      />

      <div
        v-if="detailDrawerMode && detailOpen"
        class="bot-drawer-scrim"
        @click="detailOpen = false"
      ></div>
      <BotDetailPanel
        v-if="detailOpen"
        :class="{ 'is-drawer': detailDrawerMode }"
        :drawer-mode="detailDrawerMode"
        :thread="thread"
        :workspaces="workspaces"
        :profiles="profiles"
        :skills="skills"
        :external-agents="externalAgents"
        @close="detailOpen = false"
        @update-bot="updateBot"
        @archive-bot="archiveBot"
        @open-task="inspectTask"
        @cancel-task="cancelTask"
        @save-playbook="savePlaybook"
        @delete-playbook="deletePlaybook"
        @import-skill="importSkill"
        @save-routine="saveRoutine"
        @toggle-routine="toggleRoutine"
        @run-routine="runRoutine"
        @delete-routine="deleteRoutine"
        @preview-file="previewFile"
        @open-app="openMiniApp"
        @delete-app="deleteMiniApp"
        @open-session="emit('openSession', $event)"
      />
    </div>

    <BotOnboardingModal
      v-if="showOnboarding && workspace"
      :templates="workspace.templates"
      :existing-bots="workspace.bots"
      :initial-main-bot-name="mainBotName"
      :initial-user-name="workspace.meta.userDisplayName"
      :has-configured-model="hasConfiguredModel"
      :revisiting="workspace.meta.onboarded"
      :busy="onboardingBusy"
      @complete="completeOnboarding"
      @recruit-templates="recruitTemplates"
      @close="showGuideModal = false"
      @open-models="showGuideModal = false; emit('openModels')"
    />

    <div v-if="showCreateDialog" class="bot-modal-scrim" @click.self="showCreateDialog = false">
      <form class="bot-modal is-scrollable" @submit.prevent="submitCreateBot">
        <header class="bot-modal-head">
          <h3>招募新 Bot</h3>
          <button type="button" class="icon-button" aria-label="关闭弹窗" @click="showCreateDialog = false"><UiIcon name="close" :size="16" /></button>
        </header>
        <div class="bot-modal-body">
          <label>名称（不可含空格或 @）<input v-model="createForm.name" required maxlength="30" placeholder="例如：周报助手" /></label>
          <label>岗位职责<input v-model="createForm.roleSummary" maxlength="200" placeholder="一句话说明它管哪一摊" /></label>
          <label>角色指令<textarea v-model="createForm.systemPrompt" rows="3" placeholder="工作风格、输出偏好与注意事项…"></textarea></label>
          <div class="bot-picker-group">
            <span>形象与主题色</span>
            <BotAvatarPicker v-model:shape="createForm.shape" v-model:color="createForm.color" />
          </div>
          <label>项目工作区（可选）
            <select v-model="createForm.workspaceId">
              <option value="">仅使用 Bot 资料库</option>
              <option v-for="ws in workspaces" :key="ws.id" :value="ws.id">{{ ws.name }}</option>
            </select>
          </label>
        </div>
        <footer class="bot-modal-foot bot-form-actions">
          <button type="button" @click="showCreateDialog = false">取消</button>
          <button type="submit" class="is-primary">立即入职</button>
        </footer>
      </form>
    </div>

    <div v-if="showManualTaskDialog && thread" class="bot-modal-scrim" @click.self="showManualTaskDialog = false">
      <form class="bot-modal" @submit.prevent="submitManualTask">
        <header>
          <h3>派发后台任务给 {{ thread.card.bot.name }}</h3>
          <button type="button" class="icon-button" @click="showManualTaskDialog = false"><UiIcon name="close" :size="16" /></button>
        </header>
        <label>任务标题<input v-model="taskForm.title" required maxlength="100" placeholder="例如：整理最近 10 条 Git 提交" /></label>
        <label>详细指令<textarea v-model="taskForm.instruction" rows="4" required placeholder="写清目标、范围与预期输出…"></textarea></label>
        <label v-if="thread.playbooks.length">按做法执行（可选）
          <select v-model="taskForm.playbookId">
            <option value="">不指定做法</option>
            <option v-for="pb in thread.playbooks" :key="pb.id" :value="pb.id">{{ pb.name }}</option>
          </select>
        </label>
        <div class="bot-form-actions">
          <button type="button" @click="showManualTaskDialog = false">取消</button>
          <button type="submit" class="is-primary">启动后台任务</button>
        </div>
      </form>
    </div>

    <div v-if="inspectingTask" class="bot-modal-scrim" @click.self="inspectingTask = null">
      <div class="bot-modal is-wide">
        <header>
          <h3>后台任务：{{ inspectingTask.task.title }}</h3>
          <button type="button" class="icon-button" @click="inspectingTask = null"><UiIcon name="close" :size="16" /></button>
        </header>
        <div class="bot-task-detail-meta">
          <span class="bot-task-pill" :class="`st-${inspectingTask.task.status.toLowerCase()}`">{{ inspectingTask.task.status }}</span>
          <span v-if="inspectingTask.task.modelLabel">模型：{{ inspectingTask.task.modelLabel }}</span>
          <button type="button" class="bot-ghost-btn" @click="emit('openSession', inspectingTask.task.sessionId); inspectingTask = null">
            打开底层任务会话（查看工具/审批）
          </button>
        </div>
        <div class="bot-panel-label">执行指令</div>
        <pre class="bot-task-pre">{{ inspectingTask.task.instruction }}</pre>
        <div v-if="inspectingTask.task.summary" class="bot-panel-label">最终结论</div>
        <pre v-if="inspectingTask.task.summary" class="bot-task-pre">{{ inspectingTask.task.summary }}</pre>
        <div v-if="inspectingTask.task.errorDetail" class="bot-task-err">失败原因：{{ inspectingTask.task.errorDetail }}</div>
      </div>
    </div>

    <div v-if="previewingFile" class="bot-modal-scrim" @click.self="previewingFile = null">
      <div class="bot-modal is-wide">
        <header>
          <h3>资料库文件：{{ previewingFile.relativePath }}</h3>
          <button type="button" class="icon-button" @click="previewingFile = null"><UiIcon name="close" :size="16" /></button>
        </header>
        <div class="bot-task-detail-meta">
          <span>大小：{{ previewingFile.sizeBytes }} bytes</span>
          <span v-if="previewingFile.truncated" class="bot-fallback-pill">文件较大，仅展示前 200 KB</span>
        </div>
        <pre class="bot-task-pre is-tall">{{ previewingFile.content }}</pre>
      </div>
    </div>

    <BotShareCard
      v-if="sharingEntries && thread"
      :card="thread.card"
      :entries="sharingEntries"
      @close="sharingEntries = null"
    />

    <template v-for="item in openMiniApps" :key="item.bundle.app.id">
      <MiniAppWindow
        v-if="item.hot || !item.minimized"
        :bundle="item.bundle"
        :minimized="item.minimized"
        @minimize="minimizeMiniApp(item.bundle.app.id)"
        @close="closeMiniApp(item.bundle.app.id)"
        @send-message="sendFromMiniApp"
        @data-updated="refreshLive()"
      />
    </template>

    <MiniAppDock
      :apps="dockedApps"
      @restore="openMiniApp"
      @close="closeMiniApp"
    />
  </section>
</template>

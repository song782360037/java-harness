<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, approvalModeLabel, approvalModeOptions, effortLabel, reasoningOptions, type ModelProfile, type Workspace } from './api'
import { emptyDraft, executionState, formatTime, scheduleLabel, scheduleOptions, triggerLabel, weekdays,
  type Automation, type AutomationDraft, type AutomationExecution } from './automations'
import type { FlowSummary } from './flows'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ workspaces: Workspace[]; profiles: ModelProfile[]; sidebarCollapsed: boolean; defaultWorkspaceId: string; defaultProfileId: string }>()
const emit = defineEmits<{ openSession: [id: string]; openFlow: [flowId: string, itemId?: string]; expandSidebar: [] }>()
const automations = ref<Automation[]>([])
const flows = ref<FlowSummary[]>([])
const selectedId = ref('')
const selected = computed(() => automations.value.find(item => item.id === selectedId.value))
const history = ref<AutomationExecution[]>([])
const historyLoaded = ref(false)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const confirmingDelete = ref(false)
const editor = ref<HTMLDialogElement>()
const editingId = ref<string | null>(null)
const targetKind = ref<'session' | 'flow'>('session')
const form = ref<AutomationDraft>(emptyDraft())
const formError = ref('')
let editorTrigger: HTMLElement | null = null
let pollTimer: number | undefined
// 轮询与操作会并发加载，只采用最后一次请求的结果。
let loadVersion = 0
const workspaceName = (id: string) => id ? props.workspaces.find(item => item.id === id)?.name || '工作区已不存在' : '仅聊天'
const profileName = (id: string) => props.profiles.find(item => item.id === id)?.displayName || id
const flowName = (id: string) => flows.value.find(item => item.flow.id === id)?.flow.name || '流水线已不存在'
const message = (cause: unknown, fallback: string) => cause instanceof Error ? cause.message : fallback
const toDraft = ({ id: _id, nextRunAt: _next, createdAt: _created, ...draft }: Automation): AutomationDraft => ({ ...draft, flowId: draft.flowId || '' })
const canOpenExecution = (item: AutomationExecution) => Boolean((item.flowId && item.flowItemId && item.flowItemStatus) || (item.sessionId && item.runStatus))

async function load() {
  const version = ++loadVersion
  try {
    const [items, flowList] = await Promise.all([api<Automation[]>('/automations'), api<FlowSummary[]>('/flows').catch(() => flows.value)])
    if (version !== loadVersion) return
    const id = items.some(item => item.id === selectedId.value) ? selectedId.value : items[0]?.id || ''
    const runs = id ? await api<AutomationExecution[]>(`/automations/${id}/runs`) : []
    if (version !== loadVersion) return
    if (id !== selectedId.value) { selectedId.value = id; confirmingDelete.value = false }
    automations.value = items; flows.value = flowList; history.value = runs; historyLoaded.value = true
    error.value = ''
  } catch (cause) { if (version === loadVersion) error.value = message(cause, '自动化读取失败') }
  finally { if (version === loadVersion) loading.value = false }
}
async function select(id: string) {
  selectedId.value = id; confirmingDelete.value = false; history.value = []; historyLoaded.value = false
  await load()
}
async function openEditor(value?: Automation) {
  editingId.value = value?.id ?? ''
  form.value = value ? toDraft(value) : emptyDraft(props.defaultWorkspaceId, props.defaultProfileId)
  targetKind.value = form.value.flowId ? 'flow' : 'session'
  formError.value = ''
  editorTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  editor.value?.showModal()
  editor.value?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true })
}
function switchTarget(kind: 'session' | 'flow') {
  targetKind.value = kind
  if (kind === 'session') form.value.flowId = ''
  else if (!form.value.flowId) form.value.flowId = flows.value[0]?.flow.id || ''
}
function closeEditor() {
  if (busy.value) return
  editor.value?.close(); editingId.value = null
  if (editorTrigger?.isConnected) editorTrigger.focus({ preventScroll: true })
}
async function save() {
  busy.value = true; formError.value = ''
  try {
    if (targetKind.value === 'flow' && !form.value.flowId) throw new Error('请选择要触发的流水线')
    // 仅手动运行没有暂停概念，始终保存为启用，避免列表显示“已暂停”却无法恢复。
    const draft = { ...form.value, flowId: targetKind.value === 'flow' ? form.value.flowId : '', enabled: form.value.scheduleKind === 'MANUAL' || form.value.enabled }
    const saved = await api<Automation>(editingId.value ? `/automations/${editingId.value}` : '/automations', draft)
    busy.value = false
    closeEditor()
    if (saved.id !== selectedId.value) { selectedId.value = saved.id; historyLoaded.value = false }
    await load()
  } catch (cause) { formError.value = message(cause, '保存失败') }
  finally { busy.value = false }
}
/** 切换启用状态时提交完整定义，沿用版本号检查。 */
async function toggle(value: Automation) {
  busy.value = true; error.value = ''
  try {
    await api<Automation>(`/automations/${value.id}`, { ...toDraft(value), enabled: !value.enabled })
    await load()
  } catch (cause) { error.value = message(cause, '更新失败') }
  finally { busy.value = false }
}
async function runNow(value: Automation) {
  busy.value = true; error.value = ''
  try { await api<AutomationExecution>(`/automations/${value.id}/run`, {}); await load() }
  catch (cause) { error.value = message(cause, '运行失败') }
  finally { busy.value = false }
}
async function remove(value: Automation) {
  busy.value = true; error.value = ''
  try {
    await api(`/automations/${value.id}/delete`, { revision: value.revision })
    confirmingDelete.value = false; selectedId.value = ''; historyLoaded.value = false
    await load()
    // 确认按钮随删除移除，焦点回到新建入口。
    document.querySelector<HTMLButtonElement>('.automation-create')?.focus()
  }
  catch (cause) { error.value = message(cause, '删除失败') }
  finally { busy.value = false }
}
function openExecution(item: AutomationExecution) {
  if (item.flowId && item.flowItemId && item.flowItemStatus) emit('openFlow', item.flowId, item.flowItemId)
  else if (item.sessionId && item.runStatus) emit('openSession', item.sessionId)
}
// 页面打开期间定时刷新，展示新触发的运行与状态变化。
onMounted(() => { void load(); pollTimer = window.setInterval(() => { if (!busy.value) void load() }, 10000) })
onUnmounted(() => window.clearInterval(pollTimer))
</script>

<template>
  <main class="main-panel automations-page" aria-labelledby="automations-title">
    <header class="workspace-header">
      <div class="workspace-title"><button v-if="sidebarCollapsed" class="icon-button" aria-label="展开侧栏" title="展开侧栏" @click="emit('expandSidebar')"><UiIcon name="panel" /></button><span class="header-breadcrumb">工作台 <span aria-hidden="true">/</span></span><h1 id="automations-title">自动化</h1></div>
      <button v-if="automations.length" type="button" class="primary automation-create" :disabled="busy || loading" @click="openEditor()"><UiIcon name="plus" :size="15" />新建自动化</button>
    </header>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <div class="automations-body" :aria-busy="loading">
      <p v-if="loading" class="automations-empty"><span class="loading-indicator"></span>正在读取自动化…</p>
      <div v-else-if="!automations.length" class="automations-empty">
        <UiIcon name="clock" :size="26" />
        <h2>按计划自动推进工作</h2>
        <p>设定时间与提示词，或绑定一条固定流水线，让每日巡检、审查或汇总按计划自动运行并在这里追踪结果。</p>
        <button type="button" class="primary automation-create" @click="openEditor()"><UiIcon name="plus" :size="15" />新建自动化</button>
      </div>
      <template v-else>
        <nav class="automation-list" aria-label="自动化列表">
          <button v-for="item in automations" :key="item.id" type="button" class="automation-item" :aria-current="item.id === selectedId ? 'true' : undefined" @click="select(item.id)">
            <span class="automation-item-title"><strong>{{ item.name }}</strong><span class="automation-state" :class="{ paused: !item.enabled }">{{ item.enabled ? '启用' : '已暂停' }}</span></span>
            <small>{{ scheduleLabel(item) }} · {{ item.flowId ? `流水线：${flowName(item.flowId)}` : workspaceName(item.workspaceId) }}</small>
          </button>
        </nav>
        <section v-if="selected" class="automation-detail" :aria-label="selected.name">
          <div class="automation-detail-heading">
            <div><h2>{{ selected.name }}</h2><p>{{ scheduleLabel(selected) }}<template v-if="selected.enabled && selected.nextRunAt"> · 下次 {{ formatTime(selected.nextRunAt) }}</template></p></div>
            <div class="automation-actions">
              <button type="button" :disabled="busy" @click="runNow(selected)"><UiIcon name="arrow" :size="14" />立即运行</button>
              <button type="button" :disabled="busy || selected.scheduleKind === 'MANUAL'" :title="selected.scheduleKind === 'MANUAL' ? '仅手动运行的任务无需暂停' : undefined" @click="toggle(selected)">{{ selected.enabled ? '暂停' : '恢复' }}</button>
              <button type="button" class="icon-button" :disabled="busy" aria-label="编辑自动化" title="编辑" @click="openEditor(selected)"><UiIcon name="edit" :size="15" /></button>
              <button type="button" class="icon-button" :disabled="busy" aria-label="删除自动化" title="删除" @click="confirmingDelete = true"><UiIcon name="trash" :size="15" /></button>
            </div>
          </div>
          <p v-if="confirmingDelete" role="alert" class="dialog-error inline-confirm"><span>删除「{{ selected.name }}」及其触发记录？已创建的会话或事项会保留。</span><button type="button" :disabled="busy" @click="confirmingDelete = false">取消</button><button type="button" class="primary danger" :disabled="busy" @click="remove(selected)">确认删除</button></p>
          <dl v-if="selected.flowId" class="automation-meta">
            <div><dt>执行方式</dt><dd>触发固定流水线</dd></div>
            <div><dt>目标流水线</dt><dd>{{ flowName(selected.flowId) }}</dd></div>
          </dl>
          <dl v-else class="automation-meta">
            <div><dt>工作区</dt><dd>{{ workspaceName(selected.workspaceId) }}</dd></div>
            <div><dt>模型</dt><dd>{{ profileName(selected.modelProfileId) }} · {{ selected.reasoningEffort === null ? '模型默认强度' : effortLabel(selected.reasoningEffort) }}</dd></div>
            <div><dt>命令执行</dt><dd>{{ selected.allowCommands ? (selected.approvalMode === 'MANUAL' ? '允许（每条仍需你审批）' : '允许（按审批模式处理）') : '不提供命令工具' }}</dd></div>
            <div><dt>审批模式</dt><dd>{{ approvalModeLabel(selected.approvalMode) }}</dd></div>
          </dl>
          <p v-if="selected.prompt" class="automation-prompt">{{ selected.prompt }}</p>
          <h3 class="automation-history-title">运行记录</h3>
          <ol v-if="history.length" class="automation-history">
            <li v-for="item in history" :key="item.id">
              <button type="button" :disabled="!canOpenExecution(item)" :title="canOpenExecution(item) ? (item.flowItemId ? '打开对应流水线事项' : '打开对应会话') : undefined" @click="openExecution(item)">
                <span class="execution-badge" :class="executionState(item).tone">{{ executionState(item).label }}</span>
                <span class="execution-copy"><strong>{{ formatTime(item.startedAt) }} · {{ triggerLabel(item.trigger) }}</strong><small v-if="item.detail">{{ item.detail }}</small><small v-else-if="item.scheduledFor && item.trigger === 'CATCH_UP'">原计划 {{ formatTime(item.scheduledFor) }}</small></span>
                <UiIcon v-if="canOpenExecution(item)" name="chevron" :size="13" />
              </button>
            </li>
          </ol>
          <p v-else-if="historyLoaded" class="field-note">尚无运行记录，可点击「立即运行」先试一次。</p>
        </section>
      </template>
    </div>
    <dialog ref="editor" class="model-editor-dialog automation-editor-dialog" aria-labelledby="automation-editor-title" @keydown.esc.stop @cancel.prevent="closeEditor">
      <form v-if="editingId !== null" class="model-editor-panel automation-editor" @submit.prevent="save">
        <header class="model-editor-heading"><h2 id="automation-editor-title">{{ editingId ? '编辑自动化' : '新建自动化' }}</h2><button type="button" class="icon-button" :disabled="busy" aria-label="关闭自动化编辑" @click="closeEditor"><UiIcon name="close" :size="20" /></button></header>
        <div class="model-editor-content">
          <p v-if="formError" role="alert" class="dialog-error">{{ formError }}</p>
          <fieldset :disabled="busy">
            <label for="automation-name">名称</label><input id="automation-name" v-model="form.name" required maxlength="100" placeholder="例如 每日代码巡检" />
            <label>执行目标</label>
            <div class="segmented" role="group" aria-label="执行目标">
              <button type="button" :aria-pressed="targetKind === 'session'" @click="switchTarget('session')">新建会话</button>
              <button type="button" :aria-pressed="targetKind === 'flow'" @click="switchTarget('flow')">触发流水线</button>
            </div>
            <template v-if="targetKind === 'flow'">
              <label for="automation-flow">流水线</label>
              <select id="automation-flow" v-model="form.flowId" required>
                <option value="" disabled>{{ flows.length ? '选择一条流水线' : '暂无可用流水线' }}</option>
                <option v-if="form.flowId && !flows.some(item => item.flow.id === form.flowId)" :value="form.flowId">流水线已不存在，请重新选择</option>
                <option v-for="item in flows" :key="item.flow.id" :value="item.flow.id">{{ item.flow.name }}</option>
              </select>
              <label for="automation-prompt">事项说明（可选）</label><textarea id="automation-prompt" v-model="form.prompt" maxlength="20000" rows="3" placeholder="每次触发时传给第一个岗位的背景或检查范围"></textarea>
              <p class="field-note">工作区、各岗位模型与审批模式沿用所选流水线的配置。</p>
            </template>
            <template v-else>
              <label for="automation-prompt">提示词</label><textarea id="automation-prompt" v-model="form.prompt" required rows="4" placeholder="每次运行要做什么，以及什么情况下需要汇报"></textarea>
              <label for="automation-workspace">工作区</label><select id="automation-workspace" v-model="form.workspaceId" @change="!form.workspaceId && (form.allowCommands = false)"><option value="">仅聊天</option><option v-if="form.workspaceId && !workspaces.some(item => item.id === form.workspaceId)" :value="form.workspaceId">工作区已不存在，请重新选择</option><option v-for="item in workspaces" :key="item.id" :value="item.id">{{ item.name }}</option></select>
              <div class="automation-grid">
                <div><label for="automation-model">模型</label><select id="automation-model" v-model="form.modelProfileId"><option v-if="!profiles.some(item => item.id === form.modelProfileId)" :value="form.modelProfileId">模型已不存在，请重新选择</option><option v-for="item in profiles" :key="item.id" :value="item.id">{{ item.displayName }}</option></select></div>
                <div><label for="automation-effort">思考强度</label><select id="automation-effort" v-model="form.reasoningEffort"><option :value="null">模型默认</option><option v-for="option in reasoningOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
              </div>
            </template>
            <div class="automation-grid">
              <div><label for="automation-kind">频率</label><select id="automation-kind" v-model="form.scheduleKind" aria-describedby="automation-schedule-summary"><option v-for="option in scheduleOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
              <div v-if="form.scheduleKind === 'WEEKLY'"><label for="automation-weekday">星期</label><select id="automation-weekday" v-model.number="form.scheduleWeekday"><option v-for="(day, index) in weekdays" :key="day" :value="index">{{ day }}</option></select></div>
              <div v-if="['DAILY', 'WEEKDAYS', 'WEEKLY'].includes(form.scheduleKind)"><label for="automation-time">时间</label><input id="automation-time" v-model="form.scheduleTime" type="time" required /></div>
              <div v-if="form.scheduleKind === 'HOURLY'"><label for="automation-minute">第几分钟</label><input id="automation-minute" :value="Number(form.scheduleTime.slice(3)) || 0" type="number" min="0" max="59" step="1" required @input="form.scheduleTime = `00:${String(Math.min(59, Math.max(0, Number(($event.target as HTMLInputElement).value) || 0))).padStart(2, '0')}`" /></div>
            </div>
            <template v-if="form.scheduleKind === 'CUSTOM'"><label for="automation-cron">cron 表达式</label><input id="automation-cron" v-model="form.cron" required maxlength="100" placeholder="0 9 * * 1-5" spellcheck="false" aria-describedby="automation-cron-note" /><p id="automation-cron-note" class="field-note">5 段：分 时 日 月 周；分钟需为单个数字，即最多每小时一次；“日”和“周”只能指定一个。周日为 0。</p></template>
            <div id="automation-schedule-summary" class="automation-schedule-summary"><UiIcon name="clock" :size="16" /><div><strong>{{ scheduleLabel(form) }}</strong><p v-if="form.scheduleKind !== 'MANUAL'">按运行服务的系统时区执行{{ form.enabled ? '' : ' · 定时触发已暂停' }}</p><p v-else>保存后可随时从详情中立即运行</p></div></div>
            <template v-if="targetKind === 'session'">
              <label class="check-label"><input v-model="form.allowCommands" type="checkbox" :disabled="!form.workspaceId" /> 允许提议执行命令</label>
              <label for="automation-approval">审批模式</label><select id="automation-approval" v-model="form.approvalMode" aria-describedby="automation-approval-note"><option v-for="option in approvalModeOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
              <p id="automation-approval-note" class="field-note" :role="form.approvalMode === 'FULL_ACCESS' ? 'alert' : undefined">{{ approvalModeOptions.find(option => option.value === form.approvalMode)?.description }}{{ form.approvalMode === 'FULL_ACCESS' ? `。无人值守时${form.allowCommands ? '命令和' : ''}外部工具调用会在无人确认的情况下自动执行，只建议在你信任的工作区使用。` : '' }}</p>
            </template>
            <label v-if="form.scheduleKind !== 'MANUAL'" class="check-label"><input v-model="form.enabled" type="checkbox" /> 启用定时触发</label>
          </fieldset>
        </div>
        <footer class="model-editor-footer"><span>{{ targetKind === 'flow' ? '每次运行都会发起一件新的流水线事项' : '每次运行都会新建一个会话' }}</span><button type="button" :disabled="busy" @click="closeEditor">取消</button><button type="submit" class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button></footer>
      </form>
    </dialog>
  </main>
</template>

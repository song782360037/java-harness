<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, ApiError, approvalModeOptions, reasoningOptions, type ModelProfile, type Workspace } from './api'
import { formatTime } from './automations'
import { isOpen, itemState, needsUser, pendingText, stepStateLabel, type FlowDefinition, type FlowItem, type FlowItemDetail, type FlowNode,
  type FlowProposal, type FlowSnapshot, type FlowSummary } from './flows'
import FlowDiagram from './FlowDiagram.vue'
import MarkdownContent from './MarkdownContent.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ workspaces: Workspace[]; profiles: ModelProfile[]; sidebarCollapsed: boolean; defaultWorkspaceId: string; defaultProfileId: string }>()
const emit = defineEmits<{ openSession: [id: string]; expandSidebar: [] }>()
const summaries = ref<FlowSummary[]>([])
const inbox = ref<FlowItem[]>([])
const selectedId = ref('')
const showInbox = ref(false)
const snapshot = ref<FlowSnapshot | null>(null)
const detail = ref<FlowItemDetail | null>(null)
const nodeForm = ref<FlowNode | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
// 操作失败的提示保留到下一次操作，不随轮询消失。
const actionError = ref('')
const confirming = ref<'' | 'flow' | 'item'>('')
const note = ref('')
const revise = ref('')
// 生成对话框：describe 阶段填写需求，生成后预览流程图再保存；change 表示在当前流程上修改。
const designer = ref<HTMLDialogElement>()
const design = ref<{ change: boolean; description: string; modelProfileId: string; workspaceId: string; name: string; proposal: FlowDefinition | null } | null>(null)
const generating = ref(false)
const designError = ref('')
// 表单对话框：发起事项，或修改流水线的名称、说明与工作区。
const editor = ref<HTMLDialogElement>()
const form = ref<{ kind: 'start'; title: string; input: string } | { kind: 'settings'; name: string; description: string; workspaceId: string } | null>(null)
const formError = ref('')
let pollTimer: number | undefined
let polling = false
// 轮询与操作会并发加载，只采用最后一次请求的结果。
let loadVersion = 0
// 读取事项详情时其流水线的版本；版本变化后才重新读取。
let detailVersion = ''
const flow = computed(() => snapshot.value?.flow)
const items = computed(() => [...(snapshot.value?.items || [])].sort((a, b) => Number(isOpen(b)) - Number(isOpen(a))))
const counts = computed(() => {
  const result: Record<string, { total: number; waiting: boolean }> = {}
  for (const item of snapshot.value?.items || []) {
    if (!isOpen(item)) continue
    const entry = result[item.nodeKey] ||= { total: 0, waiting: false }
    entry.total++
    if (needsUser(item)) entry.waiting = true
  }
  return result
})
const workspaceName = (id: string) => id ? props.workspaces.find(item => item.id === id)?.name || '工作区已不存在' : '未绑定工作区，岗位只能讨论'
const knownWorkspace = (id: string) => props.workspaces.some(item => item.id === id)
const message = (cause: unknown, fallback: string) => cause instanceof Error ? cause.message : fallback
// 流程修改后，旧事项所在的节点可能已不在当前定义里。
const nodeName = (item: FlowItem) => flow.value?.definition.nodes.find(node => node.key === item.nodeKey)?.name || '节点已调整'
// 打开事项时按它触发时的流程快照画图。
const shown = computed(() => detail.value && detail.value.item.flowId === flow.value?.id ? detail.value.definition : flow.value?.definition)
const efforts = reasoningOptions.filter(option => option.value)
const currentNode = computed(() => detail.value?.definition.nodes.find(node => node.key === detail.value?.item.nodeKey))

async function load() {
  const version = ++loadVersion
  try {
    const [list, waiting] = await Promise.all([api<FlowSummary[]>('/flows'), api<FlowItem[]>('/flow-items/waiting')])
    if (version !== loadVersion) return
    const id = list.some(item => item.flow.id === selectedId.value) ? selectedId.value : list[0]?.flow.id || ''
    let next = id ? snapshot.value : null
    if (id && (next?.flow.id !== id || next.version !== list.find(item => item.flow.id === id)!.version)) next = await api<FlowSnapshot>(`/flows/${id}`)
    if (version !== loadVersion) return
    let item = detail.value
    if (item) {
      const flowVersion = list.find(entry => entry.flow.id === item!.item.flowId)?.version
      let read = false
      if (flowVersion === undefined) item = null
      else if (flowVersion !== detailVersion) {
        // 事项已被删除时关闭面板；其他读取失败保留现有内容，下一轮再读。
        const current = item
        item = await api<FlowItemDetail>(`/flow-items/${current.item.id}`).then(value => { read = true; return value },
          cause => cause instanceof ApiError && cause.status === 404 ? null : current)
      }
      if (version !== loadVersion) return
      if (read) detailVersion = flowVersion!
    }
    if (id !== selectedId.value) { nodeForm.value = null; confirming.value = '' }
    summaries.value = list; inbox.value = waiting; selectedId.value = id; snapshot.value = next; detail.value = item
    if (!list.length) showInbox.value = false
    error.value = ''
  } catch (cause) { if (version === loadVersion) error.value = message(cause, '流水线读取失败') }
  finally { if (version === loadVersion) loading.value = false }
}
/** 执行一次操作并刷新；失败时在页面顶部提示。 */
async function act(work: () => Promise<void>, fallback: string) {
  busy.value = true; actionError.value = ''
  try { await work(); await load() }
  catch (cause) { actionError.value = message(cause, fallback) }
  finally { busy.value = false }
}
// 递增版本号，让进行中的轮询不会把刚关闭的事项面板写回来。
function closeSide() { loadVersion++; detail.value = null; nodeForm.value = null; confirming.value = ''; note.value = '' }
async function selectFlow(id: string) {
  closeSide(); showInbox.value = false
  if (id !== selectedId.value) { selectedId.value = id; snapshot.value = null }
  await load()
}
async function openItem(item: Pick<FlowItem, 'id' | 'flowId'>) {
  closeSide()
  await act(async () => {
    detail.value = await api<FlowItemDetail>(`/flow-items/${item.id}`)
    detailVersion = summaries.value.find(entry => entry.flow.id === item.flowId)?.version ?? ''
    if (item.flowId !== selectedId.value) { selectedId.value = item.flowId; snapshot.value = null }
    showInbox.value = false
  }, '事项读取失败')
}
function selectNode(key: string) {
  const node = flow.value?.definition.nodes.find(item => item.key === key)
  closeSide()
  if (node) nodeForm.value = { ...node }
}
const saveFlow = async (definition: FlowDefinition | null, name = flow.value!.name, description = flow.value!.description, workspaceId = flow.value!.workspaceId) => {
  snapshot.value = await api<FlowSnapshot>(`/flows/${flow.value!.id}`, { name, description, workspaceId, definition, revision: flow.value!.revision })
}
const saveNode = () => act(async () => {
  const definition = flow.value!.definition
  await saveFlow({ ...definition, nodes: definition.nodes.map(node => node.key === nodeForm.value!.key ? nodeForm.value! : node) })
  nodeForm.value = null
}, '保存失败')
const itemAction = (action: 'decide' | 'retry' | 'stop', body: object = {}) => act(async () => {
  detail.value = await api<FlowItemDetail>(`/flow-items/${detail.value!.item.id}/${action}`, body)
  note.value = ''
}, '操作失败')
const removeItem = () => act(async () => { await api(`/flow-items/${detail.value!.item.id}/delete`, {}); closeSide() }, '删除失败')
const removeFlow = () => act(async () => { await api(`/flows/${flow.value!.id}/delete`, {}); closeSide(); selectedId.value = ''; snapshot.value = null }, '删除失败')

/** 回车提交修改要求；输入法选词时的回车不算。 */
function reviseEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  void openDesigner(true)
}
async function openDesigner(change: boolean) {
  const description = change ? revise.value.trim() : ''
  if (busy.value || change && !description) return
  design.value = { change, description, modelProfileId: props.defaultProfileId || 'default', workspaceId: change ? flow.value!.workspaceId : props.defaultWorkspaceId, name: change ? flow.value!.name : '', proposal: null }
  designError.value = ''
  await nextTick()
  designer.value?.showModal()
  if (change) void generate()
  else designer.value?.querySelector<HTMLTextAreaElement>('textarea')?.focus({ preventScroll: true })
}
async function generate() {
  const draft = design.value!
  generating.value = true; designError.value = ''
  try {
    const proposal = await api<FlowProposal>('/flows/design', { description: draft.description, modelProfileId: draft.modelProfileId, definition: draft.change ? flow.value!.definition : null })
    // 生成期间对话框已关闭或重新打开时丢弃结果。
    if (design.value !== draft) return
    draft.proposal = proposal.definition
    if (!draft.change) draft.name = proposal.name
  } catch (cause) { if (design.value === draft) designError.value = message(cause, '生成失败') }
  finally { if (design.value === draft || !design.value) generating.value = false }
}
async function saveDesign() {
  const draft = design.value!
  busy.value = true; designError.value = ''
  try {
    if (draft.change) await saveFlow(draft.proposal)
    else {
      snapshot.value = await api<FlowSnapshot>('/flows', { name: draft.name, description: draft.description, workspaceId: draft.workspaceId, definition: draft.proposal })
      selectedId.value = snapshot.value.flow.id
    }
    busy.value = false
    closeDesigner(); closeSide(); revise.value = ''; showInbox.value = false
    await load()
  } catch (cause) { designError.value = message(cause, '保存失败') }
  finally { busy.value = false }
}
function closeDesigner() {
  if (busy.value) return
  designer.value?.close(); design.value = null; generating.value = false
}
async function openForm(kind: 'start' | 'settings') {
  form.value = kind === 'start' ? { kind, title: '', input: '' }
    : { kind, name: flow.value!.name, description: flow.value!.description, workspaceId: flow.value!.workspaceId }
  formError.value = ''
  await nextTick()
  editor.value?.showModal()
  editor.value?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true })
}
function closeForm() {
  if (busy.value) return
  editor.value?.close(); form.value = null
}
async function submitForm() {
  const value = form.value!
  busy.value = true; formError.value = ''
  try {
    let started: { id: string; flowId: string } | undefined
    if (value.kind === 'start') started = await api<{ id: string; flowId: string }>(`/flows/${flow.value!.id}/items`, { title: value.title, input: value.input })
    else await saveFlow(null, value.name, value.description, value.workspaceId)
    busy.value = false
    closeForm()
    if (started) await openItem(started)
    else await load()
  } catch (cause) { formError.value = message(cause, '保存失败') }
  finally { busy.value = false }
}
// 页面打开期间定时刷新，流转中的事项和新的待办会自己出现。
// 上一轮还没返回时跳过，避免慢请求被下一轮不断作废。
function poll() {
  if (busy.value || polling) return
  polling = true
  void load().finally(() => { polling = false })
}
onMounted(() => { poll(); pollTimer = window.setInterval(poll, 2000) })
onUnmounted(() => window.clearInterval(pollTimer))
</script>

<template>
  <main class="main-panel flows-page" aria-labelledby="flows-title">
    <header class="workspace-header">
      <div class="workspace-title"><button v-if="sidebarCollapsed" class="icon-button" aria-label="展开侧栏" title="展开侧栏" @click="emit('expandSidebar')"><UiIcon name="panel" /></button><span class="header-breadcrumb">工作台 <span aria-hidden="true">/</span></span><h1 id="flows-title">流水线</h1></div>
      <button v-if="summaries.length" type="button" class="primary" :disabled="busy || loading" @click="openDesigner(false)"><UiIcon name="plus" :size="15" />新建流水线</button>
    </header>
    <div v-if="actionError || error" class="error-banner" role="alert">{{ actionError || error }}</div>
    <div class="flows-body" :aria-busy="loading">
      <p v-if="loading" class="flows-empty"><span class="loading-indicator"></span>正在读取流水线…</p>
      <div v-else-if="!summaries.length" class="flows-empty">
        <UiIcon name="branch" :size="26" />
        <h2>把重复的协作固化成流水线</h2>
        <p>描述一类反复出现的工作，自动生成一条固定流程：哪些岗位依次处理、在哪里分支、哪一步需要你确认。之后每件事都沿着它流转。</p>
        <button type="button" class="primary" @click="openDesigner(false)"><UiIcon name="plus" :size="15" />新建流水线</button>
      </div>
      <template v-else>
        <nav class="flow-list" aria-label="流水线列表">
          <button type="button" class="flow-list-item inbox" :aria-current="showInbox ? 'true' : undefined" @click="closeSide(); showInbox = true"><UiIcon name="shield" :size="15" /><strong>待你处理</strong><span v-if="inbox.length" class="flow-badge waiting">{{ inbox.length }}</span></button>
          <button v-for="item in summaries" :key="item.flow.id" type="button" class="flow-list-item" :aria-current="!showInbox && item.flow.id === selectedId ? 'true' : undefined" @click="selectFlow(item.flow.id)">
            <span><strong>{{ item.flow.name }}</strong><span v-if="item.waiting" class="flow-badge waiting" :title="`${item.waiting} 件待你处理`">{{ item.waiting }}</span></span>
            <small>{{ item.flow.definition.nodes.length }} 个节点{{ item.running ? ` · ${item.running} 件在走` : '' }}</small>
          </button>
        </nav>
        <section v-if="showInbox" class="flow-main" aria-label="待你处理">
          <div class="flow-heading"><h2>待你处理</h2><p>停在人工环节、岗位没能给出结论，或岗位运行中等你审批、回答的事项。</p></div>
          <ul v-if="inbox.length" class="flow-items flow-inbox">
            <li v-for="item in inbox" :key="item.id"><button type="button" :disabled="busy" @click="openItem(item)"><span><strong>{{ item.title }}</strong><span class="flow-badge waiting">{{ item.flowName }}</span></span><small>{{ item.status === 'RUNNING' ? pendingText(item) : item.detail || '等待你确认' }} · {{ formatTime(item.updatedAt) }}</small></button></li>
          </ul>
          <p v-else class="field-note">现在没有需要你处理的事项。</p>
        </section>
        <section v-else-if="flow" class="flow-main" :aria-label="flow.name">
          <div class="flow-heading">
            <div class="flow-title">
              <h2>{{ flow.name }}</h2>
              <div class="flow-actions">
                <button type="button" class="icon-button" :disabled="busy" aria-label="编辑流水线" title="编辑名称与工作区" @click="openForm('settings')"><UiIcon name="edit" :size="15" /></button>
                <button type="button" class="icon-button" :disabled="busy" aria-label="删除流水线" title="删除" @click="confirming = 'flow'"><UiIcon name="trash" :size="15" /></button>
                <button type="button" class="primary" :disabled="busy" @click="openForm('start')"><UiIcon name="arrow" :size="14" />发起事项</button>
              </div>
            </div>
            <p class="flow-workspace" :class="{ unbound: !knownWorkspace(flow.workspaceId) }"><UiIcon name="folder" :size="13" />{{ workspaceName(flow.workspaceId) }}</p>
            <p v-if="flow.description">{{ flow.description }}</p>
          </div>
          <p v-if="confirming === 'flow'" role="alert" class="dialog-error inline-confirm"><span>删除「{{ flow.name }}」及其全部事项和过程记录？</span><button type="button" :disabled="busy" @click="confirming = ''">取消</button><button type="button" class="primary danger" :disabled="busy" @click="removeFlow">确认删除</button></p>
          <div class="flow-canvas"><FlowDiagram :definition="shown!" :counts="counts" :selected-key="nodeForm?.key" :current-key="detail?.item.flowId === flow.id ? detail.item.nodeKey : undefined" interactive @select="selectNode" /></div>
          <form class="flow-revise" @submit.prevent="openDesigner(true)">
            <textarea v-model="revise" rows="1" maxlength="2000" aria-label="修改流程" placeholder="描述你想怎么改这条流程，例如：审查通过后加一步安全检查" @keydown.enter.exact="reviseEnter"></textarea>
            <button type="submit" class="primary" :disabled="busy || !revise.trim()"><UiIcon name="wand" :size="14" />修改流程</button>
          </form>
          <p class="field-note">点流程图里的节点可以修改岗位的职责、模型和审批模式。修改只影响之后发起的事项。</p>
        </section>
        <aside class="flow-side" aria-label="事项">
          <div v-if="detail" class="flow-item-detail">
            <div class="flow-side-heading"><h3>{{ detail.item.title }}</h3><button type="button" class="icon-button" aria-label="返回事项列表" title="关闭" @click="closeSide"><UiIcon name="close" :size="16" /></button></div>
            <p><span class="flow-badge" :class="itemState(detail.item).tone">{{ itemState(detail.item).label }}</span> {{ detail.item.flowName }} · {{ formatTime(detail.item.createdAt) }}<template v-if="detail.item.detail"><br />{{ detail.item.detail }}</template></p>
            <p v-if="detail.input" class="flow-input">{{ detail.input }}</p>
            <div v-if="detail.item.status === 'WAITING'" class="flow-decision">
              <strong>{{ currentNode?.type === 'HUMAN' ? `「${currentNode.name}」需要你确认` : '请你决定下一步' }}</strong>
              <p v-if="currentNode?.type === 'HUMAN' && currentNode.description">{{ currentNode.description }}</p>
              <textarea v-model="note" maxlength="400" aria-label="说明" placeholder="可选：补充说明，后面的岗位会看到"></textarea>
              <div>
                <button v-for="(label, index) in detail.options" :key="label" type="button" :class="{ primary: index === 0 }" :disabled="busy" @click="itemAction('decide', { label, note })">{{ label || '继续' }}</button>
                <button v-if="detail.retryable" type="button" :disabled="busy" @click="itemAction('retry')"><UiIcon name="refresh" :size="13" /> 重试这个岗位</button>
              </div>
            </div>
            <div v-else-if="needsUser(detail.item)" class="flow-decision">
              <strong>{{ pendingText(detail.item) }}</strong>
              <p>{{ detail.item.pending === 'APPROVAL' ? '「' + (currentNode?.name || '岗位') + '」要执行命令或调用外部工具，批准或拒绝后才会继续。' : '「' + (currentNode?.name || '岗位') + '」向你提了问题，回答后才会继续。' }}</p>
              <div><button type="button" class="primary" :disabled="busy" @click="emit('openSession', detail.sessionId)">去处理</button></div>
            </div>
            <ol class="flow-steps">
              <li v-for="step in detail.steps" :key="step.id" :class="{ open: step.status === 'RUNNING' || step.status === 'WAITING' }">
                <div><strong>{{ step.nodeName }}</strong><span class="flow-badge" :class="{ active: step.status === 'RUNNING', waiting: step.status === 'WAITING', danger: step.status === 'FAILED' }">{{ step.outcome || stepStateLabel(step.status) }}</span></div>
                <small v-if="step.detail">{{ step.detail }}</small>
                <small v-if="step.nodeType === 'HUMAN' && step.summary">你的说明：{{ step.summary }}</small>
                <details v-else-if="step.summary"><summary>查看结论</summary><MarkdownContent :content="step.summary" /></details>
              </li>
            </ol>
            <p v-if="confirming === 'item'" role="alert" class="dialog-error inline-confirm"><span>删除这件事项及其过程记录？</span><button type="button" :disabled="busy" @click="confirming = ''">取消</button><button type="button" class="primary danger" :disabled="busy" @click="removeItem">确认删除</button></p>
            <div class="flow-actions">
              <button type="button" :disabled="busy" title="在会话视图中查看各岗位的完整过程，并处理命令与文件修改审批" @click="emit('openSession', detail.sessionId)"><UiIcon name="eye" :size="14" />查看过程</button>
              <button v-if="isOpen(detail.item)" type="button" :disabled="busy" @click="itemAction('stop')"><UiIcon name="stop" :size="14" />停止</button>
              <button v-if="detail.item.status !== 'RUNNING'" type="button" class="icon-button" :disabled="busy" aria-label="删除事项" title="删除" @click="confirming = 'item'"><UiIcon name="trash" :size="15" /></button>
            </div>
          </div>
          <form v-else-if="nodeForm" class="automation-editor flow-node-editor" @submit.prevent="saveNode">
            <div class="flow-side-heading"><h3>{{ { TRIGGER: '触发节点', AGENT: '岗位', HUMAN: '人工环节', END: '终点' }[nodeForm.type] }}</h3><button type="button" class="icon-button" aria-label="关闭" title="关闭" @click="closeSide"><UiIcon name="close" :size="16" /></button></div>
            <fieldset :disabled="busy">
              <label for="flow-node-name">名称</label><input id="flow-node-name" v-model="nodeForm.name" required maxlength="20" />
              <label for="flow-node-description">{{ nodeForm.type === 'AGENT' ? '职责' : '说明' }}</label><textarea id="flow-node-description" v-model="nodeForm.description" :required="nodeForm.type === 'AGENT'" maxlength="1000" :rows="nodeForm.type === 'AGENT' ? 6 : 3"></textarea>
              <template v-if="nodeForm.type === 'AGENT'">
                <label for="flow-node-model">模型</label><select id="flow-node-model" v-model="nodeForm.modelProfileId"><option v-if="!profiles.some(item => item.id === nodeForm!.modelProfileId)" :value="nodeForm.modelProfileId">模型已不存在，请重新选择</option><option v-for="item in profiles" :key="item.id" :value="item.id">{{ item.displayName }}</option></select>
                <label for="flow-node-effort">思考强度</label><select id="flow-node-effort" v-model="nodeForm.reasoningEffort"><option :value="null">模型默认</option><option v-for="option in efforts" :key="option.value" :value="option.value">{{ option.label }}</option></select>
                <label for="flow-node-approval">审批模式</label><select id="flow-node-approval" v-model="nodeForm.approvalMode"><option v-for="option in approvalModeOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
                <p class="field-note">{{ approvalModeOptions.find(option => option.value === nodeForm!.approvalMode)?.description }}</p>
              </template>
            </fieldset>
            <div class="flow-actions"><button type="submit" class="primary" :disabled="busy">保存</button><button type="button" :disabled="busy" @click="closeSide">取消</button></div>
          </form>
          <template v-else-if="!showInbox">
            <div class="flow-side-heading"><h3>这条流水线里的事项</h3><span v-if="items.length" class="flow-badge">{{ items.length }}</span></div>
            <ul v-if="items.length" class="flow-items">
              <li v-for="item in items" :key="item.id"><button type="button" :disabled="busy" @click="openItem(item)"><span><strong>{{ item.title }}</strong><span class="flow-badge" :class="itemState(item).tone">{{ itemState(item).label }}</span></span><small>{{ isOpen(item) ? nodeName(item) : item.detail }} · {{ formatTime(item.updatedAt) }}</small></button></li>
            </ul>
            <p v-else class="field-note">还没有事项。点「发起事项」试走一遍这条流程。</p>
          </template>
          <p v-else class="field-note">选择一件事项查看它的流转过程并做出决定。</p>
        </aside>
      </template>
    </div>
    <dialog ref="designer" class="model-editor-dialog automation-editor-dialog flow-design-dialog" aria-labelledby="flow-design-title" @keydown.esc.stop @cancel.prevent="closeDesigner">
      <form v-if="design" class="model-editor-panel automation-editor" @submit.prevent="design.proposal ? saveDesign() : generate()">
        <header class="model-editor-heading"><h2 id="flow-design-title">{{ design.change ? '修改流程' : '新建流水线' }}</h2><button type="button" class="icon-button" :disabled="busy" aria-label="关闭" @click="closeDesigner"><UiIcon name="close" :size="20" /></button></header>
        <div class="model-editor-content">
          <p v-if="designError" role="alert" class="dialog-error">{{ designError }}</p>
          <fieldset :disabled="busy || generating">
            <label for="flow-design-description">{{ design.change ? '修改要求' : '描述这类工作怎么流转' }}</label>
            <textarea id="flow-design-description" v-model="design.description" required maxlength="2000" :rows="design.proposal ? 2 : 5" placeholder="例如：每个新需求先由产品分析师澄清，再由架构师评估方案，方案有风险时让我确认，然后开发实现，测试不通过就打回开发"></textarea>
            <div class="automation-grid">
              <div><label for="flow-design-model">生成与岗位使用的模型</label><select id="flow-design-model" v-model="design.modelProfileId"><option v-if="!profiles.some(item => item.id === design!.modelProfileId)" :value="design.modelProfileId">默认模型</option><option v-for="item in profiles" :key="item.id" :value="item.id">{{ item.displayName }}</option></select></div>
              <div v-if="!design.change"><label for="flow-design-workspace">工作区</label><select id="flow-design-workspace" v-model="design.workspaceId"><option value="">不绑定（岗位只讨论）</option><option v-for="item in workspaces" :key="item.id" :value="item.id">{{ item.name }}</option></select></div>
            </div>
            <template v-if="design.proposal && !design.change"><label for="flow-design-name">名称</label><input id="flow-design-name" v-model="design.name" required maxlength="100" /></template>
          </fieldset>
          <p v-if="generating" class="flow-designing"><span class="loading-indicator"></span>正在设计流程，通常需要十几秒…</p>
          <div v-else-if="design.proposal" class="flow-design-preview"><FlowDiagram :definition="design.proposal" /></div>
        </div>
        <footer class="model-editor-footer">
          <span>{{ design.proposal ? '确认无误后保存；不满意可以调整描述重新生成。' : '生成后先预览流程图，确认再保存。' }}</span>
          <button type="button" :disabled="busy" @click="closeDesigner">取消</button>
          <button v-if="design.proposal" type="button" :disabled="busy || generating" @click="generate">重新生成</button>
          <button type="submit" class="primary" :disabled="busy || generating">{{ design.proposal ? (busy ? '保存中…' : '保存') : (generating ? '生成中…' : '生成流程') }}</button>
        </footer>
      </form>
    </dialog>
    <dialog ref="editor" class="model-editor-dialog automation-editor-dialog" aria-labelledby="flow-form-title" @keydown.esc.stop @cancel.prevent="closeForm">
      <form v-if="form" class="model-editor-panel automation-editor" @submit.prevent="submitForm">
        <header class="model-editor-heading"><h2 id="flow-form-title">{{ form.kind === 'start' ? '发起事项' : '编辑流水线' }}</h2><button type="button" class="icon-button" :disabled="busy" aria-label="关闭" @click="closeForm"><UiIcon name="close" :size="20" /></button></header>
        <div class="model-editor-content">
          <p v-if="formError" role="alert" class="dialog-error">{{ formError }}</p>
          <fieldset v-if="form.kind === 'start'" :disabled="busy">
            <label for="flow-item-title">标题</label><input id="flow-item-title" v-model="form.title" required maxlength="100" placeholder="这件事的一句话概括" />
            <label for="flow-item-input">说明</label><textarea id="flow-item-input" v-model="form.input" rows="6" maxlength="20000" placeholder="交给第一个岗位的完整信息：背景、要求、相关文件等"></textarea>
          </fieldset>
          <fieldset v-else :disabled="busy">
            <label for="flow-name">名称</label><input id="flow-name" v-model="form.name" required maxlength="100" />
            <label for="flow-description">说明</label><textarea id="flow-description" v-model="form.description" rows="3" maxlength="2000"></textarea>
            <label for="flow-workspace">工作区</label><select id="flow-workspace" v-model="form.workspaceId"><option value="">不绑定（岗位只讨论）</option><option v-if="form.workspaceId && !knownWorkspace(form.workspaceId)" :value="form.workspaceId">工作区已不存在，请重新选择</option><option v-for="item in workspaces" :key="item.id" :value="item.id">{{ item.name }}</option></select>
            <p class="field-note">工作区只对之后发起的事项生效。</p>
          </fieldset>
        </div>
        <footer class="model-editor-footer"><span>{{ form.kind === 'start' ? '事项会从触发节点开始沿流程流转' : '' }}</span><button type="button" :disabled="busy" @click="closeForm">取消</button><button type="submit" class="primary" :disabled="busy">{{ form.kind === 'start' ? '发起' : '保存' }}</button></footer>
      </form>
    </dialog>
  </main>
</template>

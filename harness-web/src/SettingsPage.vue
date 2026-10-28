<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api, reasoningOptions, effortLabel, type ModelProfile, type ProviderView, type Session, type Workspace } from './api'
import { workspaceSummary } from './workspaces'
import WorkspaceSettings from './WorkspaceSettings.vue'
import AppearanceSettings from './AppearanceSettings.vue'
import GeneralSettings from './GeneralSettings.vue'
import ExtensionCenter from './ExtensionCenter.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ initialProfileId: string; initialSection: 'general' | 'models' | 'workspace'; session?: Session; workspaces: Workspace[]; creatingWorkspace: boolean; workspaceDisabled: boolean }>()
const currentWorkspace = computed(() => props.workspaces.find(workspace => workspace.id === props.session?.workspaceId))
const emit = defineEmits<{ close: []; saved: [settings: ModelProfile]; deleted: [id: string]; changed: []; workspaceSaved: [session: Session]; busy: [value: boolean] }>()
const section = ref<'general' | 'appearance' | 'models' | 'workspace' | 'plugins'>(props.initialSection)
const pageTitle = ref<HTMLElement>()
const sections = [{ id: 'general', label: '常规', icon: 'settings', description: '调整输入和阅读偏好，更改立即生效。' },
  { id: 'appearance', label: '外观', icon: 'palette', description: '让工作台更符合你的习惯。' },
  { id: 'models', label: '模型与提供商', icon: 'cube', description: '连接你的模型服务，在对话中自由切换。' },
  { id: 'workspace', label: '工作区', icon: 'folder', description: '管理工作区名称、目录和外观，多个会话可使用同一工作区。' },
  { id: 'plugins', label: '扩展', icon: 'cube', description: '发现并管理插件、技能与 MCP 连接器。' }] as const
const sectionInfo = computed(() => sections.find(item => item.id === section.value)!)
const workspaceBusy = ref(false)
const pluginBusy = ref(false)
const providers = ref<ProviderView[]>([])
const profiles = ref<ModelProfile[]>([])
const providerId = ref('')
const editingId = ref(props.initialProfileId)
const provider = ref<ProviderView | null>(null)
const form = ref<ModelProfile | null>(null)
const apiKey = ref('')
const clearApiKey = ref(false)
const showApiKey = ref(false)
const renaming = ref(false)
const editingModel = ref(false)
const modelEditor = ref<HTMLDialogElement>()
let modelEditorTrigger: HTMLElement | null = null
const providerNameInput = ref<HTMLInputElement>()
const deletingModel = ref<ModelProfile | null>(null)
const busy = ref(false)
watch([busy, workspaceBusy, pluginBusy], () => emit('busy', busy.value || workspaceBusy.value || pluginBusy.value))
const error = ref('')
const notice = ref('')
const confirmDelete = ref<'provider' | 'model' | null>(null)
const providerModels = computed(() => profiles.value.filter(model => model.providerId === providerId.value))
const connectionFilled = computed(() => !!provider.value?.baseUrl && (!provider.value.apiKeyRequired || !clearApiKey.value && (provider.value.apiKeyConfigured || !!apiKey.value)))
// 保存结束并更新按钮状态后恢复焦点；移动模型时原按钮可能已从列表移除。
watch(editingModel, opened => {
  if (opened) return
  modelEditor.value?.close()
  const target = modelEditorTrigger?.isConnected ? modelEditorTrigger : pageTitle.value
  target?.focus({ preventScroll: true })
}, { flush: 'post' })
function resetFeedback() { error.value = ''; notice.value = ''; confirmDelete.value = null; deletingModel.value = null }
async function editModel(id: string) {
  editingId.value = id
  const selected = profiles.value.find(model => model.id === editingId.value)
  form.value = selected ? { ...selected } : null
  editingModel.value = true
  resetFeedback()
  await focusModelEditor()
}
async function focusModelEditor() {
  modelEditorTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  modelEditor.value?.showModal()
  modelEditor.value?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true })
}
/** 取消模型编辑，保留尚未保存的提供商连接。 */
function closeModelEditor() {
  if (busy.value) return
  editingModel.value = false
  resetFeedback()
}
async function renameProvider() {
  renaming.value = true
  await nextTick()
  providerNameInput.value?.focus()
}
function requestDeleteModel(model: ModelProfile) { resetFeedback(); deletingModel.value = model; confirmDelete.value = 'model' }
async function createModel() {
  if (!provider.value?.id) return
  editingId.value = ''
  form.value = { id: '', revision: 0, providerId: provider.value.id, providerName: provider.value.displayName,
    displayName: '', modelName: '', reasoningEffort: '', runTimeoutSeconds: 120, contextWindowTokens: 16000, maxOutputTokens: 4096,
    toolTimeoutSeconds: 5, maxToolsPerRequest: 32, model: { provider: 'openai-compatible', model: '', configured: false } }
  resetFeedback()
  editingModel.value = true
  await focusModelEditor()
}
function selectProvider() {
  const selected = providers.value.find(value => value.id === providerId.value)
  provider.value = selected ? { ...selected } : null
  apiKey.value = ''; clearApiKey.value = false; showApiKey.value = false
  editingId.value = ''; form.value = null; editingModel.value = false; renaming.value = false
  resetFeedback()
}
function createProvider() {
  providerId.value = ''; editingId.value = ''; form.value = null
  provider.value = { id: '', revision: 0, displayName: '', baseUrl: '', apiKeyConfigured: false, apiKeyRequired: true, connectTimeoutSeconds: 10 }
  apiKey.value = ''; clearApiKey.value = false; resetFeedback()
  showApiKey.value = false; editingModel.value = false
  void renameProvider()
}
async function fetchAll() {
  const [connections, models] = await Promise.all([api<ProviderView[]>('/model-providers'), api<ModelProfile[]>('/model-profiles')])
  providers.value = connections; profiles.value = models
}
async function load() {
  busy.value = true; resetFeedback()
  try {
    await fetchAll()
    providerId.value = profiles.value.find(model => model.id === editingId.value)?.providerId || providerId.value || providers.value[0]?.id || ''
    if (!providers.value.some(value => value.id === providerId.value)) providerId.value = providers.value[0]?.id || ''
    selectProvider()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '配置读取失败' }
  finally { busy.value = false }
}
async function saveProvider() {
  if (!provider.value || busy.value || editingModel.value) return
  busy.value = true; resetFeedback()
  try {
    const saved = await api<ProviderView>(provider.value.id ? `/model-providers/${provider.value.id}` : '/model-providers',
      { ...provider.value, apiKey: apiKey.value, clearApiKey: clearApiKey.value })
    apiKey.value = ''; providerId.value = saved.id
    await fetchAll(); selectProvider(); notice.value = '提供商已保存，该提供商下的模型将共享这些连接信息。'
    emit('changed')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提供商保存失败' }
  finally { busy.value = false }
}
async function saveModel() {
  if (!form.value || busy.value) return
  busy.value = true; resetFeedback()
  try {
    const connectionDraft = provider.value
    const saved = await api<ModelProfile>(form.value.id ? `/model-profiles/${form.value.id}` : '/model-profiles', form.value)
    editingId.value = saved.id; providerId.value = saved.providerId
    await fetchAll()
    if (connectionDraft?.id === saved.providerId) {
      // Keep unsaved connection fields while independently saving a model.
      provider.value = connectionDraft; editingModel.value = false; form.value = null
    } else selectProvider()
    notice.value = '模型已保存。'
    emit('saved', saved)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '模型保存失败' }
  finally { busy.value = false }
}
async function remove() {
  const deleting = confirmDelete.value
  if (busy.value || !deleting) return
  busy.value = true; error.value = ''
  try {
    if (deleting === 'provider' && provider.value?.id) {
      await api(`/model-providers/${provider.value.id}/delete`, { revision: provider.value.revision })
      providerId.value = ''; emit('changed')
    } else if (deleting === 'model' && deletingModel.value?.id) {
      await api(`/model-profiles/${deletingModel.value.id}/delete`, { revision: deletingModel.value.revision })
      emit('deleted', deletingModel.value.id); editingId.value = ''
    }
    await fetchAll()
    if (!providerId.value) providerId.value = providers.value[0]?.id || ''
    selectProvider(); notice.value = '已删除。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除失败' }
  finally { busy.value = false }
}
onMounted(() => { pageTitle.value?.focus(); void load() })
</script>
<template>
  <main class="settings-page reference-settings" aria-labelledby="settings-title">
    <div class="settings-layout">
      <nav class="settings-nav" aria-label="设置分类">
        <div class="settings-brand"><img class="brand-icon" src="/harness-icon.svg" width="34" height="34" alt="" /><span class="brand-copy"><strong>Harness</strong><small>本地编程工作台</small></span></div>
        <button class="back-button" aria-label="返回工作区" title="返回工作区" :disabled="busy || workspaceBusy || pluginBusy" @click="emit('close')"><UiIcon name="back" :size="16" /><span>返回工作区</span></button>
        <p class="nav-group-label">基础设置</p>
        <button v-for="item in sections" :key="item.id" :class="{ selected: section === item.id }" :aria-current="section === item.id ? 'page' : undefined" :disabled="busy || workspaceBusy || pluginBusy" @click="section = item.id"><UiIcon :name="item.icon" :size="17" />{{ item.label }}</button>
        <div class="settings-local-note">本地运行 · 配置保存在本机</div>
      </nav>
      <div class="settings-content" :class="{ 'extensions-content': section === 'plugins', 'model-settings-content': section === 'models' }">
        <div class="settings-content-inner">
          <p class="settings-eyebrow">工作台设置</p>
          <h1 id="settings-title" ref="pageTitle" class="reference-title" tabindex="-1">{{ sectionInfo.label }}</h1>
          <div class="settings-section-heading"><p>{{ sectionInfo.description }}</p><button v-if="section === 'models'" class="reload-models" :disabled="busy" aria-label="重新加载配置" @click="load"><UiIcon name="refresh" :size="15" /><span>重新加载</span></button></div>
          <p v-if="error && section === 'models' && !editingModel" role="alert" class="dialog-error">{{ error }}</p>
          <p v-if="notice && section === 'models'" role="status" class="saved-note">{{ notice }}</p>
          <GeneralSettings v-show="section === 'general'" />
          <ExtensionCenter v-if="section === 'plugins'" @busy="pluginBusy = $event" />
          <AppearanceSettings v-show="section === 'appearance'" />
          <section v-show="section === 'models'" class="provider-console" aria-label="自定义提供商与模型">
            <aside class="provider-rail" aria-label="自定义提供商">
              <div class="provider-rail-heading"><h2>提供商</h2><span>{{ providers.length.toString().padStart(2, '0') }}</span></div>
              <div class="provider-items">
                <button v-for="item in providers" :key="item.id" class="provider-item" :class="{ selected: providerId === item.id }" :disabled="busy" :aria-pressed="providerId === item.id" @click="providerId = item.id; selectProvider()">
                  <span class="provider-monogram" aria-hidden="true">{{ item.displayName.slice(0, 1).toUpperCase() }}</span><span class="provider-item-copy"><span class="provider-item-name">{{ item.displayName }}</span><small>OpenAI 兼容</small></span><span class="provider-count">{{ profiles.filter(model => model.providerId === item.id).length }}</span>
                </button>
              </div>
              <button class="add-provider" :disabled="busy" @click="createProvider"><UiIcon name="plus" :size="16" /> 添加提供商</button>
              <div class="provider-rail-note"><UiIcon name="cube" :size="17" /><strong>一个连接，多个模型</strong><p>同一提供商下的模型共享服务地址和 API Key。</p></div>
            </aside>
            <div class="provider-detail">
              <template v-if="provider">
                <div class="provider-heading">
                  <span class="provider-heading-icon"><UiIcon name="cube" :size="22" /></span>
                  <div class="provider-heading-copy"><div><h2>{{ provider.id ? provider.displayName : '新增提供商' }}</h2><button v-if="provider.id" type="button" class="icon-button" :disabled="busy || editingModel" aria-label="编辑提供商名称" title="编辑名称" @click="renameProvider"><UiIcon name="edit" :size="15" /></button></div><p>OpenAI 兼容服务</p></div>
                  <div class="provider-status"><span class="connection-badge" :class="{ ready: connectionFilled }">{{ connectionFilled ? '连接已填写' : '待配置' }}</span><small>未验证连通性</small></div>
                </div>
                <form class="provider-form" @submit.prevent="saveProvider">
                  <div class="connection-card-heading"><h3><UiIcon name="code" :size="17" />连接设置</h3><span>此提供商下的模型共享</span></div>
                  <fieldset :disabled="busy || editingModel">
                    <div v-if="renaming || !provider.id" class="connection-field"><label for="provider-name">提供商名称</label><input id="provider-name" ref="providerNameInput" v-model="provider.displayName" required maxlength="100" placeholder="例如 公司网关" /></div>
                    <div class="connection-fields">
                      <div class="connection-field"><label for="model-base-url">Base URL</label><input id="model-base-url" v-model="provider.baseUrl" required maxlength="2048" placeholder="https://your-provider.example/v1" /></div>
                      <div class="connection-field"><label for="api-format">API 格式</label><input id="api-format" class="readonly-field" value="Chat Completions (/chat/completions)" readonly /></div>
                    </div>
                    <div class="connection-field">
                      <label for="model-key">API Key <span class="key-status" :class="{ configured: provider.apiKeyConfigured }">{{ provider.apiKeyConfigured ? '已保存' : '未设置' }}</span></label>
                      <div class="key-input"><input id="model-key" v-model="apiKey" :type="showApiKey ? 'text' : 'password'" autocomplete="new-password" maxlength="8192" :disabled="clearApiKey" :placeholder="provider.apiKeyConfigured ? '留空保留已保存的密钥' : '填写 API Key'" aria-describedby="api-key-note" /><button type="button" class="icon-button" :disabled="clearApiKey" :aria-pressed="showApiKey" :aria-label="showApiKey ? '隐藏本次输入的 Key' : '显示本次输入的 Key'" @click="showApiKey = !showApiKey"><UiIcon name="eye" :size="16" /></button></div>
                      <p id="api-key-note" class="field-note">填写新值可替换密钥；已保存的密钥不会回显。</p>
                    </div>
                    <details class="connection-options"><summary><span>连接选项</span><small>{{ provider.apiKeyRequired ? '需要 API Key · ' : '' }}连接超时 {{ provider.connectTimeoutSeconds }} 秒</small></summary>
                      <div class="connection-options-fields">
                        <label class="check-label"><input v-model="provider.apiKeyRequired" type="checkbox" /> 此提供商需要 API Key</label>
                        <div class="connection-field"><label for="provider-timeout">连接超时（秒）</label><input id="provider-timeout" v-model.number="provider.connectTimeoutSeconds" type="number" required min="1" max="300" step="1" /></div>
                        <label v-if="provider.apiKeyConfigured" class="check-label clear-key"><input v-model="clearApiKey" type="checkbox" @change="apiKey = ''" /> 清除已保存的 API Key</label>
                      </div>
                    </details>
                  </fieldset>
                  <div class="provider-save"><span>此提供商下的模型共享，新任务生效</span><button type="submit" class="primary" :disabled="busy || editingModel">{{ busy ? '处理中…' : '保存提供商' }}</button></div>
                </form>
                <section class="provider-model-list" aria-labelledby="model-list-title">
                  <div class="model-list-heading"><div><h3 id="model-list-title">模型</h3><span>{{ providerModels.length }}</span></div><button type="button" class="add-model" :disabled="busy || !provider.id" @click="createModel"><UiIcon name="plus" :size="14" /> 添加模型</button></div>
                  <p class="model-list-description">添加后即可在对话中选择，按模型分别设置运行参数。</p>
                  <div v-if="providerModels.length" class="model-rows">
                    <div class="model-table-heading" aria-hidden="true"><span>显示名称 / 模型 ID</span><span>默认思考强度</span><span>操作</span></div>
                    <div v-for="item in providerModels" :key="item.id" class="model-row" :class="{ editing: editingModel && form?.id === item.id }">
                      <span class="model-row-icon"><UiIcon name="cube" :size="16" /></span>
                      <div class="model-row-info"><div><strong>{{ item.displayName }}</strong><span v-if="item.id === 'default'" class="default-model-badge">默认配置</span></div><small>{{ item.modelName }}</small></div>
                      <span class="model-effort" :title="'默认思考强度：' + effortLabel(item.reasoningEffort)">{{ effortLabel(item.reasoningEffort) }}</span>
                      <div class="model-row-actions"><button type="button" class="icon-button" :disabled="busy" :aria-label="'编辑模型 ' + item.displayName" title="编辑模型" @click="editModel(item.id)"><UiIcon name="edit" :size="15" /></button>
                      <button type="button" class="icon-button" :disabled="busy || item.id === 'default'" :aria-label="'删除模型 ' + item.displayName" :title="item.id === 'default' ? '默认模型可以编辑和移动，不能删除' : '删除模型'" @click="requestDeleteModel(item)"><svg v-if="item.id === 'default'" viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" /></svg><UiIcon v-else name="trash" :size="15" /></button></div>
                    </div>
                  </div>
                  <p v-else class="models-empty">{{ provider.id ? '尚未添加模型，添加后即可在聊天中选择。' : '先保存提供商，再添加它支持的模型。' }}</p>
                  <p v-if="providerModels.some(item => item.id === 'default')" class="model-list-note">默认配置可编辑、可移动，不能删除。</p>
                </section>
                <div v-if="provider.id" class="provider-delete"><p>删除提供商前，请先删除或移动其下的模型。</p><button type="button" :disabled="busy || !!providerModels.length" :title="providerModels.length ? '先删除或移动该提供商下的模型' : '删除提供商'" @click="confirmDelete = 'provider'"><UiIcon name="trash" :size="14" />删除提供商</button></div>
                <p v-if="confirmDelete" role="alert" class="dialog-error">确认删除{{ confirmDelete === 'provider' ? provider.displayName : deletingModel?.displayName }}？<button type="button" :disabled="busy" @click="remove">确认删除</button><button type="button" :disabled="busy" @click="resetFeedback()">取消</button></p>
              </template>
              <p v-else class="models-empty">{{ busy ? '正在读取配置…' : '添加一个自定义提供商，开始配置模型。' }}</p>
            </div>
          </section>
          <section v-show="section === 'workspace'" aria-label="工作区配置">
            <div v-if="session && !creatingWorkspace" class="workspace-context"><span class="configuration-icon"><UiIcon :name="currentWorkspace?.icon || 'folder'" :style="{ color: currentWorkspace?.color }" :size="19" /></span><div><strong>{{ currentWorkspace?.name || session.title }}</strong><p>{{ workspaceSummary(session.workspace) || '仅聊天 · 尚未选择目录' }}</p></div><span class="subtle-badge">当前工作区</span></div>
            <WorkspaceSettings :key="creatingWorkspace ? 'new' : session?.workspaceId || session?.id" class="settings-card" :session-id="creatingWorkspace ? undefined : session?.id" :initial-path="creatingWorkspace ? '' : session?.workspace || ''" :initial-workspace="creatingWorkspace ? undefined : currentWorkspace" :disabled="!creatingWorkspace && workspaceDisabled" @busy="workspaceBusy = $event" @saved="emit('workspaceSaved', $event)" @cancel="emit('close')" />
          </section>
          <p v-if="section === 'models'" class="settings-footnote">保存后对新任务生效 · 连接与模型权限会在发送消息时验证</p>
        </div>
      </div>
    </div>
    <dialog ref="modelEditor" class="model-editor-dialog" aria-labelledby="model-editor-title" @keydown.esc.stop @cancel.prevent="closeModelEditor">
      <form v-if="editingModel && form" class="model-editor-panel" @submit.prevent="saveModel">
        <header class="model-editor-heading"><div><p>模型配置</p><h2 id="model-editor-title">{{ form.id ? '编辑模型' : '添加模型' }}</h2></div><button type="button" class="icon-button" :disabled="busy" aria-label="关闭模型编辑" @click="closeModelEditor"><UiIcon name="close" :size="20" /></button></header>
        <div class="model-editor-content">
          <div class="model-editor-context"><UiIcon name="cube" :size="19" /><strong>{{ form.displayName || '新模型' }}</strong><span v-if="form.id === 'default'" class="default-model-badge">默认配置</span></div>
          <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
          <fieldset :disabled="busy">
            <label for="model-name">模型 ID</label><input id="model-name" v-model="form.modelName" required maxlength="128" placeholder="填写服务提供方的模型 ID" aria-describedby="model-id-note" />
            <p id="model-id-note" class="field-note">与服务提供方的模型 ID 保持一致。</p>
            <label for="profile-name">显示名称</label><input id="profile-name" v-model="form.displayName" required maxlength="100" placeholder="例如 日常开发 / 深度分析" />
            <label for="profile-effort">默认思考强度</label><select id="profile-effort" v-model="form.reasoningEffort" aria-describedby="model-effort-note"><option v-for="option in reasoningOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
            <p id="model-effort-note" class="field-note">可在对话中临时切换；不确定支持范围时使用服务默认。</p>
            <details class="advanced-settings" open><summary>运行参数与所属提供商</summary>
              <template v-if="form.id"><label for="model-provider">所属提供商</label><select id="model-provider" v-model="form.providerId"><option v-for="item in providers" :key="item.id" :value="item.id">{{ item.displayName }}</option></select></template>
              <div class="settings-grid">
                <label>任务总超时（秒）<input v-model.number="form.runTimeoutSeconds" type="number" required min="1" max="86400" step="1" /></label>
                <label>单工具超时（秒）<input v-model.number="form.toolTimeoutSeconds" type="number" required min="1" max="300" step="1" /></label>
                <label>上下文窗口（tokens）<input v-model.number="form.contextWindowTokens" type="number" required min="1024" max="2000000" step="1" /></label>
                <label>输出预留（tokens）<input v-model.number="form.maxOutputTokens" type="number" required min="64" max="131072" step="1" /></label>
                <label>单次工具上限<input v-model.number="form.maxToolsPerRequest" type="number" required min="1" max="256" step="1" /></label>
              </div>
            </details>
          </fieldset>
        </div>
        <footer class="model-editor-footer"><span>仅保存当前模型，新任务生效</span><button type="button" :disabled="busy" @click="closeModelEditor">取消</button><button type="submit" class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存模型' }}</button></footer>
      </form>
    </dialog>
  </main>
</template>

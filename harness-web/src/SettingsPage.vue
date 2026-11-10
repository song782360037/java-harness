<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api, reasoningOptions, effortShortLabel, type ModelProfile, type ProviderView, type Session, type Workspace } from './api'
import WorkspaceSettings from './WorkspaceSettings.vue'
import AppearanceSettings from './AppearanceSettings.vue'
import GeneralSettings from './GeneralSettings.vue'
import AboutUpdates from './AboutUpdates.vue'
import { desktopAvailable } from './desktopUpdates'
import ExtensionCenter from './ExtensionCenter.vue'
import UsageSettings from './UsageSettings.vue'
import ApprovalSettings from './ApprovalSettings.vue'
import MemorySettings from './MemorySettings.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ initialProfileId: string; initialSection: 'general' | 'models' | 'workspace' | 'approval' | 'memory' | 'updates'; session?: Session; workspaces: Workspace[]; creatingWorkspace: boolean; workspaceDisabled: boolean }>()
const currentWorkspace = computed(() => props.workspaces.find(workspace => workspace.id === props.session?.workspaceId))
const emit = defineEmits<{ close: []; saved: [settings: ModelProfile]; deleted: [id: string]; changed: []; workspaceSaved: [session: Session]; busy: [value: boolean] }>()
const section = ref<'general' | 'appearance' | 'models' | 'workspace' | 'approval' | 'plugins' | 'usage' | 'memory' | 'updates'>(props.initialSection)
const pageTitle = ref<HTMLElement>()
const sections = [{ id: 'general', label: '常规', icon: 'settings' },
  { id: 'appearance', label: '外观', icon: 'palette' },
  { id: 'models', label: '模型与提供商', icon: 'cube' },
  { id: 'workspace', label: '工作区', icon: 'folder' },
  { id: 'approval', label: '审批', icon: 'shield' },
  { id: 'memory', label: '记忆', icon: 'memory' },
  { id: 'plugins', label: '扩展', icon: 'puzzle' },
  { id: 'usage', label: '使用统计', icon: 'chart' },
  { id: 'updates', label: '关于与更新', icon: 'refresh' }] as const
const visibleSections = computed(() => sections.filter(item => item.id !== 'updates' || desktopAvailable))
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
const connectionFilled = computed(() => !!provider.value?.baseUrl.trim() && (!provider.value.apiKeyRequired || !!apiKey.value.trim()))
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
    displayName: '', modelName: '', reasoningEffort: '', runTimeoutSeconds: 6000, contextWindowTokens: 256000, maxOutputTokens: 32000,
    toolTimeoutSeconds: 300, maxToolsPerRequest: 200, model: { provider: 'openai-compatible', model: '', configured: false } }
  resetFeedback()
  editingModel.value = true
  await focusModelEditor()
}
/** 切换提供商时读取已保存的密钥，并恢复默认隐藏状态。 */
async function selectProvider() {
  provider.value = null
  apiKey.value = ''; showApiKey.value = false
  editingId.value = ''; form.value = null; editingModel.value = false; renaming.value = false
  resetFeedback()
  if (!providerId.value) return
  const selected = await api<{ provider: ProviderView; apiKey: string }>(`/model-providers/${encodeURIComponent(providerId.value)}`)
  provider.value = { ...selected.provider }
  apiKey.value = selected.apiKey
}
/** 锁定切换过程，避免旧提供商的密钥与新连接混用。 */
async function chooseProvider(id: string) {
  if (busy.value) return
  busy.value = true; providerId.value = id
  try { await selectProvider() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '配置读取失败' }
  finally { busy.value = false }
}
/** 收起操作菜单并确认删除当前提供商。 */
function requestDeleteProvider(event: Event) {
  (event.currentTarget as HTMLElement).closest('details')?.removeAttribute('open')
  resetFeedback(); confirmDelete.value = 'provider'
}
function createProvider() {
  providerId.value = ''; editingId.value = ''; form.value = null
  provider.value = { id: '', revision: 0, displayName: '', baseUrl: '', apiKeyConfigured: false, apiKeyRequired: true, connectTimeoutSeconds: 30 }
  apiKey.value = ''; resetFeedback()
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
    await selectProvider()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '配置读取失败' }
  finally { busy.value = false }
}
async function saveProvider() {
  if (!provider.value || busy.value || editingModel.value) return
  busy.value = true; resetFeedback()
  try {
    // 不再单独设置鉴权开关：空密钥表示无需鉴权，并清除旧密钥。
    const saved = await api<ProviderView>(provider.value.id ? `/model-providers/${provider.value.id}` : '/model-providers',
      { ...provider.value, apiKey: apiKey.value, apiKeyRequired: !!apiKey.value.trim(), clearApiKey: !apiKey.value.trim() })
    apiKey.value = ''; providerId.value = saved.id
    await fetchAll(); await selectProvider(); notice.value = '连接已保存。'
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
    } else await selectProvider()
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
    await selectProvider(); notice.value = '已删除。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除失败' }
  finally { busy.value = false }
}
onMounted(() => { pageTitle.value?.focus(); void load() })
</script>
<template>
  <main class="settings-page reference-settings" aria-labelledby="settings-title">
    <div class="settings-layout">
      <nav class="settings-nav" aria-label="设置分类">
        <div class="settings-brand"><img class="brand-icon" src="/harness-icon.svg" width="34" height="34" alt="" /><span class="brand-copy"><strong>Harness</strong></span></div>
        <button class="back-button" aria-label="返回工作区" title="返回工作区" :disabled="busy || workspaceBusy || pluginBusy" @click="emit('close')"><UiIcon name="back" :size="16" /><span>返回工作区</span></button>
        <p class="nav-group-label">基础设置</p>
        <button v-for="item in visibleSections" :key="item.id" :class="{ selected: section === item.id }" :aria-current="section === item.id ? 'page' : undefined" :disabled="busy || workspaceBusy || pluginBusy" @click="section = item.id"><UiIcon :name="item.icon" :size="17" />{{ item.label }}</button>
      </nav>
      <div class="settings-content" :class="{ 'extensions-content': section === 'plugins', 'model-settings-content': section === 'models', 'workspace-settings-content': section === 'workspace' }">
        <div class="settings-content-inner">
          <div class="settings-title-row"><h1 id="settings-title" ref="pageTitle" class="reference-title" tabindex="-1">{{ sectionInfo.label }}</h1><button v-if="section === 'models'" class="reload-models" :disabled="busy" aria-label="重新加载配置" @click="load"><UiIcon name="refresh" :size="15" /><span>重新加载</span></button></div>
          <p v-if="error && section === 'models' && !editingModel" role="alert" class="dialog-error">{{ error }}</p>
          <p v-if="notice && section === 'models'" role="status" class="saved-note">{{ notice }}</p>
          <GeneralSettings v-show="section === 'general'" />
          <AboutUpdates v-if="section === 'updates' && desktopAvailable" />
          <ExtensionCenter v-if="section === 'plugins'" @busy="pluginBusy = $event" />
          <AppearanceSettings v-show="section === 'appearance'" />
          <UsageSettings v-if="section === 'usage'" />
          <ApprovalSettings v-if="section === 'approval'" @busy="pluginBusy = $event" />
          <MemorySettings v-if="section === 'memory'" @busy="pluginBusy = $event" />
          <section v-show="section === 'models'" class="provider-console" aria-label="自定义提供商与模型">
            <aside class="provider-rail" aria-label="自定义提供商">
              <div class="provider-rail-heading"><h2>提供商</h2><span>{{ providers.length }}</span></div>
              <button class="add-provider" :disabled="busy" @click="createProvider"><UiIcon name="plus" :size="16" /> 添加提供商</button>
              <div class="provider-items">
                <button v-for="item in providers" :key="item.id" class="provider-item" :class="{ selected: providerId === item.id }" :disabled="busy" :aria-pressed="providerId === item.id" @click="chooseProvider(item.id)">
                  <span class="provider-monogram" aria-hidden="true">{{ item.displayName.slice(0, 1).toUpperCase() }}</span><span class="provider-item-copy"><span class="provider-item-name">{{ item.displayName }}</span><span class="provider-count">{{ profiles.filter(model => model.providerId === item.id).length }} 个模型</span></span><UiIcon name="chevron" :size="14" />
                </button>
              </div>
            </aside>
            <div class="provider-detail">
              <template v-if="provider">
                <div class="provider-heading">
                  <span class="provider-heading-icon"><UiIcon name="cube" :size="22" /></span>
                  <div class="provider-heading-copy"><div><h2>{{ provider.id ? provider.displayName : '新增提供商' }}</h2><button v-if="provider.id" type="button" class="icon-button" :disabled="busy || editingModel" aria-label="编辑提供商名称" title="编辑名称" @click="renameProvider"><UiIcon name="edit" :size="15" /></button></div></div>
                  <div class="provider-status"><span class="connection-badge" :class="{ ready: connectionFilled }">{{ connectionFilled ? '已配置' : '待配置' }}</span></div>
                  <details v-if="provider.id" class="provider-menu"><summary class="icon-button" aria-label="提供商操作"><UiIcon name="more" :size="18" /></summary><div class="provider-menu-actions"><button type="button" :disabled="busy || editingModel || !!providerModels.length" :title="providerModels.length ? '先删除或移动该提供商下的模型' : '删除提供商'" @click="requestDeleteProvider"><UiIcon name="trash" :size="14" />删除提供商</button></div></details>
                </div>
                <form class="provider-form" @submit.prevent="saveProvider">
                  <div class="connection-card-heading"><h3>连接设置</h3><div class="connection-format"><span>API 格式</span><span>Chat Completions</span></div></div>
                  <fieldset :disabled="busy || editingModel">
                    <div v-if="renaming || !provider.id" class="connection-field"><label for="provider-name">提供商名称</label><input id="provider-name" ref="providerNameInput" v-model="provider.displayName" required maxlength="100" placeholder="例如 公司网关" /></div>
                    <div class="connection-field"><label for="model-base-url">Base URL</label><input id="model-base-url" v-model="provider.baseUrl" required maxlength="2048" placeholder="https://your-provider.example/v1" spellcheck="false" /></div>
                    <div class="connection-field">
                      <label for="model-key">API Key</label>
                      <div class="key-input"><input id="model-key" v-model="apiKey" :type="showApiKey ? 'text' : 'password'" autocomplete="new-password" maxlength="8192" placeholder="填写 API Key" spellcheck="false" /><button type="button" class="icon-button" :aria-pressed="showApiKey" :aria-label="showApiKey ? '隐藏 API Key' : '显示 API Key'" :title="showApiKey ? '隐藏 API Key' : '显示 API Key'" @click="showApiKey = !showApiKey"><UiIcon :name="showApiKey ? 'eye' : 'eye-off'" :size="16" /></button></div>
                    </div>
                  </fieldset>
                  <div class="provider-save"><button type="submit" class="primary" :disabled="busy || editingModel">{{ busy ? '处理中…' : '保存连接' }}</button></div>
                </form>
                <section class="provider-model-list" aria-labelledby="model-list-title">
                  <div class="model-list-heading"><div><h3 id="model-list-title">模型</h3><span>{{ providerModels.length }}</span></div><button type="button" class="add-model" :disabled="busy || !provider.id" @click="createModel"><UiIcon name="plus" :size="14" /> 添加模型</button></div>
                  <div v-if="providerModels.length" class="model-rows">
                    <div class="model-table-heading" aria-hidden="true"><span>名称 / 模型 ID</span><span>默认思考强度</span><span>操作</span></div>
                    <div v-for="item in providerModels" :key="item.id" class="model-row" :class="{ editing: editingModel && form?.id === item.id }">
                      <div class="model-row-info"><div><strong>{{ item.displayName }}</strong><span v-if="item.id === 'default'" class="default-model-badge">默认配置</span></div><small>{{ item.modelName }}</small></div>
                      <span class="model-effort" :title="'默认思考强度：' + effortShortLabel(item.reasoningEffort)">{{ effortShortLabel(item.reasoningEffort) }}</span>
                      <div class="model-row-actions"><button type="button" class="edit-model" :disabled="busy" :aria-label="'编辑模型 ' + item.displayName" @click="editModel(item.id)">编辑</button>
                      <button type="button" class="icon-button" :disabled="busy || item.id === 'default'" :aria-label="'删除模型 ' + item.displayName" :title="item.id === 'default' ? '默认模型可以编辑和移动，不能删除' : '删除模型'" @click="requestDeleteModel(item)"><svg v-if="item.id === 'default'" viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" /></svg><UiIcon v-else name="trash" :size="15" /></button></div>
                    </div>
                  </div>
                  <p v-else class="models-empty">{{ provider.id ? '尚未添加模型，添加后即可在聊天中选择。' : '先保存提供商，再添加它支持的模型。' }}</p>
                </section>
                <p v-if="confirmDelete" role="alert" class="dialog-error inline-confirm"><span>确认删除{{ confirmDelete === 'provider' ? provider.displayName : deletingModel?.displayName }}？</span><button type="button" :disabled="busy" @click="resetFeedback()">取消</button><button type="button" class="primary danger" :disabled="busy" @click="remove">确认删除</button></p>
              </template>
              <p v-else class="models-empty">{{ busy ? '正在读取配置…' : '添加一个自定义提供商，开始配置模型。' }}</p>
            </div>
          </section>
          <section v-show="section === 'workspace'" class="workspace-settings-section" aria-label="工作区配置">
            <div v-if="session && !creatingWorkspace" class="workspace-context"><span class="configuration-icon"><UiIcon :name="currentWorkspace?.icon || 'folder'" :style="{ color: currentWorkspace?.color }" :size="19" /></span><div><strong>{{ currentWorkspace?.name || session.title }}</strong></div><span class="subtle-badge">当前工作区</span></div>
            <WorkspaceSettings :key="creatingWorkspace ? 'new' : session?.workspaceId || session?.id" class="settings-card" :session-id="creatingWorkspace ? undefined : session?.id" :initial-path="creatingWorkspace ? '' : session?.workspace || ''" :initial-workspace="creatingWorkspace ? undefined : currentWorkspace" :disabled="!creatingWorkspace && workspaceDisabled" @busy="workspaceBusy = $event" @saved="emit('workspaceSaved', $event)" @cancel="emit('close')" />
          </section>
        </div>
      </div>
    </div>
    <dialog ref="modelEditor" class="model-editor-dialog automation-editor-dialog"aria-labelledby="model-editor-title" @keydown.esc.stop @cancel.prevent="closeModelEditor">
      <form v-if="editingModel && form" class="model-editor-panel" @submit.prevent="saveModel">
        <header class="model-editor-heading"><h2 id="model-editor-title">{{ form.id ? '编辑模型' : '添加模型' }}</h2><button type="button" class="icon-button" :disabled="busy" aria-label="关闭模型编辑" @click="closeModelEditor"><UiIcon name="close" :size="20" /></button></header>
        <div class="model-editor-content">
          <div class="model-editor-context"><UiIcon name="cube" :size="19" /><strong>{{ form.displayName || '新模型' }}</strong><span v-if="form.id === 'default'" class="default-model-badge">默认配置</span></div>
          <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
          <fieldset :disabled="busy">
            <label for="model-name">模型 ID</label><input id="model-name" v-model="form.modelName" required maxlength="128" placeholder="填写服务提供方的模型 ID" />
            <label for="profile-name">显示名称</label><input id="profile-name" v-model="form.displayName" required maxlength="100" placeholder="例如 日常开发 / 深度分析" />
            <label for="profile-effort">默认思考强度</label><select id="profile-effort" v-model="form.reasoningEffort"><option v-for="option in reasoningOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
            <details class="advanced-settings"><summary>运行参数与所属提供商</summary>
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

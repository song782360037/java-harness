<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api, reasoningOptions, effortLabel, type ModelProfile, type ProviderView, type Session } from './api'
import WorkspaceSettings from './WorkspaceSettings.vue'
import AppearanceSettings from './AppearanceSettings.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ initialProfileId: string; initialSection: 'models' | 'workspace'; session?: Session; creatingWorkspace: boolean; workspaceDisabled: boolean }>()
const emit = defineEmits<{ close: []; saved: [settings: ModelProfile]; deleted: [id: string]; changed: []; workspaceSaved: [session: Session]; busy: [value: boolean] }>()
const section = ref<'appearance' | 'models' | 'workspace'>(props.initialSection)
const pageTitle = ref<HTMLElement>()
const sections = [{ id: 'appearance', label: '外观', icon: 'palette', description: '让工作台更符合你的习惯。' },
  { id: 'models', label: '模型与提供商', icon: 'cube', description: '连接你的模型服务，在对话中自由切换。' },
  { id: 'workspace', label: '工作区', icon: 'folder', description: '选择本次对话使用的项目目录。' }] as const
const sectionInfo = computed(() => sections.find(item => item.id === section.value)!)
const workspaceBusy = ref(false)
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
const modelEditor = ref<HTMLElement>()
const providerNameInput = ref<HTMLInputElement>()
const deletingModel = ref<ModelProfile | null>(null)
const busy = ref(false)
watch([busy, workspaceBusy], () => emit('busy', busy.value || workspaceBusy.value))
const error = ref('')
const notice = ref('')
const confirmDelete = ref<'provider' | 'model' | null>(null)
const providerModels = computed(() => profiles.value.filter(model => model.providerId === providerId.value))
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
  await nextTick()
  modelEditor.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  modelEditor.value?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true })
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
    displayName: '', modelName: '', reasoningEffort: '', runTimeoutSeconds: 120, contextMaxCharacters: 32000,
    maxSteps: 0, toolTimeoutSeconds: 5, model: { provider: 'openai-compatible', model: '', configured: false } }
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
        <div class="settings-brand"><UiIcon name="code" :size="23" /><strong>Harness</strong></div>
        <button class="back-button" :disabled="busy || workspaceBusy" @click="emit('close')"><UiIcon name="back" :size="16" /> 返回工作区</button>
        <p class="nav-group-label">基础设置</p>
        <button v-for="item in sections" :key="item.id" :class="{ selected: section === item.id }" :aria-current="section === item.id ? 'page' : undefined" :disabled="busy || workspaceBusy" @click="section = item.id"><UiIcon :name="item.icon" :size="17" />{{ item.label }}</button>
        <div class="settings-local-note">本地运行 · 配置保存在本机</div>
      </nav>
      <div class="settings-content">
        <div class="settings-content-inner">
          <h1 id="settings-title" ref="pageTitle" class="reference-title" tabindex="-1">{{ sectionInfo.label }}</h1>
          <div class="settings-section-heading"><p>{{ sectionInfo.description }}</p><button v-if="section === 'models'" class="icon-button" :disabled="busy" aria-label="重新加载配置" title="重新加载配置" @click="load"><UiIcon name="refresh" :size="16" /></button></div>
          <p v-if="error && section === 'models'" role="alert" class="dialog-error">{{ error }}</p>
          <p v-if="notice && section === 'models'" role="status" class="saved-note">{{ notice }}</p>
          <AppearanceSettings v-show="section === 'appearance'" />
          <section v-show="section === 'models'" class="provider-console" aria-label="自定义提供商与模型">
            <aside class="provider-rail" aria-label="自定义提供商">
              <h2>自定义提供商</h2>
              <button v-for="item in providers" :key="item.id" class="provider-item" :class="{ selected: providerId === item.id }" :disabled="busy" :aria-pressed="providerId === item.id" @click="providerId = item.id; selectProvider()">
                <UiIcon name="cube" :size="16" /><span class="provider-item-name">{{ item.displayName }}</span><span class="provider-count">{{ profiles.filter(model => model.providerId === item.id).length }}</span>
              </button>
              <button class="add-provider" :disabled="busy" @click="createProvider"><UiIcon name="plus" :size="16" /> 添加提供商</button>
            </aside>
            <div class="provider-detail">
              <template v-if="provider">
                <form class="provider-form" @submit.prevent="saveProvider">
                  <div class="provider-heading">
                    <h2>{{ provider.id ? provider.displayName : '新增提供商' }}</h2>
                    <button v-if="provider.id" type="button" class="icon-button" :disabled="busy || editingModel" aria-label="编辑提供商名称" title="编辑名称" @click="renameProvider"><UiIcon name="edit" :size="15" /></button>
                    <span class="connection-badge" :class="{ ready: provider.baseUrl && (provider.apiKeyConfigured || !provider.apiKeyRequired) }">{{ provider.baseUrl && (provider.apiKeyConfigured || !provider.apiKeyRequired) ? '连接已填写' : '待配置' }}</span>
                    <button v-if="provider.id" type="button" class="icon-button provider-delete" :disabled="busy || !!providerModels.length" aria-label="删除提供商" :title="providerModels.length ? '先删除或移动该提供商下的模型' : '删除提供商'" @click="confirmDelete = 'provider'">
                      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 10v7M14 10v7" /></svg>
                    </button>
                  </div>
                  <fieldset :disabled="busy || editingModel">
                    <template v-if="renaming || !provider.id"><label for="provider-name">提供商名称</label><input id="provider-name" ref="providerNameInput" v-model="provider.displayName" required maxlength="100" placeholder="例如 公司网关" /></template>
                    <label for="model-base-url">Base URL</label><input id="model-base-url" v-model="provider.baseUrl" required maxlength="2048" placeholder="https://your-provider.example/v1" />
                    <label for="api-format">API 格式</label><input id="api-format" class="readonly-field" value="Chat Completions (/chat/completions)" readonly />
                    <label for="model-key">API Key <span class="key-status">{{ provider.apiKeyConfigured ? '已保存' : '未设置' }}</span></label>
                    <div class="key-input"><input id="model-key" v-model="apiKey" :type="showApiKey ? 'text' : 'password'" autocomplete="new-password" maxlength="8192" :disabled="clearApiKey" :placeholder="provider.apiKeyConfigured ? '已保存 · 留空保留，填写新值替换' : '填写 API Key'" /><button type="button" class="icon-button" :disabled="clearApiKey" :aria-pressed="showApiKey" :aria-label="showApiKey ? '隐藏本次输入的 Key' : '显示本次输入的 Key'" @click="showApiKey = !showApiKey"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" /></svg></button></div>
                    <details class="connection-options"><summary>连接选项</summary>
                      <label v-if="provider.apiKeyConfigured" class="check-label"><input v-model="clearApiKey" type="checkbox" @change="apiKey = ''" /> 清除已保存的 API Key</label>
                      <label class="check-label"><input v-model="provider.apiKeyRequired" type="checkbox" /> 此提供商需要 API Key</label>
                      <label for="provider-timeout">连接超时（秒）</label><input id="provider-timeout" v-model.number="provider.connectTimeoutSeconds" type="number" required min="1" max="300" step="1" />
                    </details>
                  </fieldset>
                  <div class="provider-save"><button type="submit" :disabled="busy || editingModel">{{ busy ? '处理中…' : '保存提供商' }}</button></div>
                </form>
                <section class="provider-model-list" aria-labelledby="model-list-title">
                  <div class="model-list-heading"><h3 id="model-list-title">模型列表</h3><span>{{ providerModels.length }} 个模型</span></div>
                  <div v-if="providerModels.length" class="model-rows">
                    <div v-for="item in providerModels" :key="item.id" class="model-row" :class="{ editing: editingModel && form?.id === item.id }">
                      <div class="model-row-info"><strong>{{ item.modelName || item.displayName }}</strong><small v-if="item.modelName && item.displayName !== item.modelName">{{ item.displayName }}</small></div>
                      <span class="model-effort" :title="'默认思考强度：' + effortLabel(item.reasoningEffort)">{{ effortLabel(item.reasoningEffort).split(' · ')[0] }}</span>
                      <button type="button" class="icon-button" :disabled="busy" :aria-label="'编辑模型 ' + (item.modelName || item.displayName)" title="编辑模型" @click="editModel(item.id)"><UiIcon name="edit" :size="15" /></button>
                      <button type="button" class="icon-button" :disabled="busy || item.id === 'default'" :aria-label="'删除模型 ' + (item.modelName || item.displayName)" :title="item.id === 'default' ? '默认模型可以编辑和移动，不能删除' : '删除模型'" @click="requestDeleteModel(item)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 10v7M14 10v7" /></svg></button>
                    </div>
                  </div>
                  <p v-else class="models-empty">{{ provider.id ? '尚未添加模型，添加后即可在聊天中选择。' : '先保存提供商，再添加它支持的模型。' }}</p>
                  <button type="button" class="add-model" :disabled="busy || !provider.id" @click="createModel"><UiIcon name="plus" :size="14" /> 添加模型</button>
                </section>
                <form v-if="editingModel && form" ref="modelEditor" class="inline-model-editor" @submit.prevent="saveModel">
                  <div class="card-heading"><h3>{{ form.id ? '编辑模型' : '添加模型' }}</h3><button type="button" class="icon-button" :disabled="busy" aria-label="关闭模型编辑" @click="editingModel = false; resetFeedback()"><UiIcon name="close" :size="16" /></button></div>
                  <fieldset :disabled="busy">
                    <label for="model-name">模型 ID</label><input id="model-name" v-model="form.modelName" required maxlength="128" placeholder="填写服务提供方的模型 ID" />
                    <label for="profile-name">显示名称</label><input id="profile-name" v-model="form.displayName" required maxlength="100" placeholder="例如 日常开发 / 深度分析" />
                    <label for="profile-effort">默认思考强度</label><select id="profile-effort" v-model="form.reasoningEffort"><option v-for="option in reasoningOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
                    <p class="field-note">可在聊天输入框旁临时切换；不确定支持范围时，使用服务默认。</p>
                    <details class="advanced-settings"><summary>运行参数与所属提供商</summary>
                      <template v-if="form.id"><label for="model-provider">所属提供商</label><select id="model-provider" v-model="form.providerId"><option v-for="item in providers" :key="item.id" :value="item.id">{{ item.displayName }}</option></select></template>
                      <div class="settings-grid">
                        <label>任务总超时（秒）<input v-model.number="form.runTimeoutSeconds" type="number" required min="1" max="86400" step="1" /></label>
                        <label>上下文容量（字符）<input v-model.number="form.contextMaxCharacters" type="number" required min="4000" max="256000" step="1" /></label>
                        <label>模型步数上限（0 不限）<input v-model.number="form.maxSteps" type="number" required min="0" max="2147483647" step="1" /></label>
                        <label>单工具超时（秒）<input v-model.number="form.toolTimeoutSeconds" type="number" required min="1" max="300" step="1" /></label>
                      </div>
                    </details>
                  </fieldset>
                  <div class="dialog-actions"><button type="button" :disabled="busy" @click="editingModel = false; resetFeedback()">取消</button><button class="primary" :disabled="busy">保存模型</button></div>
                </form>
                <p v-if="confirmDelete" role="alert" class="dialog-error">确认删除{{ confirmDelete === 'provider' ? provider.displayName : deletingModel?.displayName }}？<button type="button" :disabled="busy" @click="remove">确认删除</button><button type="button" :disabled="busy" @click="resetFeedback()">取消</button></p>
              </template>
              <p v-else class="models-empty">{{ busy ? '正在读取配置…' : '添加一个自定义提供商，开始配置模型。' }}</p>
            </div>
          </section>
          <section v-show="section === 'workspace'" aria-label="工作区配置">
            <div v-if="session && !creatingWorkspace" class="workspace-context"><span class="configuration-icon"><UiIcon name="folder" :size="19" /></span><div><strong>{{ session.title }}</strong><p>{{ session.workspace || '仅聊天 · 尚未选择目录' }}</p></div><span class="subtle-badge">当前会话</span></div>
            <WorkspaceSettings class="settings-card" :session-id="creatingWorkspace ? undefined : session?.id" :initial-path="creatingWorkspace ? '' : session?.workspace || ''" :disabled="!creatingWorkspace && workspaceDisabled" @busy="workspaceBusy = $event" @saved="emit('workspaceSaved', $event)" />
          </section>
          <p v-if="section === 'models'" class="settings-footnote">保存后对新任务生效 · 连接与模型权限会在发送消息时验证</p>
        </div>
      </div>
    </div>
  </main>
</template>

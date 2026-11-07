<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type ModelProfile } from './api'
import type { MemorySettings, MemoryState } from './memory'
import PreferenceSelect from './PreferenceSelect.vue'
const emit = defineEmits<{ busy: [value: boolean] }>()
const state = ref<MemoryState>()
const form = ref<MemorySettings>()
const baseline = ref<MemorySettings>()
const dirty = computed(() => !!form.value && (form.value.enabled !== baseline.value?.enabled || form.value.modelProfileId !== baseline.value?.modelProfileId))
const profiles = ref<ModelProfile[]>([])
const busy = ref(false), error = ref(''), notice = ref('')
const loadingError = ref('')
const modelOptions = computed(() => profiles.value.map(profile => ({ value: profile.id,
  label: profile.displayName + (profile.model.configured ? '' : '（未配置）'), disabled: !profile.model.configured })))
const processed = computed(() => (state.value?.progress.completed || 0) + (state.value?.progress.skipped || 0))
let version = 0, disposed = false
let timer: ReturnType<typeof setTimeout> | undefined

/** 后台轮询只更新真实状态，保留表单中尚未保存的更改。 */
async function load(reset = false) {
  if (busy.value) { clearTimeout(timer); timer = setTimeout(() => { void load() }, 3000); return }
  if (reset) error.value = ''
  const current = ++version
  try {
    const [value, models] = await Promise.all([api<MemoryState>('/memory-settings'),
      reset || !profiles.value.length ? api<ModelProfile[]>('/model-profiles') : Promise.resolve(undefined)])
    if (disposed || current !== version) return
    state.value = value
    if (models) profiles.value = models
    if (reset || !form.value || form.value.enabled === baseline.value?.enabled && form.value.modelProfileId === baseline.value?.modelProfileId) {
      form.value = { ...value.settings }; baseline.value = { ...value.settings }
    }
    loadingError.value = ''
  } catch (cause) { if (!disposed && current === version) loadingError.value = message(cause) }
  finally {
    clearTimeout(timer)
    if (!disposed) timer = setTimeout(() => { void load() }, 3000)
  }
}
/** 保存后以服务端响应为准，关闭开关会取消当前生成。 */
async function save() {
  if (!form.value || busy.value) return
  version++; busy.value = true; emit('busy', true); error.value = ''; notice.value = ''
  try {
    state.value = await api<MemoryState>('/memory-settings', form.value)
    form.value = { ...state.value.settings }
    baseline.value = { ...state.value.settings }
    notice.value = state.value.settings.enabled ? '已开启，将自动分析历史对话并持续更新记忆' : '已关闭，已有记忆保留'
  } catch (cause) { error.value = message(cause) }
  finally { busy.value = false; emit('busy', false) }
}
/** 重试失败记录，不重新分析已完成来源。 */
async function retry() {
  if (busy.value) return
  version++; busy.value = true; emit('busy', true); error.value = ''
  try { state.value = await api<MemoryState>('/memory-settings/retry', {}) }
  catch (cause) { error.value = message(cause) }
  finally { busy.value = false; emit('busy', false) }
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : '记忆设置操作失败' }
onMounted(() => { void load(true) })
onUnmounted(() => { disposed = true; version++; clearTimeout(timer) })
</script>
<template>
  <section class="memory-settings" aria-label="自动记忆设置">
    <p v-if="error || loadingError" class="dialog-error" role="alert">{{ error || loadingError }} <button :disabled="busy" @click="load(true)">重新加载设置</button></p>
    <form v-if="form" @submit.prevent="save">
      <div class="general-settings">
        <div class="preference-row"><div class="preference-copy"><label for="automatic-memory">启用自动记忆</label><p>从对话中生成记忆并用于后续交流；关闭后已有记忆保留</p></div><input id="automatic-memory" v-model="form.enabled" type="checkbox" :disabled="busy" /></div>
        <div class="preference-row"><div class="preference-copy"><label id="memory-model-label" for="memory-model">生成模型</label><p id="memory-model-help">会产生额外的模型调用</p></div><PreferenceSelect id="memory-model" v-model="form.modelProfileId" :options="modelOptions" :disabled="busy" /></div>
      </div>
      <div class="memory-settings-actions"><span v-if="dirty" class="settings-save-hint">有未保存的更改</span><span v-else-if="notice" role="status" class="saved-note">{{ notice }}</span><span v-else class="settings-save-hint">更改后需保存才会生效</span><button class="primary" :disabled="busy">{{ busy ? '处理中…' : '保存设置' }}</button></div>
    </form>
    <section v-if="state" class="memory-processing" aria-label="自动记忆处理进度">
      <div class="memory-processing-heading"><h2>对话处理进度</h2><span class="memory-processing-state" :class="{ active: state.settings.enabled && (state.progress.running || state.progress.pending), enabled: state.settings.enabled }">{{ !state.settings.enabled ? '已关闭' : state.progress.running ? '正在生成' : state.progress.pending ? '等待处理' : '已开启' }}</span></div>
      <div class="memory-processing-summary"><strong>{{ processed }}</strong><span>/ {{ state.progress.total }} 段对话已处理</span></div>
      <div class="memory-progress" role="progressbar" aria-label="已处理对话" :aria-valuenow="processed" aria-valuemin="0" :aria-valuemax="state.progress.total"><span :style="{ width: `${state.progress.total ? Math.min(100, processed / state.progress.total * 100) : 0}%` }"></span></div>
      <dl class="memory-processing-counts">
        <div><dt>待处理</dt><dd>{{ state.progress.pending }}</dd></div>
        <div><dt>生成中</dt><dd>{{ state.progress.running }}</dd></div>
        <div><dt>已完成</dt><dd>{{ state.progress.completed }}</dd></div>
        <div><dt>已跳过</dt><dd>{{ state.progress.skipped }}</dd></div>
        <div :class="{ failed: state.progress.failed }"><dt>失败</dt><dd>{{ state.progress.failed }}</dd></div>
      </dl>
      <div v-if="state.progress.failed" class="memory-processing-failed"><p>部分对话生成失败，请检查模型连接后重试。</p><button :disabled="busy || !state.settings.enabled" @click="retry">重试失败的对话</button></div>
    </section>
  </section>
</template>

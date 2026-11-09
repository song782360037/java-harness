<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type ApprovalReviewerSettings, type ApprovalRules, type ModelProfile } from './api'
import SettingsLoading from './SettingsLoading.vue'
import UiIcon from './UiIcon.vue'
const emit = defineEmits<{ busy: [value: boolean] }>()
const kinds = [
  { key: 'deny', label: '拒绝', icon: 'close', help: '直接拒绝，组合命令逐段检查。', placeholder: 'git push\nnpm publish' },
  { key: 'ask', label: '询问', icon: 'chat', help: '始终交给你确认，完全访问也不例外。', placeholder: 'git checkout\ngit reset' },
  { key: 'allow', label: '允许', icon: 'check', help: '智能审批下自动批准，仅匹配不含 ; | & > $ ( ) 的单条命令。', placeholder: 'mvn test\nnpm test' },
] as const
const texts = ref<Record<'deny' | 'ask' | 'allow', string>>({ deny: '', ask: '', allow: '' })
const baseline = ref({ ...texts.value })
const dirty = computed(() => kinds.some(kind => texts.value[kind.key] !== baseline.value[kind.key]))
// 与保存时一致：忽略空行。
const ruleCount = (key: 'deny' | 'ask' | 'allow') => texts.value[key].split('\n').filter(line => line.trim()).length
const revision = ref(0), loaded = ref(false), busy = ref(false), error = ref(''), notice = ref('')
// 每行一条规则，按词前缀匹配，不区分大小写。
function apply(rules: ApprovalRules) {
  revision.value = rules.revision
  texts.value = { deny: rules.deny.join('\n'), ask: rules.ask.join('\n'), allow: rules.allow.join('\n') }
  baseline.value = { ...texts.value }
}
async function load() {
  error.value = ''
  try { apply(await api<ApprovalRules>('/approval-policy')); loaded.value = true }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '审批规则读取失败' }
}
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''
  const lines = (value: string) => value.split('\n').map(line => line.trim()).filter(Boolean)
  try {
    apply(await api<ApprovalRules>('/approval-policy', { revision: revision.value, deny: lines(texts.value.deny), ask: lines(texts.value.ask), allow: lines(texts.value.allow) }))
    return true
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败'; return false }
  finally { busy.value = false }
}
const reviewerOptions = [
  { value: 'NONE', label: '不使用', help: '只按规则审批，规则未覆盖的动作交给你确认。' },
  { value: 'JEV', label: '仅 Jev', help: 'TypeSafe Jev 高置信的结论直接生效，其余交给你确认。' },
  { value: 'LLM', label: '仅 LLM', help: '用选定的模型判断风险与授权，并给出理由。' },
  { value: 'JEV_LLM', label: 'Jev → LLM', help: 'Jev 先判，没把握的再交 LLM，仍无结论时交给你。' },
] as const
const reviewer = ref<ApprovalReviewerSettings | null>(null)
const profiles = ref<ModelProfile[]>([])
const jevApiKey = ref(''), clearJevApiKey = ref(false)
const reviewerBusy = ref(false), reviewerError = ref('')
const reviewerSnapshot = () => JSON.stringify({ ...reviewer.value, jevApiKey: jevApiKey.value, clearJevApiKey: clearJevApiKey.value })
const reviewerBaseline = ref('')
const reviewerDirty = computed(() => !!reviewer.value && reviewerSnapshot() !== reviewerBaseline.value)
async function loadReviewer() {
  reviewerError.value = ''
  try {
    const [settings, models] = await Promise.all([api<ApprovalReviewerSettings>('/approval-reviewer'), api<ModelProfile[]>('/model-profiles')])
    reviewer.value = settings; profiles.value = models; jevApiKey.value = ''; clearJevApiKey.value = false
    reviewerBaseline.value = reviewerSnapshot()
  } catch (cause) { reviewerError.value = cause instanceof Error ? cause.message : '评审器设置读取失败' }
}
async function saveReviewer() {
  if (!reviewer.value || reviewerBusy.value) return
  reviewerBusy.value = true; reviewerError.value = ''
  try {
    // 隐藏区块里的输入不提交：未使用 Jev 时既不改 Key 也不清除；超时为空时回到默认值。
    const value = reviewer.value
    reviewer.value = await api<ApprovalReviewerSettings>('/approval-reviewer', { ...value,
      jevTimeoutSeconds: Number(value.jevTimeoutSeconds) || 10, llmTimeoutSeconds: Number(value.llmTimeoutSeconds) || 20,
      jevApiKey: usesJev() && !clearJevApiKey.value ? jevApiKey.value : '', clearJevApiKey: usesJev() && clearJevApiKey.value })
    jevApiKey.value = ''; clearJevApiKey.value = false
    reviewerBaseline.value = reviewerSnapshot()
    return true
  } catch (cause) { reviewerError.value = cause instanceof Error ? cause.message : '保存失败'; return false }
  finally { reviewerBusy.value = false }
}
/** 规则和评审器用同一个保存入口，只提交有变化的部分，两者互不影响。 */
async function saveAll() {
  // 入口先定下提交范围，保存期间的新输入留到下一次保存。
  const rules = dirty.value, review = reviewerDirty.value
  if (busy.value || reviewerBusy.value || !rules && !review) return
  notice.value = ''; emit('busy', true)
  const saved = [rules ? await save() : true, review ? await saveReviewer() : true]
  emit('busy', false)
  if (saved.every(Boolean)) notice.value = '已保存，对之后提出的动作生效'
}
const usesJev = () => reviewer.value?.reviewers === 'JEV' || reviewer.value?.reviewers === 'JEV_LLM'
const usesLlm = () => reviewer.value?.reviewers === 'LLM' || reviewer.value?.reviewers === 'JEV_LLM'
onMounted(() => { void load(); void loadReviewer() })
</script>
<template>
  <section class="approval-settings" aria-label="审批规则">
    <p class="approval-note"><UiIcon name="shield" :size="16" /><span>规则在智能审批与完全访问下生效，这两种模式下文件修改直接写入（可撤销），受保护路径（如 .github、AGENTS.md）仍需人工审核。高危命令（如删除根目录、磁盘操作）始终需要你确认。</span></p>
    <p v-if="error" role="alert" class="dialog-error">{{ error }} <button type="button" :disabled="busy" @click="load">{{ loaded ? '重新加载（放弃未保存的修改）' : '重试' }}</button></p>
    <p v-if="reviewerError" role="alert" class="dialog-error">{{ reviewerError }} <button type="button" :disabled="reviewerBusy" @click="loadReviewer">重新加载</button></p>
    <SettingsLoading v-if="!loaded && !reviewer && !error && !reviewerError" label="正在读取审批规则" />
    <form v-if="loaded || reviewer" class="approval-form" @submit.prevent="saveAll">
    <section v-if="loaded" class="approval-card">
      <header class="approval-card-heading"><h2>命令规则</h2><p>每行一条，按命令前缀匹配</p></header>
      <div class="approval-rules">
        <div v-for="kind in kinds" :key="kind.key" class="approval-rule" :class="`is-${kind.key}`">
          <div class="approval-rule-heading"><span class="approval-rule-icon" aria-hidden="true"><UiIcon :name="kind.icon" :size="14" /></span><label :for="`approval-${kind.key}`">{{ kind.label }}</label><span class="approval-rule-count">{{ ruleCount(kind.key) }} 条</span></div>
          <p :id="`approval-${kind.key}-help`">{{ kind.help }}</p>
          <textarea :id="`approval-${kind.key}`" v-model="texts[kind.key]" rows="6" spellcheck="false" placeholder="暂无规则，每行输入一条命令前缀" :aria-describedby="`approval-${kind.key}-help approval-${kind.key}-example`" :disabled="busy" />
          <small :id="`approval-${kind.key}-example`" class="approval-rule-example">示例：{{ kind.placeholder.split('\n').join('、') }}</small>
        </div>
      </div>
    </section>
    <section v-if="reviewer" class="approval-card">
      <header class="approval-card-heading"><h2 id="approval-reviewers-title">智能审批评审器</h2><p>规则未覆盖的动作先由评审器判断</p></header>
      <div class="reviewer-options" role="radiogroup" aria-labelledby="approval-reviewers-title">
        <label v-for="option in reviewerOptions" :key="option.value" class="reviewer-option" :class="{ selected: reviewer.reviewers === option.value }">
          <input v-model="reviewer.reviewers" type="radio" name="approval-reviewers" :value="option.value" :disabled="reviewerBusy" />
          <strong>{{ option.label }}<span v-if="option.value === 'JEV_LLM'" class="reviewer-badge">推荐</span></strong>
          <small>{{ option.help }}</small>
        </label>
      </div>
      <div v-if="usesJev() || usesLlm()" class="reviewer-config">
        <div v-if="usesJev()" class="reviewer-panel">
          <h3>TypeSafe Jev</h3>
          <p>你的消息、命令和工具参数会发送到 TypeSafe 云端。</p>
          <div class="approval-grid">
            <label>API Key<input v-model="jevApiKey" class="approval-input" type="password" autocomplete="off" :placeholder="reviewer.jevApiKeyConfigured ? '已保存，留空保持不变' : '填写 TypeSafe API Key'" :disabled="reviewerBusy || clearJevApiKey" /></label>
            <label>模型<input v-model="reviewer.jevModel" class="approval-input" spellcheck="false" :disabled="reviewerBusy" /></label>
            <label>服务地址<input v-model="reviewer.jevBaseUrl" class="approval-input" spellcheck="false" :disabled="reviewerBusy" /></label>
            <label>超时（秒）<input v-model.number="reviewer.jevTimeoutSeconds" class="approval-input" type="number" min="1" max="60" :disabled="reviewerBusy" /></label>
          </div>
          <label v-if="reviewer.jevApiKeyConfigured" class="approval-check"><input v-model="clearJevApiKey" type="checkbox" :disabled="reviewerBusy" @change="jevApiKey = ''" /> 清除已保存的 Key</label>
        </div>
        <div v-if="usesLlm()" class="reviewer-panel">
          <h3>LLM 评审</h3>
          <p>每次评审额外调用一次模型。</p>
          <div class="approval-grid">
            <label>评审模型<select v-model="reviewer.llmProfileId" class="approval-input" :disabled="reviewerBusy"><option value="">请选择模型</option><option v-for="profile in profiles" :key="profile.id" :value="profile.id" :disabled="!profile.model.configured && profile.id !== reviewer.llmProfileId">{{ profile.providerName }} / {{ profile.displayName }}{{ profile.model.configured ? '' : '（待配置）' }}</option></select></label>
            <label>超时（秒）<input v-model.number="reviewer.llmTimeoutSeconds" class="approval-input" type="number" min="1" max="120" :disabled="reviewerBusy" /></label>
          </div>
        </div>
      </div>
    </section>
    <footer class="approval-card-footer approval-save-bar"><span v-if="dirty || reviewerDirty" class="settings-save-hint">有未保存的更改</span><span v-else-if="notice" role="status" class="saved-note">{{ notice }}</span><span v-else class="settings-save-hint">修改后保存，对之后提出的动作生效</span><button type="submit" class="primary" :disabled="busy || reviewerBusy">{{ busy || reviewerBusy ? '保存中…' : '保存' }}</button></footer>
    </form>
  </section>
</template>

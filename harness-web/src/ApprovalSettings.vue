<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type ApprovalReviewerSettings, type ApprovalRules, type ModelProfile } from './api'
const emit = defineEmits<{ busy: [value: boolean] }>()
const kinds = [
  { key: 'deny', label: '拒绝规则', help: '智能审批与完全访问下直接拒绝，命令不会执行。也会检查组合命令中的每一段。', placeholder: 'git push\nnpm publish' },
  { key: 'ask', label: '询问规则', help: '始终交给你确认，完全访问也不例外。', placeholder: 'git checkout\ngit reset' },
  { key: 'allow', label: '允许规则', help: '智能审批下自动批准。只匹配不含 ; | & > $ ( ) 等结构的单条命令，不能只写解释器名。', placeholder: 'mvn test\nnpm test' },
] as const
const texts = ref<Record<'deny' | 'ask' | 'allow', string>>({ deny: '', ask: '', allow: '' })
const revision = ref(0), loaded = ref(false), busy = ref(false), error = ref(''), notice = ref('')
// 每行一条规则，按词前缀匹配，不区分大小写。
function apply(rules: ApprovalRules) {
  revision.value = rules.revision
  texts.value = { deny: rules.deny.join('\n'), ask: rules.ask.join('\n'), allow: rules.allow.join('\n') }
}
async function load() {
  error.value = ''
  try { apply(await api<ApprovalRules>('/approval-policy')); loaded.value = true }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '审批规则读取失败' }
}
async function save() {
  if (busy.value) return
  busy.value = true; emit('busy', true); error.value = ''; notice.value = ''
  const lines = (value: string) => value.split('\n').map(line => line.trim()).filter(Boolean)
  try {
    apply(await api<ApprovalRules>('/approval-policy', { revision: revision.value, deny: lines(texts.value.deny), ask: lines(texts.value.ask), allow: lines(texts.value.allow) }))
    notice.value = '已保存，对之后提出的命令生效'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { busy.value = false; emit('busy', false) }
}
const reviewerOptions = [
  { value: 'NONE', label: '不使用评审器', help: '只按规则审批，规则未覆盖的动作交给你确认。' },
  { value: 'JEV', label: '仅 Jev', help: 'TypeSafe Jev 高置信的结论直接生效，其余交给你确认。' },
  { value: 'LLM', label: '仅 LLM', help: '用选定的模型判断风险与授权，并给出理由。' },
  { value: 'JEV_LLM', label: 'Jev → LLM（推荐）', help: 'Jev 先判，没把握的再交 LLM，仍无结论时交给你。' },
] as const
const reviewer = ref<ApprovalReviewerSettings | null>(null)
const profiles = ref<ModelProfile[]>([])
const jevApiKey = ref(''), clearJevApiKey = ref(false)
const reviewerBusy = ref(false), reviewerError = ref(''), reviewerNotice = ref('')
async function loadReviewer() {
  reviewerError.value = ''
  try {
    const [settings, models] = await Promise.all([api<ApprovalReviewerSettings>('/approval-reviewer'), api<ModelProfile[]>('/model-profiles')])
    reviewer.value = settings; profiles.value = models; jevApiKey.value = ''; clearJevApiKey.value = false
  } catch (cause) { reviewerError.value = cause instanceof Error ? cause.message : '评审器设置读取失败' }
}
async function saveReviewer() {
  if (!reviewer.value || reviewerBusy.value) return
  reviewerBusy.value = true; emit('busy', true); reviewerError.value = ''; reviewerNotice.value = ''
  try {
    // 隐藏区块里的输入不提交：未使用 Jev 时既不改 Key 也不清除；超时为空时回到默认值。
    const value = reviewer.value
    reviewer.value = await api<ApprovalReviewerSettings>('/approval-reviewer', { ...value,
      jevTimeoutSeconds: Number(value.jevTimeoutSeconds) || 10, llmTimeoutSeconds: Number(value.llmTimeoutSeconds) || 20,
      jevApiKey: usesJev() && !clearJevApiKey.value ? jevApiKey.value : '', clearJevApiKey: usesJev() && clearJevApiKey.value })
    jevApiKey.value = ''; clearJevApiKey.value = false
    reviewerNotice.value = '已保存，对之后提出的动作生效'
  } catch (cause) { reviewerError.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { reviewerBusy.value = false; emit('busy', false) }
}
const usesJev = () => reviewer.value?.reviewers === 'JEV' || reviewer.value?.reviewers === 'JEV_LLM'
const usesLlm = () => reviewer.value?.reviewers === 'LLM' || reviewer.value?.reviewers === 'JEV_LLM'
onMounted(() => { void load(); void loadReviewer() })
</script>
<template>
  <section class="approval-settings" aria-label="审批规则">
    <p class="approval-intro">审批模式在输入框下方切换，随每次发送记录到任务上。<strong>手动审批</strong>不使用这些规则；<strong>智能审批</strong>自动批准只读命令、允许规则和下方评审器判定安全的动作；<strong>完全访问</strong>自动批准其余命令和外部工具。删除根目录、用户目录、磁盘操作、下载后直接执行等高危命令在任何模式下都需要你确认。</p>
    <p v-if="error" role="alert" class="dialog-error">{{ error }} <button type="button" :disabled="busy" @click="load">{{ loaded ? '重新加载（放弃未保存的修改）' : '重试' }}</button></p>
    <form v-if="loaded" @submit.prevent="save">
      <div v-for="kind in kinds" :key="kind.key" class="approval-rule-field">
        <label :for="`approval-${kind.key}`">{{ kind.label }}</label>
        <p :id="`approval-${kind.key}-help`">{{ kind.help }}</p>
        <textarea :id="`approval-${kind.key}`" v-model="texts[kind.key]" rows="4" spellcheck="false" :placeholder="kind.placeholder" :aria-describedby="`approval-${kind.key}-help`" :disabled="busy" />
      </div>
      <div class="approval-actions"><span v-if="notice" role="status" class="saved-note">{{ notice }}</span><button type="submit" class="primary" :disabled="busy">保存规则</button></div>
    </form>
    <h2 class="approval-section-title">智能审批评审器</h2>
    <p class="approval-intro">规则未覆盖的命令和插件工具调用，可以先交给评审器判断。评审只看你的消息、任务已执行的工具调用和待审批动作，不看助手回答和工具输出；超时、出错或没有把握时一律交给你确认，评审期间你也可以直接批准或拒绝。同一任务被评审器拒绝 3 次（连续）或 20 次后，改为人工确认。</p>
    <p v-if="reviewerError" role="alert" class="dialog-error">{{ reviewerError }} <button type="button" :disabled="reviewerBusy" @click="loadReviewer">重新加载</button></p>
    <form v-if="reviewer" @submit.prevent="saveReviewer">
      <div class="approval-rule-field">
        <label for="approval-reviewers">评审器</label>
        <p id="approval-reviewers-help">{{ reviewerOptions.find(option => option.value === reviewer?.reviewers)?.help }}</p>
        <select id="approval-reviewers" v-model="reviewer.reviewers" class="approval-input" aria-describedby="approval-reviewers-help" :disabled="reviewerBusy">
          <option v-for="option in reviewerOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
      </div>
      <div v-if="usesJev()" class="approval-rule-field">
        <label>TypeSafe Jev</label>
        <p>启用后，你的消息、命令文本和工具参数会发送到 TypeSafe 云端服务（仅托管 API）。Jev 只返回风险概率，理由由本机根据概率生成。</p>
        <div class="approval-grid">
          <label>API Key<input v-model="jevApiKey" class="approval-input" type="password" autocomplete="off" :placeholder="reviewer.jevApiKeyConfigured ? '已保存，留空保持不变' : '填写 TypeSafe API Key'" :disabled="reviewerBusy || clearJevApiKey" /></label>
          <label>模型<input v-model="reviewer.jevModel" class="approval-input" spellcheck="false" :disabled="reviewerBusy" /></label>
          <label>服务地址<input v-model="reviewer.jevBaseUrl" class="approval-input" spellcheck="false" :disabled="reviewerBusy" /></label>
          <label>超时（秒）<input v-model.number="reviewer.jevTimeoutSeconds" class="approval-input" type="number" min="1" max="60" :disabled="reviewerBusy" /></label>
        </div>
        <label v-if="reviewer.jevApiKeyConfigured" class="approval-check"><input v-model="clearJevApiKey" type="checkbox" :disabled="reviewerBusy" @change="jevApiKey = ''" /> 清除已保存的 Key</label>
      </div>
      <div v-if="usesLlm()" class="approval-rule-field">
        <label>LLM 评审</label>
        <p>使用已配置的模型，每次评审多一次模型调用，计入任务超时和使用统计。</p>
        <div class="approval-grid">
          <label>评审模型<select v-model="reviewer.llmProfileId" class="approval-input" :disabled="reviewerBusy"><option value="">请选择模型</option><option v-for="profile in profiles" :key="profile.id" :value="profile.id" :disabled="!profile.model.configured && profile.id !== reviewer.llmProfileId">{{ profile.providerName }} / {{ profile.displayName }}{{ profile.model.configured ? '' : '（待配置）' }}</option></select></label>
          <label>超时（秒）<input v-model.number="reviewer.llmTimeoutSeconds" class="approval-input" type="number" min="1" max="120" :disabled="reviewerBusy" /></label>
        </div>
      </div>
      <div class="approval-actions"><span v-if="reviewerNotice" role="status" class="saved-note">{{ reviewerNotice }}</span><button type="submit" class="primary" :disabled="reviewerBusy">保存评审器</button></div>
    </form>
    <p class="approval-intro">智能审批与完全访问下，模型对工作区文件的修改会直接写入，可在差异卡片中撤销；.github、.mvn、.vscode、mvnw、AGENTS.md 等受保护路径仍需在任务结束后人工审核。</p>
  </section>
</template>

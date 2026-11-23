<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { api, type ExternalInteraction } from './api'

const props = defineProps<{ runId: string; revision: string; active: boolean }>()
const emit = defineEmits<{ activity: [label: string] }>()
const requests = ref<ExternalInteraction[]>([])
const answers = reactive<Record<string, Record<string, unknown>>>({})
const busy = ref(''), error = ref(''), readError = ref('')
let generation = 0

function statusLabel(item: ExternalInteraction) {
  if (item.status === 'PENDING') return item.kind === 'permission' ? '等待审批' : '等待回答'
  if (item.status === 'ANSWERED') return item.kind === 'permission' ? '已处理' : '已回答'
  if (item.status === 'DECLINED') return item.kind === 'permission' ? '已拒绝' : '已跳过'
  if (item.status === 'CANCELLED') return '已失效'
  return item.status
}

function isRequired(item: ExternalInteraction, key: string) {
  return !!item.schema?.required?.includes(key)
}

function recordedSummary(item: ExternalInteraction): string[] {
  const answer = item.answer
  if (!answer || typeof answer !== 'object') return []
  const record = answer as Record<string, unknown>
  if (item.kind === 'permission') {
    const outcome = record.outcome as Record<string, unknown> | undefined
    if (outcome?.outcome === 'selected' && typeof outcome.optionId === 'string') {
      const matched = item.choices.find(choice => choice.id === outcome.optionId)
      return [matched ? `已选择：${matched.name}` : `已选择：${outcome.optionId}`]
    }
    return []
  }
  if (record.action === 'accept' && record.content && typeof record.content === 'object') {
    const propsMap = item.schema?.properties ?? {}
    return Object.entries(record.content as Record<string, unknown>).map(([key, value]) => {
      const label = propsMap[key]?.title || key
      const rendered = typeof value === 'boolean' ? (value ? '是' : '否') : String(value)
      return `${label}：${rendered}`
    })
  }
  return []
}

/** Refresh from the run event stream without overwriting an in-progress form. */
async function refresh() {
  const version = ++generation
  try {
    const list = await api<ExternalInteraction[]>(`/runs/${props.runId}/external-interactions`)
    if (version !== generation) return
    readError.value = ''
    for (const item of list) if (item.schema && !answers[item.id]) {
      answers[item.id] = Object.fromEntries(Object.entries(item.schema.properties)
        .map(([key, field]) => [key, field.default !== undefined ? field.default : '']))
    }
    requests.value = list
    const pending = list.filter(item => item.status === 'PENDING')
    emit('activity', !props.active ? '' : pending.some(item => item.kind === 'permission') ? '等待审批' : pending.length ? '等待回答' : '')
  } catch (cause) {
    if (version === generation) readError.value = cause instanceof Error ? cause.message : '外部智能体请求读取失败，请刷新重试。'
  }
}

/** Send only this request's selected option or form values. */
async function decide(item: ExternalInteraction, action: 'accept' | 'decline', choice?: string) {
  if (busy.value || !props.active) return
  busy.value = item.id; error.value = ''
  try {
    const raw = answers[item.id] ?? {}
    const value = action === 'decline'
      ? null
      : item.kind === 'permission'
        ? choice
        : Object.fromEntries(Object.entries(raw)
          .filter(([key, value]) => (value !== '' && value !== undefined) || isRequired(item, key))
          .map(([key, value]) => [key, item.schema?.properties[key]?.type === 'boolean' ? value === true || value === 'true' : value]))
    await api(`/runs/${props.runId}/external-interactions/${item.id}`, { action, value })
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提交失败，请重试。' }
  finally { busy.value = '' }
}

watch(() => props.revision, () => { void refresh() }, { immediate: true })
</script>

<template>
  <section v-if="requests.length || error || readError" class="command-reviews" aria-label="外部智能体请求">
    <h3>外部智能体请求 <span>{{ requests.length }}</span></h3>
    <p v-if="readError" class="dialog-error" role="alert">{{ readError }} <button type="button" @click="refresh">刷新记录</button></p>
    <p v-if="error" class="dialog-error" role="alert">{{ error }}</p>
    <article v-for="item in requests" :key="item.id" class="command-card external-request-card" :aria-label="`${item.title}，${statusLabel(item)}`">
      <details class="command-details" :open="item.status === 'PENDING'">
        <summary>
          <span class="command-decided">{{ item.kind === 'permission' ? '审批' : '追问' }}</span>
          <span class="command-summary-title">{{ item.title }}</span>
          <span class="command-status" :data-status="item.status">{{ statusLabel(item) }}</span>
        </summary>
        <p v-if="item.kind === 'form' && item.details" class="command-purpose">{{ item.details }}</p>
        <pre v-else-if="item.details" class="command-source">{{ item.details }}</pre>
        <template v-if="active && item.status === 'PENDING'">
          <form v-if="item.kind === 'form' && item.schema && answers[item.id]" class="external-form" @submit.prevent="decide(item, 'accept')">
            <div class="external-form-fields">
              <label v-for="(field, key) in item.schema.properties" :key="key" class="external-field">
                <span class="external-field-label">{{ field.title || key }}<span v-if="isRequired(item, key)" class="required-mark" aria-hidden="true">*</span></span>
                <small v-if="field.description" class="external-field-desc">{{ field.description }}</small>
                <select v-if="field.enum || field.type === 'boolean'" v-model="answers[item.id]![key]" :required="isRequired(item, key)" :disabled="!!busy">
                  <option value="">未选择</option>
                  <template v-if="field.type === 'boolean'"><option :value="true">是</option><option :value="false">否</option></template>
                  <option v-for="option in field.enum" :key="option" :value="option">{{ option }}</option>
                </select>
                <input v-else-if="field.type === 'string'" v-model="answers[item.id]![key]" class="external-input" :minlength="field.minLength" :maxlength="field.maxLength ?? 2000" :required="isRequired(item, key)" :disabled="!!busy">
                <input v-else v-model.number="answers[item.id]![key]" class="external-input" type="number" :step="field.type === 'integer' ? 1 : 'any'" :min="field.minimum" :max="field.maximum" :required="isRequired(item, key)" :disabled="!!busy">
              </label>
            </div>
            <footer>
              <span role="status">等待回答计入任务超时，5 分钟未处理将自动失效。</span>
              <div>
                <button type="button" :disabled="!!busy" @click="decide(item, 'decline')">跳过</button>
                <button type="submit" class="primary" :disabled="!!busy">{{ busy === item.id ? '提交中…' : '提交回答' }}</button>
              </div>
            </footer>
          </form>
          <footer v-else>
            <span role="status">仅本次生效；等待审批计入任务超时，5 分钟未处理将自动失效。</span>
            <div>
              <button v-if="!item.choices.some(option => !option.allow)" type="button" :disabled="!!busy" @click="decide(item, 'decline')">拒绝</button>
              <button v-for="choice in item.choices" :key="choice.id" type="button" :class="{ primary: choice.allow }" :disabled="!!busy" @click="decide(item, 'accept', choice.id)">{{ choice.name }}</button>
            </div>
          </footer>
        </template>
        <ul v-else-if="recordedSummary(item).length" class="question-answer">
          <li v-for="line in recordedSummary(item)" :key="line">{{ line }}</li>
        </ul>
      </details>
    </article>
  </section>
</template>

<style scoped>
.external-form-fields { display: grid; gap: 12px; padding: 0 14px 14px; }
.external-field { display: grid; gap: 6px; font-size: var(--font-caption); color: var(--text); }
.external-field-label { font-weight: 550; }
.required-mark { color: var(--danger); margin-left: 3px; }
.external-field-desc { color: var(--muted); line-height: 1.6; }
.external-input { width: 100%; min-height: 38px; padding: 8px 10px; border: 1px solid var(--border-strong); border-radius: var(--radius-md); background: var(--surface); color: var(--text); font-size: var(--font-body); box-sizing: border-box; }
.external-input:disabled { opacity: .55; cursor: not-allowed; }
.external-request-card .command-status[data-status="DECLINED"],
.external-request-card .command-status[data-status="CANCELLED"] { color: var(--muted); }
</style>

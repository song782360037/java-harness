<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api, type UserQuestion } from './api'
const props = defineProps<{ runId: string; revision: string; active: boolean }>()
const emit = defineEmits<{ activity: [value: string] }>()
const questions = ref<UserQuestion[]>([])
// 每个问题的草稿：选中的选项与补充说明。
const chosen = ref<Record<string, string[]>>({})
const notes = ref<Record<string, string>>({})
const error = ref('')
const busy = ref(false)
const labels: Record<string, string> = { PENDING: '等待你的回答', ANSWERED: '已回答', SKIPPED: '已跳过', CANCELLED: '已取消', INTERRUPTED: '已中断' }
let loading = false, dirty = false, disposed = false, version = 0
async function load() {
  if (loading) { dirty = true; return }
  loading = true
  const current = version
  try {
    const values = await api<UserQuestion[]>(`/runs/${props.runId}/questions`)
    if (!disposed && current === version) {
      questions.value = values
      if (error.value === '提问记录读取失败，请刷新重试。') error.value = ''
    }
  } catch { if (!disposed) error.value = '提问记录读取失败，请刷新重试。' }
  finally { loading = false; if (dirty && !disposed) { dirty = false; void load() } }
}
function toggle(question: UserQuestion, label: string) {
  const current = chosen.value[question.id] || []
  chosen.value[question.id] = current.includes(label) ? current.filter(value => value !== label)
    : question.multiSelect ? [...current, label] : [label]
}
const ready = (question: UserQuestion) => (chosen.value[question.id]?.length ?? 0) > 0 || !!notes.value[question.id]?.trim()
async function submit(question: UserQuestion, action: 'answer' | 'skip') {
  if (busy.value) return
  busy.value = true; error.value = ''; version++
  try {
    const body = action === 'answer' ? { selected: chosen.value[question.id] || [], note: notes.value[question.id] || '' } : {}
    const value = await api<UserQuestion>(`/questions/${question.id}/${action}`, body)
    if (!disposed) questions.value = questions.value.map(item => item.id === value.id ? value : item)
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '提交结果暂时无法确认，请刷新记录。' }
  finally { busy.value = false; if (!disposed) void load() }
}
function retry() { error.value = ''; void load() }
onMounted(load)
watch(() => props.revision, load)
watch(questions, values => emit('activity', values.some(value => value.status === 'PENDING') ? '等待你的回答' : ''), { immediate: true })
onUnmounted(() => { disposed = true })
</script>
<template>
  <section v-if="questions.length || error" class="command-reviews" aria-label="向你提问">
    <h3>提问 <span>{{ questions.length }}</span></h3>
    <p v-if="error" class="dialog-error" role="alert">{{ error }} <button type="button" @click="retry">刷新记录</button></p>
    <article v-for="question in questions" :key="question.id" class="command-card question-card" :aria-label="`${question.question}，${labels[question.status] || question.status}`">
      <details class="command-details" :open="question.status === 'PENDING'">
        <summary><span class="command-summary-title">{{ question.question }}</span><span class="command-status" :data-status="question.status">{{ labels[question.status] || question.status }}</span></summary>
        <p class="command-purpose">{{ question.question }}</p>
        <div v-if="question.status === 'PENDING'" class="question-options" role="group" :aria-label="`${question.question}（${question.multiSelect ? '可多选' : '单选'}）`">
          <button v-for="option in question.options" :key="option.label" type="button" class="question-option" :class="{ selected: chosen[question.id]?.includes(option.label) }"
            :aria-pressed="!!chosen[question.id]?.includes(option.label)" :disabled="busy || !active" @click="toggle(question, option.label)">
            <strong>{{ option.label }}</strong><span v-if="option.description">{{ option.description }}</span>
          </button>
          <label class="sr-only" :for="`question-note-${question.id}`">补充说明</label>
          <textarea :id="`question-note-${question.id}`" v-model="notes[question.id]" class="question-note" rows="2" maxlength="2000" :disabled="busy || !active" placeholder="也可以直接写下你的回答或补充说明…" />
        </div>
        <ul v-else-if="question.status === 'ANSWERED'" class="question-answer">
          <li v-for="label in question.selected" :key="label">{{ label }}</li>
          <li v-if="question.note" class="question-answer-note">{{ question.note }}</li>
        </ul>
        <footer>
          <span role="status">{{ question.detail || (question.status === 'PENDING' ? (question.multiSelect ? '可选择多项。' : '选择一项后提交。') + '等待回答计入任务超时，5 分钟未回答将自动失效。' : '') }}</span>
          <div v-if="question.status === 'PENDING'">
            <button type="button" :disabled="busy || !active" @click="submit(question, 'skip')">跳过</button>
            <button type="button" class="primary" :disabled="busy || !active || !ready(question)" @click="submit(question, 'answer')">提交回答</button>
          </div>
        </footer>
      </details>
    </article>
  </section>
</template>

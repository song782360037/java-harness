<script setup lang="ts">
import { computed, ref } from 'vue'
import type { FileChange } from './api'
import { fileDiff } from './fileDiff'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ change: FileChange; canApply: boolean; busy: boolean }>()
const emit = defineEmits<{ apply: []; reject: [] }>()
const expanded = ref(false)
const rows = computed(() => expanded.value ? fileDiff(props.change.beforeContent, props.change.afterContent) : [])
const labels: Record<string, string> = { PENDING: '待审核', APPLYING: '正在应用', APPLIED: '已应用', REJECTED: '已拒绝', CONFLICT: '文件冲突', UNCERTAIN: '需检查文件' }
const format = (text: string | null) => text === null ? '新文件' : `UTF-8${text.startsWith('\uFEFF') ? ' BOM' : ''} · ${text.includes('\r\n') ? 'CRLF' : 'LF'}`
</script>
<template>
  <article class="file-change-card">
    <div class="file-change-heading">
      <button class="file-change-toggle" type="button" :aria-expanded="expanded" @click="expanded = !expanded">
        <UiIcon :name="expanded ? 'down' : 'chevron'" :size="14" /><UiIcon name="code" :size="15" />
        <span>{{ change.path }}</span><small>{{ change.operation === 'create' ? '新建' : '修改' }}</small>
      </button>
      <span class="change-status" :class="change.status.toLowerCase()">{{ labels[change.status] || change.status }}</span>
    </div>
    <div v-if="expanded" class="file-change-diff">
      <p class="diff-format">{{ format(change.beforeContent) }} → {{ format(change.afterContent) }}</p>
      <div v-if="rows?.length" class="diff-scroll" tabindex="0" :aria-label="change.path + ' 的文件差异'">
        <table class="diff-table"><caption class="sr-only">文件差异，减号表示删除，加号表示新增</caption><tbody>
          <tr v-for="(row, index) in rows" :key="index" :class="row.kind">
            <td class="line-number">{{ row.before }}</td><td class="line-number">{{ row.after }}</td>
            <td class="diff-sign">{{ row.kind === 'add' ? '+' : row.kind === 'remove' ? '−' : '' }}</td><td class="diff-code">{{ row.text }}</td>
          </tr>
        </tbody></table>
      </div>
      <p v-else class="diff-format">{{ rows === null ? '差异较大，请展开下方完整内容进行审核。' : '创建空文件。' }}</p>
      <details class="full-file-content"><summary>查看完整修改前后内容</summary>
        <h4>修改前{{ change.beforeContent === null ? '（文件不存在）' : '' }}</h4><pre>{{ change.beforeContent ?? '' }}</pre>
        <h4>修改后</h4><pre>{{ change.afterContent }}</pre>
      </details>
    </div>
    <p v-if="change.detail" class="change-detail" role="status">{{ change.detail }}</p>
    <div v-if="change.status === 'PENDING'" class="file-change-actions">
      <span>{{ canApply ? '审核差异后，确认应用到本机文件。' : '任务正常完成且位于原工作区时可应用。' }}</span>
      <button type="button" :disabled="busy" @click="emit('reject')">拒绝</button>
      <button type="button" class="primary" :disabled="busy || !canApply || !expanded" :title="expanded ? '应用此文件的修改' : '先展开并审核文件差异'" @click="emit('apply')">{{ busy ? '处理中…' : '应用修改' }}</button>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from './api'
import { cacheRate, chartDays, compactTokens, dayTotal, heatmapWeeks, type UsageStatistics } from './usage'
import UiIcon from './UiIcon.vue'
const ranges = [{ days: 1, label: '今天' }, { days: 7, label: '7 天' }, { days: 30, label: '30 天' }, { days: 0, label: '全部' }] as const
const colors: Record<string, string> = { harness: 'var(--accent)', 'claude-code': 'var(--warning)', codex: 'var(--info)', workbuddy: 'var(--source-rose)', qoder: 'var(--source-violet)' }
const days = ref<number>(7)
const stats = ref<UsageStatistics | null>(null)
const loading = ref(false)
// 请求超过 300ms 才显示等待动效，避免缓存命中时闪烁。
const slow = ref(false)
let slowTimer: number | undefined
const heatScroll = ref<HTMLElement>()
const error = ref('')
const total = computed(() => stats.value ? stats.value.totals.inputTokens + stats.value.totals.outputTokens : 0)
const bars = computed(() => chartDays(stats.value?.days ?? []))
const peak = computed(() => Math.max(1, ...bars.value.map(dayTotal)))
const weekdays = ['日', '一', '二', '三', '四', '五', '六']
const weeks = computed(() => stats.value ? heatmapWeeks(stats.value.heatmap) : [])
const heatLabel = computed(() => {
  const tokens = stats.value?.heatmap.tokens ?? []
  return `近一年每日用量热力图：${tokens.filter(value => value > 0).length} 天有用量，合计 ${compactTokens(tokens.reduce((sum, value) => sum + value, 0))} tokens`
})
const labels = computed(() => Object.fromEntries((stats.value?.sources ?? []).map(source => [source.id, source.label])))
const percent = (value: number) => `${(value * 100).toFixed(value > 0 && value < 0.1 ? 1 : 0)}%`
const number = (value: number) => value.toLocaleString('zh-CN')
/** 拉取所选范围的统计；首次读取本地 agent 日志可能需要数秒。 */
async function load(range = days.value) {
  if (loading.value) return
  loading.value = true; error.value = ''
  slowTimer = window.setTimeout(() => { slow.value = true }, 300)
  // 成功后才切换范围，失败时按钮与数据仍保持一致。
  try {
    stats.value = await api<UsageStatistics>(`/usage?days=${range}`); days.value = range
    // 窄屏下热力图可横向滚动，默认停在最近几周。
    await nextTick()
    if (heatScroll.value) heatScroll.value.scrollLeft = heatScroll.value.scrollWidth
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '统计读取失败' }
  finally { window.clearTimeout(slowTimer); slow.value = false; loading.value = false }
}
const color = (source: string) => colors[source] ?? 'var(--muted)'
function barLabel(date: string, tokens: Record<string, number>) {
  const parts = Object.entries(tokens).map(([source, value]) => `${labels.value[source] ?? source} ${compactTokens(value)}`)
  return `${date}：${parts.length ? parts.join('，') : '无用量'}`
}
onMounted(load)
onBeforeUnmount(() => window.clearTimeout(slowTimer))
</script>
<template>
  <section class="usage-settings" aria-label="使用统计" :aria-busy="loading">
    <div class="usage-toolbar">
      <div class="usage-ranges" role="group" aria-label="统计范围">
        <button v-for="range in ranges" :key="range.days" type="button" :class="{ selected: days === range.days }" :aria-pressed="days === range.days" :disabled="loading" @click="range.days !== days && load(range.days)">{{ range.label }}</button>
      </div>
      <button type="button" class="usage-refresh" :disabled="loading" @click="load()"><UiIcon name="refresh" :size="15" :class="{ spinning: slow }" /><span>{{ loading ? '读取中' : '刷新' }}</span></button>
    </div>
    <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
    <div v-if="slow && !stats" class="usage-loading" role="status">
      <p><span class="loading-indicator" aria-hidden="true" />正在读取用量数据<small>首次需要解析本机各 agent 的日志，可能需要数秒</small></p>
      <div class="usage-cards" aria-hidden="true"><div v-for="item in 5" :key="item" class="usage-skeleton" /></div>
      <div class="usage-skeleton tall" aria-hidden="true" />
    </div>
    <div v-if="stats" class="usage-body" :class="{ stale: slow }">
      <div class="usage-cards">
        <div><span>总 tokens</span><strong :title="number(total)">{{ compactTokens(total) }}</strong></div>
        <div><span>输入</span><strong :title="number(stats.totals.inputTokens)">{{ compactTokens(stats.totals.inputTokens) }}</strong></div>
        <div><span>输出</span><strong :title="number(stats.totals.outputTokens)">{{ compactTokens(stats.totals.outputTokens) }}</strong></div>
        <div><span>缓存命中</span><strong>{{ percent(cacheRate(stats.totals)) }}</strong><small>{{ compactTokens(stats.totals.cachedTokens) }}</small></div>
        <div><span>模型调用</span><strong>{{ number(stats.totals.calls) }}</strong></div>
      </div>
      <div class="usage-heading-row"><h2 class="usage-heading">热力图</h2><span>{{ stats.heatmap.zone }}</span></div>
      <div class="usage-heatmap">
        <div ref="heatScroll" class="heat-scroll">
          <div class="heat-grid" role="img" :aria-label="heatLabel" :style="{ '--weeks': weeks.length }">
            <span aria-hidden="true" /><span v-for="day in weekdays" :key="day" class="heat-weekday" aria-hidden="true">{{ day }}</span>
            <template v-for="week in weeks" :key="week.days[0].date">
              <span class="heat-month" aria-hidden="true">{{ week.month }}</span>
              <i v-for="day in week.days" :key="day.date" :class="`level-${day.level}`" :title="`${day.date}：${compactTokens(day.tokens)} tokens`" />
              <span v-for="pad in 7 - week.days.length" :key="`pad-${pad}`" aria-hidden="true" />
            </template>
          </div>
        </div>
        <div class="heat-legend" aria-hidden="true"><span>少</span><i v-for="level in 5" :key="level" :class="`level-${level - 1}`" /><span>多</span></div>
      </div>
      <h2 class="usage-heading">{{ bars.length === stats.days.length ? '每日用量' : '每月用量' }}</h2>
      <div class="usage-chart" role="list" aria-label="用量趋势">
        <div v-for="bar in bars" :key="bar.date" class="usage-bar" role="listitem" :title="barLabel(bar.date, bar.tokens)" :aria-label="barLabel(bar.date, bar.tokens)">
          <div class="usage-stack" :style="{ height: `${dayTotal(bar) / peak * 100}%` }">
            <span v-for="source in stats.sources" v-show="bar.tokens[source.id]" :key="source.id" :style="{ flexGrow: bar.tokens[source.id] ?? 0, background: color(source.id) }" />
          </div>
        </div>
      </div>
      <div class="usage-axis" aria-hidden="true"><span>{{ bars[0]?.date }}</span><span v-if="bars.length > 1">{{ bars[bars.length - 1]?.date }}</span></div>
      <h2 class="usage-heading">来源</h2>
      <ul class="usage-sources">
        <li v-for="source in stats.sources" :key="source.id">
          <i :style="{ background: color(source.id) }" aria-hidden="true" />
          <span>{{ source.label }}</span>
          <small v-if="!source.available">未检测到本地日志</small>
          <strong>{{ compactTokens(source.totals.inputTokens + source.totals.outputTokens) }}</strong>
          <small>{{ number(source.totals.calls) }} 次</small>
        </li>
      </ul>
      <h2 class="usage-heading">模型</h2>
      <p v-if="!stats.models.length" class="usage-empty">所选范围内没有用量记录。</p>
      <div v-else class="usage-table-wrap">
        <table class="usage-table">
          <thead><tr><th scope="col">模型</th><th scope="col">来源</th><th scope="col">输入</th><th scope="col">输出</th><th scope="col">缓存</th><th scope="col">调用</th></tr></thead>
          <tbody>
            <tr v-for="item in stats.models" :key="`${item.source}/${item.model}`">
              <th scope="row" :title="item.model">{{ item.model }}</th>
              <td>{{ labels[item.source] ?? item.source }}</td>
              <td>{{ compactTokens(item.totals.inputTokens) }}</td>
              <td>{{ compactTokens(item.totals.outputTokens) }}</td>
              <td>{{ percent(cacheRate(item.totals)) }}</td>
              <td>{{ number(item.totals.calls) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="settings-footnote">Java Harness 用量含已删除的会话；Claude Code、Codex、WorkBuddy、Qoder 用量读取自本机 ~/.claude、~/.codex、~/.workbuddy、~/.qoder 下的会话日志，Qoder 内置模型只记录 credits，不计入 token。输入 tokens 均含缓存命中部分。</p>
    </div>
  </section>
</template>

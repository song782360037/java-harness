<script setup lang="ts">
import { computed } from 'vue'
import type { SessionUsage } from './api'
// estimated/maxTokens 来自最近一次请求的上下文估算；服务端报告的输入 tokens 优先。
const props = defineProps<{ usage: SessionUsage | null | undefined; estimated: number; maxTokens: number }>()
const radius = 7
const circumference = 2 * Math.PI * radius
const reported = computed(() => (props.usage?.lastInputTokens ?? 0) > 0)
const used = computed(() => reported.value ? props.usage!.lastInputTokens : props.estimated)
const ratio = computed(() => props.maxTokens > 0 ? Math.min(1, used.value / props.maxTokens) : 0)
const level = computed(() => ratio.value >= 0.9 ? 'danger' : ratio.value >= 0.7 ? 'warning' : '')
const hasTotals = computed(() => !!props.usage && props.usage.inputTokens + props.usage.outputTokens > 0)
const cacheRate = computed(() => props.usage?.inputTokens ? props.usage.cachedTokens / props.usage.inputTokens : 0)
const speed = computed(() => props.usage?.durationMillis ? props.usage.outputTokens / (props.usage.durationMillis / 1000) : 0)
/** 以 k / M 缩写 token 数量。 */
function compact(value: number) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 1 : 2)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(value >= 100_000 ? 0 : 1)}k`
  return String(value)
}
const percent = (value: number) => `${(value * 100).toFixed(value > 0 && value < 0.1 ? 1 : 0)}%`
const label = computed(() => `上下文已用 ${percent(ratio.value)}，${used.value} / ${props.maxTokens} tokens`)
</script>
<template>
  <div v-if="maxTokens > 0" class="token-usage" :class="level">
    <button type="button" class="token-ring" :aria-label="label" aria-describedby="token-usage-details" @click="($event.currentTarget as HTMLElement).focus()" @keydown.esc="($event.currentTarget as HTMLElement).blur()">
      <svg viewBox="0 0 18 18" width="18" height="18" aria-hidden="true">
        <circle class="track" cx="9" cy="9" :r="radius" />
        <circle class="value" cx="9" cy="9" :r="radius" :stroke-dasharray="circumference" :stroke-dashoffset="circumference * (1 - ratio)" />
      </svg>
    </button>
    <div id="token-usage-details" role="tooltip" class="token-popover">
      <div class="token-heading"><span>上下文</span><strong>{{ percent(ratio) }}</strong></div>
      <p class="token-context">{{ compact(used) }} / {{ compact(maxTokens) }} tokens<small v-if="!reported"> · 估算</small></p>
      <div class="token-heading section"><span>本会话累计</span></div>
      <dl v-if="hasTotals">
        <dt>输入</dt><dd>{{ compact(usage!.inputTokens) }}</dd>
        <dt>输出</dt><dd>{{ compact(usage!.outputTokens) }}</dd>
        <dt>缓存命中</dt><dd>{{ compact(usage!.cachedTokens) }}<small> · {{ percent(cacheRate) }}</small></dd>
        <dt>生成速率</dt><dd>{{ speed ? `${speed.toFixed(1)} tok/s` : '—' }}</dd>
      </dl>
      <p v-else class="token-empty">模型服务尚未报告用量</p>
    </div>
  </div>
</template>
<style scoped>
.token-usage { position: relative; display: flex; align-items: center; align-self: center; }
.token-ring { display: grid; place-items: center; width: 28px; height: 28px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--accent); cursor: default; }
.token-ring:hover, .token-ring:focus-visible { background: var(--subtle); }
.warning .token-ring { color: var(--warning); }
.danger .token-ring { color: var(--danger); }
svg { transform: rotate(-90deg); }
circle { fill: none; stroke-width: 2.2; }
.track { stroke: var(--border-strong); }
.value { stroke: currentColor; stroke-linecap: round; transition: stroke-dashoffset .3s ease; }
.token-popover { position: absolute; right: -6px; bottom: calc(100% + 10px); z-index: 40; width: 220px; padding: 10px 12px; background: var(--surface); border: 0.5px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--popover-shadow); font-size: var(--font-caption); color: var(--text); visibility: hidden; opacity: 0; transition: opacity .12s; pointer-events: none; }
/* 触屏点击会聚焦按钮，Esc 失焦关闭。 */
.token-usage:hover .token-popover, .token-ring:focus + .token-popover { visibility: visible; opacity: 1; }
.token-heading { display: flex; justify-content: space-between; align-items: baseline; color: var(--muted); }
.token-heading strong { color: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }
.token-heading.section { margin-top: 10px; padding-top: 8px; border-top: 0.5px solid var(--border); }
.token-context { margin: 4px 0 0; font-variant-numeric: tabular-nums; }
dl { display: grid; grid-template-columns: auto 1fr; gap: 5px 12px; margin: 6px 0 0; }
dt { color: var(--muted); }
dd { margin: 0; text-align: right; font-variant-numeric: tabular-nums; }
small { color: var(--muted); }
.token-empty { margin: 6px 0 0; color: var(--muted); }
</style>

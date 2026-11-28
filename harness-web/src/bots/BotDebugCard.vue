<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import {
  botApi,
  botTierLabel,
  type BotCard,
  type BotChange,
  type BotDebugContext,
  type BotDebugTrace,
  type BotUsageRow,
} from './api'

const props = defineProps<{
  card: BotCard
  context: BotDebugContext | null
  traces: BotDebugTrace[]
  changes: BotChange[]
  loading: boolean
}>()

const emit = defineEmits<{
  close: []
  toggleDebug: [enabled: boolean]
  refresh: []
}>()

type DebugTab = 'context' | 'trace' | 'changes' | 'trust'
const tab = ref<DebugTab>('context')
const openGroup = ref<'system' | 'transcript' | 'tools' | ''>('system')
const openSectionIdx = ref<number | null>(null)
const showTranscriptMsgs = ref(false)
const openToolName = ref('')
const copiedPrompt = ref(false)
const pickedTraceId = ref('')
const openStepKey = ref('')
const openChangeId = ref('')
const botUsageRow = ref<BotUsageRow | null>(null)

async function loadBotUsage() {
  try {
    const report = await botApi.usage(7)
    botUsageRow.value = report.bots.find(b => b.botId === props.card.bot.id) || null
  } catch {
    botUsageRow.value = null
  }
}

watch(() => props.card.bot.id, () => {
  openSectionIdx.value = null
  showTranscriptMsgs.value = false
  openToolName.value = ''
  pickedTraceId.value = ''
  openStepKey.value = ''
  openChangeId.value = ''
  botUsageRow.value = null
  if (tab.value === 'trust') void loadBotUsage()
})

watch(tab, next => {
  if (next === 'trust') void loadBotUsage()
})

function formatTokens(n: number | null | undefined): string {
  if (!n || n <= 0) return '0'
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 10_000 ? 0 : 1)}k`
  return String(n)
}

function formatMs(startIso: string, endIso: string | null): string {
  if (!endIso) return '进行中'
  const ms = Math.max(0, new Date(endIso).getTime() - new Date(startIso).getTime())
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function formatOffset(runStart: string, stepStart: string): string {
  const ms = Math.max(0, new Date(stepStart).getTime() - new Date(runStart).getTime())
  if (ms < 1000) return `+${ms}ms`
  return `+${(ms / 1000).toFixed(1)}s`
}

function formatShortTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const contextRatio = computed(() => {
  if (!props.context || props.context.windowTokens <= 0) return 0
  const total = props.context.measured?.inputTokens || props.context.totalEstimatedTokens
  return Math.min(100, Math.round((total / props.context.windowTokens) * 100))
})

const systemPct = computed(() => {
  if (!props.context || props.context.windowTokens <= 0) return 0
  return Math.min(100, (props.context.systemTokens / props.context.windowTokens) * 100)
})
const transcriptPct = computed(() => {
  if (!props.context || props.context.windowTokens <= 0) return 0
  return Math.min(100, (props.context.transcriptTokens / props.context.windowTokens) * 100)
})
const toolsPct = computed(() => {
  if (!props.context || props.context.windowTokens <= 0) return 0
  return Math.min(100, (props.context.toolsTokens / props.context.windowTokens) * 100)
})

const cachedPct = computed(() => {
  const m = props.context?.measured
  if (!m || m.inputTokens <= 0) return 0
  return Math.round((m.cachedTokens / m.inputTokens) * 100)
})

const activeTrace = computed(() => {
  if (!props.traces.length) return null
  return props.traces.find(t => t.id === pickedTraceId.value) || props.traces[0]
})

const biggestTokenJump = computed(() => {
  const trace = activeTrace.value
  if (!trace || trace.steps.length < 2) return null
  let best: { step: number; delta: number; outputChars: number } | null = null
  for (let i = 1; i < trace.steps.length; i++) {
    const prev = trace.steps[i - 1].inputTokens ?? 0
    const curr = trace.steps[i].inputTokens ?? 0
    const delta = curr - prev
    if (delta > 3000 && (!best || delta > best.delta)) {
      const prevChars = trace.steps[i - 1].calls.reduce((sum, c) => sum + c.outputChars, 0)
      best = { step: trace.steps[i].step, delta, outputChars: prevChars }
    }
  }
  return best
})

const selectedTool = computed(() =>
  props.context?.tools.find(t => t.name === openToolName.value) || null,
)

const changeKindLabel = (kind: string) => {
  switch (kind) {
    case 'FACT_ADDED': return '新增记忆'
    case 'FACT_REMOVED': return '遗忘记忆'
    case 'NAME_UPDATED': return '修改名称'
    case 'PROMPT_UPDATED': return '改人设'
    case 'PLAYBOOK_SAVED': return '保存做法'
    case 'ROUTINE_SAVED': return '例行变更'
    default: return kind
  }
}

const entryKindLabel = (kind: string) => {
  switch (kind) {
    case 'USER': return '用户'
    case 'ASSISTANT': return 'Bot'
    case 'TASK_RESULT': return '任务回报'
    case 'SYSTEM': return '系统'
    default: return kind
  }
}

async function copyFullPrompt() {
  if (!props.context) return
  const full = props.context.sections
    .map((s, idx) => (idx > 0 && s.heading ? `### ${s.heading}\n${s.text}` : s.text))
    .join('\n\n')
  try {
    await navigator.clipboard.writeText(full)
    copiedPrompt.value = true
    window.setTimeout(() => { copiedPrompt.value = false }, 1600)
  } catch { /* ignore */ }
}

const TIERS = [
  { name: '新手', range: 'Lv.1–9', cls: 'tier-novice' },
  { name: '熟练', range: 'Lv.10–29', cls: 'tier-adept' },
  { name: '资深', range: 'Lv.30–59', cls: 'tier-senior' },
  { name: '专家（工牌扫光）', range: 'Lv.60–98', cls: 'tier-expert' },
  { name: '满级大师（彩虹流光）', range: 'Lv.99', cls: 'tier-master' },
]
</script>

<template>
  <div class="island-debug-card" role="region" aria-label="灵动岛调试面板">
    <div class="island-popover-head">
      <div class="island-popover-bot">
        <BotLookAvatar
          :shape="card.bot.shape"
          :color="card.bot.color"
          :avatar-url="card.avatarUrl"
          :outfit-json="card.bot.outfitJson"
          :size="30"
          :progress="card.experience.progress"
          show-ring
        />
        <div class="island-debug-title">
          <div class="island-debug-name-row">
            <strong>{{ card.bot.name }}</strong>
            <span class="island-level" :class="`tier-${card.experience.tier.toLowerCase()}`">Lv.{{ card.experience.level }}</span>
          </div>
          <span v-if="context" class="island-debug-model">
            {{ context.model }} · 窗口 {{ formatTokens(context.windowTokens) }}
          </span>
        </div>
      </div>
      <div class="island-debug-head-actions">
        <button type="button" class="island-debug-pill is-on" title="关闭调试模式" @click="emit('toggleDebug', false)">
          Debug 开
        </button>
        <button type="button" class="icon-button" title="刷新调试数据" @click="emit('refresh')">
          <UiIcon name="refresh" :size="13" />
        </button>
        <button type="button" class="icon-button" aria-label="收起" @click="emit('close')">
          <UiIcon name="close" :size="14" />
        </button>
      </div>
    </div>

    <div class="island-debug-tabs" role="tablist" aria-label="调试视图">
      <button type="button" role="tab" :aria-selected="tab === 'context'" @click="tab = 'context'">上下文</button>
      <button type="button" role="tab" :aria-selected="tab === 'trace'" @click="tab = 'trace'">
        追踪<span v-if="traces.length" class="island-tab-count">{{ traces.length }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'changes'" @click="tab = 'changes'">
        变更<span v-if="changes.length" class="island-tab-count">{{ changes.length }}</span>
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'trust'" @click="tab = 'trust'">成长</button>
    </div>

    <div class="island-debug-body" role="tabpanel">
      <p v-if="loading && !context && tab === 'context'" class="island-debug-empty">正在读取上下文…</p>

      <!-- Context Tab -->
      <template v-else-if="tab === 'context'">
        <div v-if="!context" class="island-debug-empty">暂无上下文数据</div>
        <template v-else>
          <div class="dbg-token-head">
            <span>发给模型的上下文</span>
            <span class="dbg-mono">
              <strong>{{ formatTokens(context.measured?.inputTokens || context.totalEstimatedTokens) }}</strong>
              / {{ formatTokens(context.windowTokens) }} · {{ contextRatio }}%
            </span>
          </div>
          <div class="dbg-bar">
            <span class="dbg-seg seg-system" :style="{ width: `${systemPct}%` }" title="系统提示词"></span>
            <span class="dbg-seg seg-transcript" :style="{ width: `${transcriptPct}%` }" title="对话记录"></span>
            <span class="dbg-seg seg-tools" :style="{ width: `${toolsPct}%` }" title="工具定义"></span>
          </div>
          <p class="dbg-hint">
            <template v-if="context.measured">
              最近实测输入 {{ context.measured.inputTokens.toLocaleString() }} tokens（缓存命中 {{ cachedPct }}%，输出 {{ context.measured.outputTokens.toLocaleString() }}）
            </template>
            <template v-else>
              当前为本地估算（完成一次回复后显示模型实测 Token 与缓存命中率）
            </template>
          </p>

          <!-- System prompt sections -->
          <div class="dbg-group">
            <button type="button" class="dbg-group-head" @click="openGroup = openGroup === 'system' ? '' : 'system'">
              <span class="dbg-dot seg-system"></span>
              <span>系统提示词</span>
              <small>{{ context.sections.length }} 段</small>
              <span class="dbg-mono dbg-right">≈{{ formatTokens(context.systemTokens) }}</span>
            </button>
            <div v-if="openGroup === 'system'" class="dbg-group-body">
              <div v-for="(sec, idx) in context.sections" :key="sec.heading || idx" class="dbg-section-item">
                <button
                  type="button"
                  class="dbg-section-btn"
                  @click="openSectionIdx = openSectionIdx === idx ? null : idx"
                >
                  <span>{{ sec.heading || '开场' }}</span>
                  <span class="dbg-mono">{{ formatTokens(sec.estimatedTokens) }}</span>
                </button>
                <pre v-if="openSectionIdx === idx" class="dbg-code">{{ sec.text }}</pre>
              </div>
            </div>
          </div>

          <!-- Transcript -->
          <div class="dbg-group">
            <button type="button" class="dbg-group-head" @click="openGroup = openGroup === 'transcript' ? '' : 'transcript'">
              <span class="dbg-dot seg-transcript"></span>
              <span>对话记录</span>
              <small>{{ context.transcriptToSeq ? `#${context.transcriptFromSeq}–#${context.transcriptToSeq}` : '暂无' }}</small>
              <span class="dbg-mono dbg-right">≈{{ formatTokens(context.transcriptTokens) }}</span>
            </button>
            <div v-if="openGroup === 'transcript'" class="dbg-group-body">
              <div class="dbg-chips">
                <span v-for="(count, kind) in context.transcriptCounts" :key="kind" class="dbg-chip">
                  {{ entryKindLabel(kind) }} {{ count }}
                </span>
              </div>
              <p class="dbg-hint">超过 {{ context.compactAbove }} 条自动滚动摘要，保留最近 {{ context.keepRecent }} 条原文</p>
              <button
                v-if="context.transcriptMessages.length"
                type="button"
                class="dbg-sub-btn"
                @click="showTranscriptMsgs = !showTranscriptMsgs"
              >
                {{ showTranscriptMsgs ? '收起原文' : `查看最近 ${context.transcriptMessages.length} 条记录` }}
              </button>
              <div v-if="showTranscriptMsgs" class="dbg-transcript-list">
                <div v-for="m in context.transcriptMessages" :key="m.seq" class="dbg-transcript-row">
                  <span class="dbg-mono">#{{ m.seq }} {{ entryKindLabel(m.kind) }}</span>
                  <p>{{ m.text }}</p>
                </div>
              </div>
            </div>
          </div>

          <!-- Tools -->
          <div class="dbg-group">
            <button type="button" class="dbg-group-head" @click="openGroup = openGroup === 'tools' ? '' : 'tools'">
              <span class="dbg-dot seg-tools"></span>
              <span>聊天工具定义</span>
              <small>{{ context.tools.length }} 个</small>
              <span class="dbg-mono dbg-right">≈{{ formatTokens(context.toolsTokens) }}</span>
            </button>
            <div v-if="openGroup === 'tools'" class="dbg-group-body">
              <div class="dbg-chips">
                <button
                  v-for="t in context.tools"
                  :key="t.name"
                  type="button"
                  class="dbg-chip is-btn"
                  :class="{ 'is-active': openToolName === t.name }"
                  @click="openToolName = openToolName === t.name ? '' : t.name"
                >
                  {{ t.name }}
                </button>
              </div>
              <p v-if="selectedTool" class="dbg-tool-desc">{{ selectedTool.description || '无说明' }}</p>
            </div>
          </div>

          <div class="dbg-copy-row">
            <button type="button" class="dbg-copy-btn" @click="copyFullPrompt">
              <UiIcon :name="copiedPrompt ? 'check' : 'copy'" :size="13" />
              {{ copiedPrompt ? '已复制完整提示词' : '复制系统提示词' }}
            </button>
            <span class="dbg-hint">便于排查人设与记忆效果</span>
          </div>
        </template>
      </template>

      <!-- Trace Tab -->
      <template v-else-if="tab === 'trace'">
        <div v-if="!traces.length" class="island-debug-empty">还没有运行追踪记录</div>
        <template v-else-if="activeTrace">
          <div v-if="traces.length > 1" class="dbg-run-pills">
            <button
              v-for="tr in traces"
              :key="tr.id"
              type="button"
              class="dbg-run-pill"
              :class="{ 'is-active': tr.id === activeTrace.id }"
              @click="pickedTraceId = tr.id; openStepKey = ''"
            >
              <span v-if="!tr.endedAt" class="dbg-live-dot"></span>
              <span>{{ tr.kind === 'CHAT' ? '聊天' : tr.title }}</span>
            </button>
          </div>
          <div class="dbg-trace-meta">
            <span>{{ activeTrace.model || '默认模型' }} · {{ formatShortTime(activeTrace.startedAt) }}</span>
            <span class="dbg-mono">{{ activeTrace.steps.length }} 步 · {{ formatMs(activeTrace.startedAt, activeTrace.endedAt) }}</span>
          </div>
          <ol class="dbg-trace-list">
            <li v-for="st in activeTrace.steps" :key="st.step" class="dbg-step-item">
              <button
                type="button"
                class="dbg-step-row"
                @click="openStepKey = openStepKey === `${activeTrace.id}-${st.step}` ? '' : `${activeTrace.id}-${st.step}`"
              >
                <span class="dbg-mono dbg-step-offset">{{ formatOffset(activeTrace.startedAt, st.startedAt) }}</span>
                <span class="dbg-step-dot" :class="{ 'is-live': !st.endedAt, 'is-err': st.calls.some(c => c.error) }"></span>
                <span class="dbg-step-main">
                  <strong>第 {{ st.step }} 步 · {{ st.calls.length ? st.calls.map(c => c.name).join(', ') : '生成回复' }}</strong>
                  <small v-if="st.inputTokens !== null" class="dbg-mono">
                    入 {{ formatTokens(st.inputTokens) }} / 出 {{ formatTokens(st.outputTokens) }} · {{ formatMs(st.startedAt, st.endedAt) }}
                  </small>
                </span>
              </button>
              <div v-if="openStepKey === `${activeTrace.id}-${st.step}`" class="dbg-step-detail">
                <div v-for="call in st.calls" :key="call.id" class="dbg-call-box">
                  <div class="dbg-call-head">
                    <span class="dbg-mono">{{ call.name }}</span>
                    <span class="dbg-call-status" :class="{ 'is-err': call.error }">
                      {{ call.error ? '失败' : `${call.outputChars} 字符` }}
                    </span>
                  </div>
                  <pre class="dbg-code">{{ call.arguments || '{}' }}</pre>
                  <pre class="dbg-code">{{ call.output || '运行中…' }}</pre>
                </div>
                <template v-if="st.reasoning">
                  <span class="dbg-label">推理摘要</span>
                  <pre class="dbg-code">{{ st.reasoning }}</pre>
                </template>
                <template v-if="st.text">
                  <span class="dbg-label">回复内容</span>
                  <pre class="dbg-code">{{ st.text }}</pre>
                </template>
              </div>
            </li>
          </ol>
          <p v-if="biggestTokenJump" class="dbg-jump-warn">
            第 {{ biggestTokenJump.step }} 步输入激增 +{{ formatTokens(biggestTokenJump.delta) }} tokens（上一步工具返回 {{ biggestTokenJump.outputChars.toLocaleString() }} 字符）
          </p>
          <div class="dbg-trace-foot">
            <span class="dbg-status-pill" :class="`st-${activeTrace.status.toLowerCase()}`">{{ activeTrace.status }}</span>
            <span v-if="activeTrace.totalInputTokens > 0" class="dbg-mono">
              总计 入 {{ formatTokens(activeTrace.totalInputTokens) }} · 缓 {{ formatTokens(activeTrace.totalCachedTokens) }} · 出 {{ formatTokens(activeTrace.totalOutputTokens) }}
            </span>
            <p v-if="activeTrace.error" class="dbg-err-line">{{ activeTrace.error }}</p>
          </div>
        </template>
      </template>

      <!-- Changes Tab -->
      <template v-else-if="tab === 'changes'">
        <div v-if="!changes.length" class="island-debug-empty">还没有自我修改或记忆变更记录</div>
        <ul v-else class="dbg-change-list">
          <li v-for="ch in changes" :key="ch.id" class="dbg-change-item">
            <button
              type="button"
              class="dbg-change-row"
              :disabled="!ch.beforeText && !ch.afterText"
              @click="openChangeId = openChangeId === ch.id ? '' : ch.id"
            >
              <span class="dbg-mono dbg-change-time">{{ formatShortTime(ch.createdAt) }}</span>
              <span class="dbg-change-badge">{{ changeKindLabel(ch.kind) }}</span>
              <span class="dbg-change-main">
                <span class="dbg-change-summary">{{ ch.summary }}</span>
                <small class="dbg-mono">{{ ch.toolName }} · #{{ ch.entrySeq }}</small>
              </span>
            </button>
            <div v-if="openChangeId === ch.id && (ch.beforeText || ch.afterText)" class="dbg-diff-box">
              <p v-if="ch.beforeText" class="dbg-diff-before">{{ ch.beforeText }}</p>
              <p v-if="ch.afterText" class="dbg-diff-after">{{ ch.afterText }}</p>
            </div>
          </li>
        </ul>
      </template>

      <!-- Trust / XP & Usage Tab -->
      <template v-else>
        <div class="dbg-trust-card">
          <div class="dbg-trust-head">
            <strong>Lv.{{ card.experience.level }} · {{ botTierLabel(card.experience.tier) }}</strong>
            <span class="dbg-mono">{{ card.experience.xp }} XP</span>
          </div>
          <div class="island-xp-bar"><span :style="{ width: `${Math.round(card.experience.progress * 100)}%` }"></span></div>
          <p class="island-xp-rules">对话 +10 XP · 普通任务 +15 XP · 按做法完成 +30 XP · 沉淀做法 +50 XP</p>
          <div v-for="t in TIERS" :key="t.name" class="island-tier-row">
            <span class="island-tier-dot" :class="t.cls"></span>
            <span>{{ t.name }}</span>
            <small>{{ t.range }}</small>
          </div>
        </div>
        <div class="dbg-usage-box">
          <div class="dbg-trust-head">
            <strong>近 7 天 Token 消耗</strong>
            <span class="dbg-mono">
              {{ formatTokens(botUsageRow ? botUsageRow.totals.inputTokens + botUsageRow.totals.outputTokens : 0) }}
              · {{ botUsageRow?.totals.calls || 0 }} 次
            </span>
          </div>
          <div class="dbg-usage-grid">
            <div>
              <small>聊天</small>
              <strong>{{ formatTokens(botUsageRow ? botUsageRow.chat.inputTokens + botUsageRow.chat.outputTokens : 0) }}</strong>
            </div>
            <div>
              <small>后台任务</small>
              <strong>{{ formatTokens(botUsageRow ? botUsageRow.task.inputTokens + botUsageRow.task.outputTokens : 0) }}</strong>
            </div>
            <div>
              <small>例行任务</small>
              <strong>{{ formatTokens(botUsageRow ? botUsageRow.routine.inputTokens + botUsageRow.routine.outputTokens : 0) }}</strong>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

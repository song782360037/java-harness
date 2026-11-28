<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotDebugCard from './BotDebugCard.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import LevelUpCard from './LevelUpCard.vue'
import {
  botApi,
  botTierLabel,
  type BotCard,
  type BotChange,
  type BotDebugContext,
  type BotDebugTrace,
  type BotTask,
  type IslandNotice,
} from './api'

const DEBUG_STORAGE_KEY = 'harness.bots.debugMode'

const props = defineProps<{
  card: BotCard | null
  allBots?: BotCard[]
  notice: IslandNotice | null
  recentNotices: IslandNotice[]
  version?: string
}>()

const emit = defineEmits<{
  openTask: [task: BotTask]
  cancelTask: [taskId: string]
  selectBot: [botId: string]
  dismissNotice: []
}>()

const wrapRef = ref<HTMLElement>()
const expanded = ref(false)
const showLevelGuide = ref(false)
const debugMode = ref(typeof window !== 'undefined' && window.localStorage.getItem(DEBUG_STORAGE_KEY) === '1')
const debugContext = ref<BotDebugContext | null>(null)
const debugTraces = ref<BotDebugTrace[]>([])
const debugChanges = ref<BotChange[]>([])
const debugLoading = ref(false)

const activeTask = computed(() => props.card?.activeTasks[0] || null)
const crossBotReply = computed(() => {
  const n = props.notice
  if (!n || !props.card) return null
  if (n.kind !== 'BOT_REPLIED' || !n.botId || n.botId === props.card.bot.id) return null
  const sender = props.allBots?.find(b => b.bot.id === n.botId) || null
  return { notice: n, sender }
})

const contextGaugePct = computed(() => {
  const ctx = debugContext.value
  if (!ctx || ctx.windowTokens <= 0) return 0
  const used = ctx.measured?.inputTokens || ctx.totalEstimatedTokens
  return Math.min(100, Math.round((used / ctx.windowTokens) * 100))
})

const contextGaugeLabel = computed(() => {
  const ctx = debugContext.value
  if (!ctx || ctx.windowTokens <= 0) return ''
  const used = ctx.measured?.inputTokens || ctx.totalEstimatedTokens
  const fmt = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(n >= 10_000 ? 0 : 1)}k` : String(n))
  return `${fmt(used)}/${fmt(ctx.windowTokens)}`
})

async function loadDebugData() {
  const botId = props.card?.bot.id
  if (!botId || !debugMode.value) return
  debugLoading.value = true
  try {
    const [ctx, traces, changes] = await Promise.all([
      botApi.debugContext(botId),
      botApi.debugTraces(botId),
      botApi.debugChanges(botId),
    ])
    if (props.card?.bot.id === botId) {
      debugContext.value = ctx
      debugTraces.value = traces
      debugChanges.value = changes
    }
  } catch { /* ignore transient error */ }
  finally {
    debugLoading.value = false
  }
}

function setDebugMode(enabled: boolean) {
  debugMode.value = enabled
  try { window.localStorage.setItem(DEBUG_STORAGE_KEY, enabled ? '1' : '0') } catch { /* ignore */ }
  if (enabled) {
    void loadDebugData()
  }
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && expanded.value) {
    event.stopPropagation()
    expanded.value = false
  }
}

function onGlobalPointerDown(event: PointerEvent) {
  if (!expanded.value) return
  if (wrapRef.value && !wrapRef.value.contains(event.target as Node)) {
    expanded.value = false
  }
}

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('pointerdown', onGlobalPointerDown)
  if (debugMode.value && props.card?.bot.id) {
    void loadDebugData()
  }
})

onUnmounted(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('pointerdown', onGlobalPointerDown)
})

const TIERS = [
  { name: '新手', range: 'Lv.1–9', cls: 'tier-novice' },
  { name: '熟练', range: 'Lv.10–29', cls: 'tier-adept' },
  { name: '资深', range: 'Lv.30–59', cls: 'tier-senior' },
  { name: '专家（工牌扫光）', range: 'Lv.60–98', cls: 'tier-expert' },
  { name: '满级大师（彩虹流光）', range: 'Lv.99', cls: 'tier-master' },
]

watch(() => props.card?.bot.id, () => {
  expanded.value = false
  showLevelGuide.value = false
  debugContext.value = null
  debugTraces.value = []
  debugChanges.value = []
  if (debugMode.value && props.card?.bot.id) {
    void loadDebugData()
  }
})

watch([expanded, () => props.version], ([isOpen]) => {
  if (debugMode.value && props.card?.bot.id && (isOpen || !debugContext.value)) {
    void loadDebugData()
  }
})

const levelUpInfo = computed(() => {
  const n = props.notice
  if (!n || n.kind !== 'LEVEL_UP') return null
  const target = props.allBots?.find(b => b.bot.id === n.botId) || props.card
  if (!target) return null
  const match = /Lv\.?\s*(\d+)/i.exec(`${n.title} ${n.detail}`)
  const lv = match ? Number(match[1]) : target.experience.level
  return { card: target, level: Math.max(2, lv) }
})

const mode = computed<'levelup' | 'reply' | 'notice' | 'task' | 'idle'>(() => {
  if (levelUpInfo.value) return 'levelup'
  if (crossBotReply.value) return 'reply'
  if (props.notice) return 'notice'
  if (activeTask.value) return 'task'
  return 'idle'
})
</script>

<template>
  <div ref="wrapRef" class="island-wrap">
    <!-- Level-Up Celebration Card -->
    <div v-if="mode === 'levelup' && levelUpInfo && !expanded" class="island-popover is-levelup">
      <LevelUpCard :card="levelUpInfo.card" :new-level="levelUpInfo.level" @dismiss="emit('dismissNotice')" />
    </div>

    <!-- Cross-bot reply quick jump bar -->
    <div v-else-if="mode === 'reply' && crossBotReply && !expanded" class="dynamic-island is-reply" role="status">
      <BotLookAvatar
        v-if="crossBotReply.sender"
        :shape="crossBotReply.sender.bot.shape"
        :color="crossBotReply.sender.bot.color"
        :avatar-url="crossBotReply.sender.avatarUrl"
        :outfit-json="crossBotReply.sender.bot.outfitJson"
        :size="20"
      />
      <span class="island-title">{{ crossBotReply.notice.botName }}</span>
      <span class="island-sub">{{ crossBotReply.notice.detail }}</span>
      <button
        type="button"
        class="island-reply-open"
        @click="emit('selectBot', crossBotReply.notice.botId); emit('dismissNotice')"
      >
        查看
      </button>
      <button type="button" class="island-reply-dismiss" aria-label="稍后查看" @click="emit('dismissNotice')">
        <UiIcon name="close" :size="11" />
      </button>
    </div>

    <button
      v-else
      type="button"
      class="dynamic-island"
      :class="[`is-${mode}`, { expanded, 'is-failed': notice?.kind === 'TASK_FAILED', 'is-debug': debugMode }]"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <template v-if="mode === 'notice' && notice">
        <span class="island-pulse" :class="notice.kind === 'TASK_FAILED' ? 'is-danger' : 'is-success'"></span>
        <span class="island-title">{{ notice.title }}</span>
        <span class="island-sub">{{ notice.detail }}</span>
      </template>
      <template v-else-if="mode === 'task' && activeTask && card">
        <span class="island-spinner" aria-hidden="true"></span>
        <span class="island-title">{{ activeTask.statusText || activeTask.title }}</span>
        <span class="island-badge">后台任务</span>
      </template>
      <template v-else-if="card">
        <span class="island-pulse is-idle" aria-hidden="true"></span>
        <span class="island-idle-text">{{ card.status === 'QUEUED' ? `${card.queuedCount} 条排队` : card.status === 'REPLYING' ? '回复中…' : '待命中' }}</span>
        <span class="island-level" :class="`tier-${card.experience.tier.toLowerCase()}`">Lv.{{ card.experience.level }}</span>
      </template>

      <template v-if="debugMode && debugContext">
        <span class="island-sep" aria-hidden="true"></span>
        <span class="island-gauge" :title="`上下文占用 ${contextGaugePct}% (${contextGaugeLabel})`">
          <span class="island-gauge-track">
            <span class="island-gauge-fill" :class="{ 'is-warn': contextGaugePct >= 75 }" :style="{ width: `${contextGaugePct}%` }"></span>
          </span>
          <span class="island-gauge-text">{{ contextGaugeLabel }}</span>
        </span>
      </template>
    </button>

    <!-- Debug Mode Expanded Popover -->
    <div v-if="expanded && card && debugMode" class="island-popover is-debug">
      <BotDebugCard
        :card="card"
        :context="debugContext"
        :traces="debugTraces"
        :changes="debugChanges"
        :loading="debugLoading"
        @close="expanded = false"
        @toggle-debug="setDebugMode"
        @refresh="loadDebugData"
      />
    </div>

    <!-- Normal Mode Expanded Popover -->
    <div v-else-if="expanded && card" class="island-popover" role="region" aria-label="灵动岛详情">
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
          <div>
            <strong>{{ card.bot.name }}</strong>
            <button type="button" class="island-level-toggle" @click="showLevelGuide = !showLevelGuide">
              Lv.{{ card.experience.level }} · {{ botTierLabel(card.experience.tier) }} · {{ card.experience.xp }} XP {{ showLevelGuide ? '▴' : '▾' }}
            </button>
          </div>
        </div>
        <div class="island-debug-head-actions">
          <button type="button" class="island-debug-pill" title="开启调试模式（查看上下文/追踪/变更）" @click="setDebugMode(true)">
            Debug
          </button>
          <button type="button" class="icon-button" aria-label="收起灵动岛" @click="expanded = false"><UiIcon name="close" :size="14" /></button>
        </div>
      </div>

      <div class="island-xp-bar"><span :style="{ width: `${Math.round(card.experience.progress * 100)}%` }"></span></div>

      <section v-if="showLevelGuide" class="island-section island-level-guide">
        <header>成长与段位规则</header>
        <p class="island-xp-rules">对话 +10 XP · 普通任务 +15 XP · 按做法完成任务 +30 XP · 沉淀新做法 +50 XP</p>
        <div v-for="t in TIERS" :key="t.name" class="island-tier-row">
          <span class="island-tier-dot" :class="t.cls"></span>
          <span>{{ t.name }}</span>
          <small>{{ t.range }}</small>
        </div>
      </section>

      <section v-if="card.activeTasks.length" class="island-section">
        <header>运行中的后台任务（{{ card.activeTasks.length }}）</header>
        <div v-for="task in card.activeTasks" :key="task.id" class="island-task-row">
          <button type="button" class="island-task-open" @click="expanded = false; emit('openTask', task)">
            <span class="island-spinner small" aria-hidden="true"></span>
            <span>{{ task.statusText ? `${task.title} · ${task.statusText}` : task.title }}</span>
          </button>
          <button type="button" class="island-task-stop" title="取消任务" @click="emit('cancelTask', task.id)">停止</button>
        </div>
      </section>

      <section v-if="recentNotices.length" class="island-section">
        <header>最近动态</header>
        <button
          v-for="item in recentNotices.slice(-5).reverse()"
          :key="item.id"
          type="button"
          class="island-notice-row"
          @click="if (item.botId) { expanded = false; emit('selectBot', item.botId) }"
        >
          <strong>{{ item.title }}</strong>
          <small>{{ item.detail }}</small>
        </button>
      </section>
    </div>
  </div>
</template>

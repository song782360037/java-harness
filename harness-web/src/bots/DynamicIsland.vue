<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import { botTierLabel, type BotCard, type BotTask, type IslandNotice } from './api'

const props = defineProps<{
  card: BotCard | null
  notice: IslandNotice | null
  recentNotices: IslandNotice[]
}>()

const emit = defineEmits<{
  openTask: [task: BotTask]
  cancelTask: [taskId: string]
  selectBot: [botId: string]
}>()

const wrapRef = ref<HTMLElement>()
const expanded = ref(false)
const showLevelGuide = ref(false)
const activeTask = computed(() => props.card?.activeTasks[0] || null)

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
})

const mode = computed<'notice' | 'task' | 'idle'>(() => {
  if (props.notice) return 'notice'
  if (activeTask.value) return 'task'
  return 'idle'
})
</script>

<template>
  <div ref="wrapRef" class="island-wrap">
    <button
      type="button"
      class="dynamic-island"
      :class="[`is-${mode}`, { expanded, 'is-failed': notice?.kind === 'TASK_FAILED' }]"
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
        <span class="island-title">{{ activeTask.title }}</span>
        <span class="island-badge">后台任务</span>
      </template>
      <template v-else-if="card">
        <span class="island-pulse is-idle" aria-hidden="true"></span>
        <span class="island-idle-text">{{ card.status === 'QUEUED' ? `${card.queuedCount} 条排队` : '待命中' }}</span>
        <span class="island-level" :class="`tier-${card.experience.tier.toLowerCase()}`">Lv.{{ card.experience.level }}</span>
      </template>
    </button>

    <div v-if="expanded && card" class="island-popover" role="region" aria-label="灵动岛详情">
      <div class="island-popover-head">
        <div class="island-popover-bot">
          <BotLookAvatar :shape="card.bot.shape" :color="card.bot.color" :size="30" :progress="card.experience.progress" show-ring />
          <div>
            <strong>{{ card.bot.name }}</strong>
            <button type="button" class="island-level-toggle" @click="showLevelGuide = !showLevelGuide">
              Lv.{{ card.experience.level }} · {{ botTierLabel(card.experience.tier) }} · {{ card.experience.xp }} XP {{ showLevelGuide ? '▴' : '▾' }}
            </button>
          </div>
        </div>
        <button type="button" class="icon-button" aria-label="收起灵动岛" @click="expanded = false"><UiIcon name="close" :size="14" /></button>
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
            <span>{{ task.title }}</span>
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

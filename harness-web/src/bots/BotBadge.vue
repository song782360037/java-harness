<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BotDesk from './BotDesk.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import { botTierLabel, type BotCard } from './api'

const props = defineProps<{
  card: BotCard
  turnCount: number
  playbookCount: number
  routineCount: number
  fileCount: number
  appCount: number
}>()

const emit = defineEmits<{
  jumpTab: [tab: 'activity' | 'playbooks' | 'apps' | 'files']
}>()

const flipped = ref(false)

// 切换 Bot 时始终从工牌正面开始展示
watch(() => props.card.bot.id, () => {
  flipped.value = false
})

const daysOnboard = computed(() => {
  const diff = Date.now() - new Date(props.card.bot.createdAt).getTime()
  return Math.max(1, Math.floor(diff / 86_400_000) + 1)
})

const badgeCode = computed(() => props.card.bot.main ? 'MAIN-01' : props.card.bot.id.slice(-4).toUpperCase())
</script>

<template>
  <div class="bot-badge-stage">
    <div class="bot-badge-lanyard" aria-hidden="true"><span></span></div>
    <div class="bot-badge-flipper" :class="{ flipped }">
      <!-- Front: Employee Badge -->
      <div class="bot-badge-face is-front" :class="`tier-${card.experience.tier.toLowerCase()}`">
        <div class="bot-badge-banner">
          <span class="bot-badge-tier">Lv.{{ card.experience.level }} · {{ botTierLabel(card.experience.tier) }}</span>
          <span class="bot-badge-no">#{{ badgeCode }}</span>
        </div>
        <div class="bot-badge-body">
          <BotLookAvatar
            :shape="card.bot.shape"
            :color="card.bot.color"
            :size="46"
            :status="card.status"
            :progress="card.experience.progress"
            show-ring
          />
          <div class="bot-badge-ident">
            <strong>{{ card.bot.name }}</strong>
            <small>{{ card.bot.roleSummary || '常驻数字员工' }}</small>
          </div>
        </div>
        <div class="bot-badge-metrics">
          <div><strong>第 {{ daysOnboard }} 天</strong><small>入职</small></div>
          <div><strong>{{ turnCount }}</strong><small>对话</small></div>
          <div><strong>{{ playbookCount }}</strong><small>做法</small></div>
          <div><strong>{{ card.experience.xp }}</strong><small>经验</small></div>
        </div>
        <button type="button" class="bot-barcode-strip" title="刷卡翻面查看工位" @click="flipped = true">
          <span class="barcode-bars" aria-hidden="true"></span>
          <span class="barcode-hint">刷卡翻面 · 看工位</span>
        </button>
      </div>

      <!-- Back: Animated Workstation Desk -->
      <div class="bot-badge-face is-back">
        <BotDesk
          :card="card"
          :playbook-count="playbookCount"
          :routine-count="routineCount"
          :file-count="fileCount"
          :app-count="appCount"
          :days-onboard="daysOnboard"
          @jump-tab="emit('jumpTab', $event)"
          @flip-back="flipped = false"
        />
      </div>
    </div>
  </div>
</template>

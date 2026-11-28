<script setup lang="ts">
import BotLookAvatar from './BotLookAvatar.vue'
import { botStatusLabel, botTierLabel, type BotCard } from './api'

defineProps<{
  card: BotCard
  anchor: { left: number; top?: number; bottom?: number }
}>()
</script>

<template>
  <div
    class="bot-status-popover"
    :style="{
      left: `${anchor.left}px`,
      top: anchor.top !== undefined ? `${anchor.top}px` : undefined,
      bottom: anchor.bottom !== undefined ? `${anchor.bottom}px` : undefined,
    }"
    role="tooltip"
  >
    <div class="bot-status-pop-head">
      <BotLookAvatar
        :shape="card.bot.shape"
        :color="card.bot.color"
        :avatar-url="card.avatarUrl"
        :outfit-json="card.bot.outfitJson"
        :size="36"
        :status="card.status"
        :progress="card.experience.progress"
        show-ring
      />
      <div class="bot-status-pop-ident">
        <strong>{{ card.bot.name }}</strong>
        <small>Lv.{{ card.experience.level }} · {{ botTierLabel(card.experience.tier) }} · {{ botStatusLabel(card.status) }}</small>
      </div>
    </div>
    <p v-if="card.bot.roleSummary" class="bot-status-pop-role">{{ card.bot.roleSummary }}</p>
    <div v-if="card.failedReason" class="bot-status-pop-err">{{ card.failedReason }}</div>
    <div v-if="card.activeTasks.length" class="bot-status-pop-tasks">
      <div v-for="t in card.activeTasks.slice(0, 2)" :key="t.id" class="bot-status-pop-task">
        <strong>{{ t.title }}</strong>
        <small>{{ t.statusText || '后台执行中…' }}</small>
      </div>
    </div>
    <div class="bot-status-pop-foot">
      <span>做法 {{ card.playbookCount }}</span>
      <span>例行 {{ card.routineCount }}</span>
      <span v-if="card.pendingApprovalCount > 0" class="is-warn">待审批 {{ card.pendingApprovalCount }}</span>
      <span v-if="card.unreadCount > 0" class="is-accent">未读 {{ card.unreadCount }}</span>
    </div>
  </div>
</template>

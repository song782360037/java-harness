<script setup lang="ts">
import { computed } from 'vue'
import BotLookAvatar from './BotLookAvatar.vue'
import { botTierLabel, unlockedAtLevel, type BotCard } from './api'

const props = defineProps<{
  card: BotCard
  newLevel: number
}>()

const emit = defineEmits<{
  dismiss: []
}>()

const SPARK_COLORS = ['#fbbf24', '#34d399', '#38bdf8', '#fb7185', '#a78bfa', '#f97316']
const SPARKS = Array.from({ length: 16 }, (_, idx) => {
  const angle = (idx / 16) * Math.PI * 2
  const dist = 46 + (idx % 3) * 14
  return {
    tx: `${Math.round(Math.cos(angle) * dist)}px`,
    ty: `${Math.round(Math.sin(angle) * dist)}px`,
    color: SPARK_COLORS[idx % SPARK_COLORS.length],
    delay: `${(idx % 4) * 45}ms`,
  }
})

const newlyUnlocked = computed(() => unlockedAtLevel(props.newLevel, props.card.bot.main))
const prevLevel = computed(() => Math.max(1, props.newLevel - 1))
</script>

<template>
  <div class="island-levelup-card" role="status" @click="emit('dismiss')">
    <span class="levelup-sparks" aria-hidden="true">
      <span
        v-for="(s, idx) in SPARKS"
        :key="idx"
        class="levelup-spark"
        :style="{ '--tx': s.tx, '--ty': s.ty, background: s.color, animationDelay: s.delay }"
      ></span>
    </span>
    <BotLookAvatar
      :shape="card.bot.shape"
      :color="card.bot.color"
      :avatar-url="card.avatarUrl"
      :outfit-json="card.bot.outfitJson"
      :size="54"
      :progress="1"
      show-ring
    />
    <div class="levelup-kicker">LEVEL UP</div>
    <div class="levelup-nums">
      <span class="levelup-old">Lv.{{ prevLevel }}</span>
      <span class="levelup-arrow">→</span>
      <span class="levelup-new">Lv.{{ newLevel }}</span>
    </div>
    <span class="levelup-tier-pill">{{ card.bot.name }} · {{ botTierLabel(card.experience.tier) }}</span>
    <div v-if="newlyUnlocked.length" class="levelup-unlocks">
      <small>解锁新装扮</small>
      <div class="levelup-unlock-row">
        <div v-for="piece in newlyUnlocked" :key="piece.id" class="levelup-unlock-item">
          <span class="levelup-unlock-bubble">
            <BotLookAvatar :shape="card.bot.shape" :color="card.bot.color" :outfit="[piece.id]" :size="32" />
          </span>
          <span>{{ piece.label }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

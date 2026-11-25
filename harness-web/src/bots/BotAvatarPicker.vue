<script setup lang="ts">
import { computed, ref } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import {
  BOT_AVATARS,
  BOT_AVATAR_CATEGORIES,
  BOT_COLORS,
  getBotAvatar,
  type BotAvatarCategory,
  type BotShape,
} from './api'

const props = defineProps<{
  shape: BotShape
  color: string
  compact?: boolean
}>()

const emit = defineEmits<{
  'update:shape': [shape: BotShape]
  'update:color': [color: string]
}>()

const category = ref<'all' | BotAvatarCategory>('all')

const filteredAvatars = computed(() =>
  category.value === 'all'
    ? BOT_AVATARS
    : BOT_AVATARS.filter(item => item.category === category.value),
)

const currentSpec = computed(() => getBotAvatar(props.shape))

function randomize() {
  const nextAvatar = BOT_AVATARS[Math.floor(Math.random() * BOT_AVATARS.length)]
  const nextColor = BOT_COLORS[Math.floor(Math.random() * BOT_COLORS.length)]
  emit('update:shape', nextAvatar.id)
  emit('update:color', nextColor)
}
</script>

<template>
  <div class="bot-avatar-picker" :class="{ 'is-compact': compact }">
    <div class="bot-avatar-preview-bar">
      <BotLookAvatar :shape="shape" :color="color" :size="44" :progress="0.65" show-ring />
      <div class="bot-avatar-preview-copy">
        <strong>{{ currentSpec.name }}</strong>
        <small>{{ currentSpec.en }} · 30 款原创角色 × 12 主题色</small>
      </div>
      <button type="button" class="bot-mini-btn" title="随机搭配形象和配色" @click="randomize">
        <UiIcon name="wand" :size="13" />
        <span>随机</span>
      </button>
    </div>

    <div class="bot-avatar-cats" role="tablist" aria-label="角色分类">
      <button
        type="button"
        role="tab"
        :aria-selected="category === 'all'"
        :class="{ active: category === 'all' }"
        @click="category = 'all'"
      >全部 ({{ BOT_AVATARS.length }})</button>
      <button
        v-for="cat in BOT_AVATAR_CATEGORIES"
        :key="cat.id"
        type="button"
        role="tab"
        :aria-selected="category === cat.id"
        :class="{ active: category === cat.id }"
        @click="category = cat.id"
      >{{ cat.label }}</button>
    </div>

    <div class="bot-shape-grid is-gallery">
      <button
        v-for="spec in filteredAvatars"
        :key="spec.id"
        type="button"
        class="bot-shape-opt"
        :class="{ active: shape === spec.id }"
        :title="`${spec.name} (${spec.en})`"
        @click="emit('update:shape', spec.id)"
      >
        <BotLookAvatar :shape="spec.id" :color="color" :size="30" />
        <span>{{ spec.name }}</span>
      </button>
    </div>

    <div class="bot-color-row">
      <span class="bot-color-label">主题色</span>
      <div class="bot-color-grid">
        <button
          v-for="c in BOT_COLORS"
          :key="c"
          type="button"
          class="bot-color-opt"
          :class="{ active: color === c }"
          :style="{ background: c }"
          :aria-label="`选择颜色 ${c}`"
          @click="emit('update:color', c)"
        ></button>
      </div>
    </div>
  </div>
</template>

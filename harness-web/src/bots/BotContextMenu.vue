<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import type { BotCard, BotSection } from './api'

const props = defineProps<{
  card: BotCard
  sections: BotSection[]
  x: number
  y: number
}>()

const emit = defineEmits<{
  close: []
  togglePin: [card: BotCard]
  markUnread: [botId: string]
  markSeen: [botId: string]
  rename: [botId: string]
  moveToSection: [botId: string, sectionId: string]
  createSectionAndMove: [botId: string, sectionName: string]
  toggleHide: [card: BotCard]
  archive: [botId: string]
}>()

const menuRef = ref<HTMLElement>()
const showMoveSub = ref(false)
const creatingSection = ref(false)
const newSectionName = ref('')
const newSectionInputRef = ref<HTMLInputElement>()
const copiedId = ref(false)
const confirmingArchive = ref(false)

const posStyle = computed(() => {
  const maxX = Math.max(12, window.innerWidth - 252)
  const maxY = Math.max(12, window.innerHeight - 360)
  return {
    left: `${Math.min(props.x, maxX)}px`,
    top: `${Math.min(props.y, maxY)}px`,
  }
})

async function copyBotId() {
  try {
    await navigator.clipboard.writeText(props.card.bot.id)
    copiedId.value = true
    window.setTimeout(() => { copiedId.value = false; emit('close') }, 700)
  } catch { emit('close') }
}

function startNewSection() {
  creatingSection.value = true
  newSectionName.value = ''
  void nextTick(() => newSectionInputRef.value?.focus())
}

function submitNewSection() {
  const name = newSectionName.value.trim()
  if (!name) return
  emit('createSectionAndMove', props.card.bot.id, name)
  creatingSection.value = false
  newSectionName.value = ''
  emit('close')
}

function onGlobalPointerDown(event: PointerEvent) {
  if (menuRef.value && !menuRef.value.contains(event.target as Node)) emit('close')
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  window.addEventListener('pointerdown', onGlobalPointerDown)
  window.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('pointerdown', onGlobalPointerDown)
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <div ref="menuRef" class="bot-ctx-menu" :style="posStyle" role="menu" aria-label="Bot 快捷菜单">
    <div class="bot-ctx-head" :style="{ background: `color-mix(in srgb, ${card.bot.color} 11%, var(--surface))` }">
      <BotLookAvatar
        :shape="card.bot.shape"
        :color="card.bot.color"
        :avatar-url="card.avatarUrl"
        :outfit-json="card.bot.outfitJson"
        :size="30"
      />
      <div class="bot-ctx-head-copy">
        <strong>{{ card.bot.name }}</strong>
        <small>Lv.{{ card.experience.level }} · #{{ card.bot.main ? 'MAIN-01' : card.bot.id.slice(-4).toUpperCase() }}</small>
      </div>
    </div>

    <div class="bot-ctx-tiles">
      <button
        type="button"
        :disabled="card.bot.main || card.bot.hidden"
        :class="{ 'is-active': card.bot.pinned }"
        @click="emit('togglePin', card); emit('close')"
      >
        <UiIcon name="pin" :size="13" />
        <span>{{ card.bot.pinned ? '取消置顶' : '置顶' }}</span>
      </button>
      <button
        type="button"
        :class="{ 'is-active': card.unreadCount > 0 }"
        @click="card.unreadCount > 0 ? emit('markSeen', card.bot.id) : emit('markUnread', card.bot.id); emit('close')"
      >
        <UiIcon name="chat" :size="13" />
        <span>{{ card.unreadCount > 0 ? '标为已读' : '标为未读' }}</span>
      </button>
      <button type="button" @click="emit('rename', card.bot.id); emit('close')">
        <UiIcon name="edit" :size="13" />
        <span>改名</span>
      </button>
      <button type="button" @click="copyBotId">
        <UiIcon :name="copiedId ? 'check' : 'copy'" :size="13" />
        <span>{{ copiedId ? '已复制' : '复制 ID' }}</span>
      </button>
    </div>

    <div class="bot-ctx-list">
      <button type="button" class="bot-ctx-item" @click="showMoveSub = !showMoveSub">
        <UiIcon name="folder" :size="13" />
        <span>移动到分组</span>
        <UiIcon :name="showMoveSub ? 'down' : 'chevron'" :size="11" class="bot-ctx-arrow" />
      </button>
      <div v-if="showMoveSub" class="bot-ctx-submenu">
        <button
          v-for="sec in sections"
          :key="sec.id"
          type="button"
          class="bot-ctx-sub-item"
          :class="{ 'is-active': card.bot.sectionId === sec.id }"
          @click="emit('moveToSection', card.bot.id, sec.id); emit('close')"
        >
          <span>{{ sec.name }}</span>
          <UiIcon v-if="card.bot.sectionId === sec.id" name="check" :size="11" />
        </button>
        <button
          type="button"
          class="bot-ctx-sub-item"
          :class="{ 'is-active': !card.bot.sectionId }"
          @click="emit('moveToSection', card.bot.id, ''); emit('close')"
        >
          <span>未分组</span>
          <UiIcon v-if="!card.bot.sectionId" name="check" :size="11" />
        </button>
        <form v-if="creatingSection" class="bot-ctx-new-sec" @submit.prevent="submitNewSection">
          <input
            ref="newSectionInputRef"
            v-model="newSectionName"
            maxlength="60"
            placeholder="新分组名称…"
            @keydown.esc.stop="creatingSection = false"
          />
          <button type="submit" class="is-confirm" :disabled="!newSectionName.trim()">确定</button>
          <button type="button" @click="creatingSection = false">取消</button>
        </form>
        <button v-else type="button" class="bot-ctx-sub-item is-new" @click="startNewSection">
          + 新建分组并移入
        </button>
      </div>

      <button
        v-if="!card.bot.main"
        type="button"
        class="bot-ctx-item"
        @click="emit('toggleHide', card); emit('close')"
      >
        <UiIcon name="panel" :size="13" />
        <span>{{ card.bot.hidden ? '恢复显示到列表' : '暂时隐藏' }}</span>
      </button>

      <template v-if="!card.bot.main">
        <div class="bot-ctx-sep"></div>
        <button
          v-if="!confirmingArchive"
          type="button"
          class="bot-ctx-item is-danger"
          @click="confirmingArchive = true"
        >
          <UiIcon name="trash" :size="13" />
          <span>归档移除</span>
        </button>
        <div v-else class="bot-ctx-confirm">
          <span>确认归档「{{ card.bot.name }}」？</span>
          <div>
            <button type="button" @click="confirmingArchive = false">取消</button>
            <button type="button" class="is-danger" @click="emit('archive', card.bot.id); emit('close')">确认</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

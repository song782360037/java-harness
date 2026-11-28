<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import BotAvatarEditor from './BotAvatarEditor.vue'
import BotDesk from './BotDesk.vue'
import { botTierLabel, type BotCard, type BotShape, type OutfitId } from './api'

const props = defineProps<{
  card: BotCard
  turnCount: number
  playbookCount: number
  skillCount?: number
  routineCount: number
  fileCount: number
  appCount: number
}>()

const emit = defineEmits<{
  jumpTab: [tab: 'activity' | 'playbooks' | 'apps' | 'files']
  rename: [name: string]
  updateLook: [shape: BotShape, color: string]
  updateOutfit: [outfit: OutfitId[]]
  uploadAvatar: [blob: Blob, filename: string]
  deleteAvatar: []
}>()

const flipped = ref(false)
const scanning = ref(false)
const leanDeg = ref(0)
const editorOpen = ref(false)
const editingName = ref(false)
const nameDraft = ref('')
const nameInputRef = ref<HTMLInputElement>()
let scanTimer: number | undefined

watch(() => props.card.bot.id, () => {
  flipped.value = false
  scanning.value = false
  editingName.value = false
  clearTimeout(scanTimer)
})

const daysOnboard = computed(() => {
  const diff = Date.now() - new Date(props.card.bot.createdAt).getTime()
  return Math.max(1, Math.floor(diff / 86_400_000) + 1)
})

const badgeCode = computed(() => props.card.bot.main ? 'MAIN-01' : props.card.bot.id.slice(-4).toUpperCase())

const barcodePath = computed(() => {
  const str = `*${props.card.bot.id.toUpperCase()}*`
  const widths: number[] = []
  for (const ch of str) {
    const code = ch.charCodeAt(0)
    widths.push(((code & 3) % 3) + 1, (((code >> 2) & 3) % 3) + 1, (((code >> 4) & 3) % 3) + 1)
  }
  return {
    total: widths.length * 4,
    path: widths.map((w, idx) => `M${idx * 4} 0h${w}v1h-${w}z`).join(''),
  }
})

function onPointerMove(event: PointerEvent) {
  if (editorOpen.value || editingName.value) {
    leanDeg.value = 0
    return
  }
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const offset = (event.clientX - rect.left) / Math.max(1, rect.width) - 0.5
  leanDeg.value = Math.max(-1.6, Math.min(1.6, offset * 3.2))
}

function swipeToDesk() {
  if (scanning.value || flipped.value) return
  scanning.value = true
  clearTimeout(scanTimer)
  scanTimer = window.setTimeout(() => {
    scanning.value = false
    flipped.value = true
  }, 420)
}

function startRename() {
  nameDraft.value = props.card.bot.name
  editingName.value = true
  void nextTick(() => {
    nameInputRef.value?.focus()
    nameInputRef.value?.select()
  })
}

function commitRename() {
  if (!editingName.value) return
  editingName.value = false
  const next = nameDraft.value.trim()
  if (next && next !== props.card.bot.name && !/[\s@]/.test(next)) {
    emit('rename', next)
  }
}
</script>

<template>
  <div
    class="bot-badge-stage"
    :style="{ '--badge-lean': `${leanDeg}deg` }"
    @pointermove="onPointerMove"
    @pointerleave="leanDeg = 0"
  >
    <div class="bot-badge-lanyard" :style="{ '--lanyard-color': card.bot.color }" aria-hidden="true">
      <span class="lanyard-strap"></span>
      <span class="lanyard-clip"></span>
    </div>
    <div class="bot-badge-swing" :class="{ 'is-editor-open': editorOpen }">
      <div class="bot-badge-flipper" :class="{ flipped }">
        <!-- Front: Employee Badge -->
        <div class="bot-badge-face is-front" :class="`tier-${card.experience.tier.toLowerCase()}`">
          <div class="bot-badge-banner">
            <span class="bot-badge-tier">Lv.{{ card.experience.level }} · {{ botTierLabel(card.experience.tier) }}</span>
            <span class="bot-badge-no">#{{ badgeCode }}</span>
          </div>
          <div class="bot-badge-body">
            <BotAvatarEditor
              :card="card"
              @update-look="(s, c) => emit('updateLook', s, c)"
              @update-outfit="emit('updateOutfit', $event)"
              @upload-avatar="(b, f) => emit('uploadAvatar', b, f)"
              @delete-avatar="emit('deleteAvatar')"
              @open-change="editorOpen = $event"
            />
            <div class="bot-badge-ident">
              <input
                v-if="editingName"
                ref="nameInputRef"
                v-model="nameDraft"
                class="bot-badge-name-input"
                maxlength="30"
                @keydown.enter.prevent="commitRename"
                @keydown.esc.prevent="editingName = false"
                @blur="commitRename"
              />
              <button v-else type="button" class="bot-badge-name-btn" title="点击修改 Bot 名称" @click="startRename">
                <strong>{{ card.bot.name }}</strong>
              </button>
              <small>{{ card.bot.roleSummary || '常驻数字员工' }}</small>
            </div>
          </div>
          <div class="bot-badge-metrics">
            <div><strong>第 {{ daysOnboard }} 天</strong><small>入职</small></div>
            <div><strong>{{ turnCount }}</strong><small>对话</small></div>
            <div><strong>{{ playbookCount + (skillCount ?? 0) }}</strong><small>能力</small></div>
            <div><strong>{{ card.experience.xp }}</strong><small>经验</small></div>
          </div>
          <button type="button" class="bot-barcode-strip" :class="{ 'is-scanning': scanning }" title="刷卡翻面查看工位" @click="swipeToDesk">
            <svg class="bot-barcode-svg" :viewBox="`0 0 ${barcodePath.total} 1`" preserveAspectRatio="none" aria-hidden="true">
              <path :d="barcodePath.path" />
            </svg>
            <span v-if="scanning" class="bot-barcode-laser" aria-hidden="true"></span>
            <span class="barcode-hint">{{ scanning ? '嘀！正在刷卡进工位…' : '刷卡翻面 · 看工位' }}</span>
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
  </div>
</template>

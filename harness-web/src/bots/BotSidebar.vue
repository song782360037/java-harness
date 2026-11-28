<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotContextMenu from './BotContextMenu.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import BotStatusPopover from './BotStatusPopover.vue'
import type { BotCard, BotLayoutItem, BotSection } from './api'

const props = defineProps<{
  bots: BotCard[]
  sections: BotSection[]
  selectedBotId: string
  outerSidebarCollapsed: boolean
  width?: number
}>()

const emit = defineEmits<{
  select: [botId: string]
  createBot: []
  createSection: [name: string]
  toggleSection: [section: BotSection]
  deleteSection: [sectionId: string]
  togglePin: [card: BotCard]
  reorder: [items: BotLayoutItem[]]
  renameBot: [botId: string, name: string]
  markUnread: [botId: string]
  markSeen: [botId: string]
  markAllSeen: []
  moveBotToSection: [botId: string, sectionId: string]
  createSectionAndMove: [botId: string, sectionName: string]
  toggleHide: [card: BotCard]
  archiveBot: [botId: string]
  expandOuterSidebar: []
  openDevices: []
  openGuide: []
}>()

const search = ref('')
const statusFilter = ref<'ALL' | 'UNREAD' | 'WORKING' | 'FAILED'>('ALL')
const addingSection = ref(false)
const newSectionName = ref('')
const draggingBotId = ref('')
const showHidden = ref(false)

// Hover status popover
const hovered = ref<{ card: BotCard; anchor: { left: number; top?: number; bottom?: number } } | null>(null)
let hoverTimer: number | undefined

// Right-click context menu
const contextMenu = ref<{ card: BotCard; x: number; y: number } | null>(null)

// Inline rename
const renamingBotId = ref('')
const renameDraft = ref('')
const renameInputRef = ref<HTMLInputElement[]>()

const visibleBots = computed(() => props.bots.filter(b => !b.bot.hidden))
const hiddenBots = computed(() => props.bots.filter(b => b.bot.hidden))
const unreadBotsCount = computed(() => visibleBots.value.filter(b => b.unreadCount > 0).length)
const totalUnreadCount = computed(() => visibleBots.value.reduce((sum, b) => sum + b.unreadCount, 0))
const workingCount = computed(() => visibleBots.value.filter(b => b.status === 'WORKING' || b.status === 'REPLYING').length)
const failedCount = computed(() => visibleBots.value.filter(b => b.status === 'FAILED').length)

const filteredBots = computed(() => {
  const q = search.value.trim().toLowerCase()
  const pool = q ? props.bots : visibleBots.value
  return pool.filter(card => {
    if (statusFilter.value === 'UNREAD' && card.unreadCount <= 0) return false
    if (statusFilter.value === 'WORKING' && card.status !== 'WORKING' && card.status !== 'REPLYING') return false
    if (statusFilter.value === 'FAILED' && card.status !== 'FAILED') return false
    if (!q) return true
    return `${card.bot.name} ${card.bot.roleSummary} ${card.preview}`.toLowerCase().includes(q)
  })
})

const pinnedBots = computed(() => filteredBots.value.filter(card => card.bot.main || card.bot.pinned))
const ungroupedBots = computed(() => filteredBots.value.filter(card => !card.bot.main && !card.bot.pinned && !card.bot.sectionId))
function sectionBots(sectionId: string) {
  return filteredBots.value.filter(card => !card.bot.main && !card.bot.pinned && card.bot.sectionId === sectionId)
}
function sectionUnread(sectionId: string) {
  return sectionBots(sectionId).reduce((sum, b) => sum + (b.bot.id === props.selectedBotId ? 0 : b.unreadCount), 0)
}
function sectionWorking(sectionId: string) {
  return sectionBots(sectionId).filter(b => b.status === 'WORKING' || b.status === 'REPLYING').length
}

function submitSection() {
  const name = newSectionName.value.trim()
  if (!name) return
  emit('createSection', name)
  newSectionName.value = ''
  addingSection.value = false
}

function onRowEnter(card: BotCard, event: MouseEvent) {
  clearTimeout(hoverTimer)
  if (contextMenu.value || renamingBotId.value) return
  const el = event.currentTarget as HTMLElement
  hoverTimer = window.setTimeout(() => {
    const rect = el.getBoundingClientRect()
    const aside = el.closest('.bot-roster')?.getBoundingClientRect()
    const left = (aside ? aside.right : rect.right) + 8
    const anchor = rect.top < window.innerHeight / 2
      ? { left, top: Math.max(12, rect.top) }
      : { left, bottom: Math.max(12, window.innerHeight - rect.bottom) }
    hovered.value = { card, anchor }
  }, 360)
}

function onRowLeave() {
  clearTimeout(hoverTimer)
  hovered.value = null
}

function openContextMenu(card: BotCard, event: MouseEvent) {
  event.preventDefault()
  clearTimeout(hoverTimer)
  hovered.value = null
  contextMenu.value = { card, x: event.clientX, y: event.clientY }
}

function startInlineRename(botId: string) {
  const card = props.bots.find(b => b.bot.id === botId)
  if (!card) return
  renamingBotId.value = botId
  renameDraft.value = card.bot.name
  void nextTick(() => {
    const el = Array.isArray(renameInputRef.value) ? renameInputRef.value[0] : renameInputRef.value
    el?.focus()
    el?.select()
  })
}

function commitInlineRename(card: BotCard) {
  if (renamingBotId.value !== card.bot.id) return
  renamingBotId.value = ''
  const next = renameDraft.value.trim()
  if (next && next !== card.bot.name && !/[\s@]/.test(next)) {
    emit('renameBot', card.bot.id, next)
  }
}

function onDragStart(botId: string) {
  draggingBotId.value = botId
  onRowLeave()
}

function onDropOnBot(target: BotCard) {
  const sourceId = draggingBotId.value
  draggingBotId.value = ''
  if (!sourceId || sourceId === target.bot.id) return
  const ordered = [...props.bots]
  const fromIdx = ordered.findIndex(item => item.bot.id === sourceId)
  const toIdx = ordered.findIndex(item => item.bot.id === target.bot.id)
  if (fromIdx === -1 || toIdx === -1) return
  const [moved] = ordered.splice(fromIdx, 1)
  ordered.splice(toIdx, 0, moved)
  const items: BotLayoutItem[] = ordered.map((item, idx) => ({
    botId: item.bot.id,
    sectionId: item.bot.id === sourceId ? target.bot.sectionId : item.bot.sectionId,
    pinned: item.bot.id === sourceId ? target.bot.pinned : item.bot.pinned,
    ordinal: idx,
  }))
  emit('reorder', items)
}

function onDropOnSection(sectionId: string) {
  const sourceId = draggingBotId.value
  draggingBotId.value = ''
  if (!sourceId) return
  const items: BotLayoutItem[] = props.bots.map((item, idx) => ({
    botId: item.bot.id,
    sectionId: item.bot.id === sourceId ? sectionId : item.bot.sectionId,
    pinned: item.bot.id === sourceId ? false : item.bot.pinned,
    ordinal: idx,
  }))
  emit('reorder', items)
}

function rowSubtitle(card: BotCard): string {
  if (card.status === 'FAILED') return card.failedReason || '任务失败'
  if (card.status === 'WORKING') return card.activeTasks[0]?.statusText || card.activeTasks[0]?.title || '后台干活中…'
  if (card.status === 'REPLYING') return '正在回复…'
  if (card.status === 'QUEUED') return `${card.queuedCount} 条消息排队中`
  return card.preview || card.bot.roleSummary || '随时待命'
}
</script>

<template>
  <aside
    class="bot-roster"
    :style="width ? { width: `${width}px`, flexBasis: `${width}px` } : undefined"
    aria-label="Bot 团队列表"
  >
    <header class="bot-roster-header">
      <div class="bot-roster-title">
        <button v-if="outerSidebarCollapsed" type="button" class="icon-button" title="展开工作台导航" @click="emit('expandOuterSidebar')">
          <UiIcon name="panel" :size="15" />
        </button>
        <h2>Bot 团队</h2>
      </div>
      <div class="bot-roster-actions">
        <button type="button" class="icon-button" title="新建分组" @click="addingSection = !addingSection">
          <UiIcon name="folder" :size="15" />
        </button>
        <button type="button" class="bot-add-btn" @click="emit('createBot')">
          <UiIcon name="plus" :size="14" />
          <span>招新</span>
        </button>
      </div>
    </header>

    <div class="bot-search-box">
      <UiIcon name="search" :size="14" />
      <input v-model="search" placeholder="搜索 Bot 或近况" aria-label="搜索 Bot" />
      <button v-if="search" type="button" class="icon-button" aria-label="清空搜索" @click="search = ''">
        <UiIcon name="close" :size="12" />
      </button>
    </div>

    <div v-if="unreadBotsCount > 0 || workingCount > 0 || failedCount > 0" class="bot-status-filters">
      <button
        v-if="unreadBotsCount > 0"
        type="button"
        class="bot-filter-pill is-unread"
        :class="{ active: statusFilter === 'UNREAD' }"
        @click="statusFilter = statusFilter === 'UNREAD' ? 'ALL' : 'UNREAD'"
      >
        <span class="dot"></span>未读 {{ totalUnreadCount > 99 ? '99+' : totalUnreadCount }}
      </button>
      <button
        v-if="workingCount > 0"
        type="button"
        class="bot-filter-pill is-working"
        :class="{ active: statusFilter === 'WORKING' }"
        @click="statusFilter = statusFilter === 'WORKING' ? 'ALL' : 'WORKING'"
      >
        <span class="dot"></span>干活中 {{ workingCount }}
      </button>
      <button
        v-if="failedCount > 0"
        type="button"
        class="bot-filter-pill is-failed"
        :class="{ active: statusFilter === 'FAILED' }"
        @click="statusFilter = statusFilter === 'FAILED' ? 'ALL' : 'FAILED'"
      >
        <span class="dot"></span>未跑成 {{ failedCount }}
      </button>
      <button
        v-if="totalUnreadCount > 0"
        type="button"
        class="bot-filter-pill is-seen-all"
        title="将全部 Bot 标为已读"
        @click="statusFilter = statusFilter === 'UNREAD' ? 'ALL' : statusFilter; emit('markAllSeen')"
      >
        全部已读
      </button>
    </div>

    <form v-if="addingSection" class="bot-new-section" @submit.prevent="submitSection">
      <input v-model="newSectionName" placeholder="输入分组名称…" maxlength="60" autofocus />
      <button type="submit">创建</button>
    </form>

    <div class="bot-roster-list">
      <!-- Pinned & Main -->
      <div v-if="pinnedBots.length" class="bot-group">
        <div class="bot-group-label">置顶与总管</div>
        <button
          v-for="card in pinnedBots"
          :key="card.bot.id"
          type="button"
          class="bot-row"
          :class="{ selected: card.bot.id === selectedBotId }"
          draggable="true"
          @mouseenter="onRowEnter(card, $event)"
          @mouseleave="onRowLeave"
          @contextmenu="openContextMenu(card, $event)"
          @dragstart="onDragStart(card.bot.id)"
          @dragover.prevent
          @drop.prevent="onDropOnBot(card)"
          @click="emit('select', card.bot.id)"
        >
          <BotLookAvatar
            :shape="card.bot.shape"
            :color="card.bot.color"
            :avatar-url="card.avatarUrl"
            :outfit-json="card.bot.outfitJson"
            :size="34"
            :status="card.status"
            :progress="card.experience.progress"
            show-ring
          />
          <div class="bot-row-copy">
            <div class="bot-row-top">
              <input
                v-if="renamingBotId === card.bot.id"
                ref="renameInputRef"
                v-model="renameDraft"
                class="bot-inline-rename"
                maxlength="30"
                @click.stop
                @keydown.enter.prevent="commitInlineRename(card)"
                @keydown.esc.prevent="renamingBotId = ''"
                @blur="commitInlineRename(card)"
              />
              <strong v-else>{{ card.bot.name }}</strong>
              <span v-if="card.unreadCount > 0 && card.bot.id !== selectedBotId" class="bot-unread-badge">
                {{ card.unreadCount > 99 ? '99+' : card.unreadCount }}
              </span>
              <span v-else-if="card.bot.main" class="bot-main-tag">总管</span>
              <span v-else class="bot-lv-tag">Lv.{{ card.experience.level }}</span>
            </div>
            <p class="bot-row-sub" :class="{ 'is-error': card.status === 'FAILED', 'is-active': card.status === 'WORKING' || card.status === 'REPLYING' }">
              {{ rowSubtitle(card) }}
            </p>
          </div>
        </button>
      </div>

      <!-- Custom Sections -->
      <section
        v-for="sec in sections"
        :key="sec.id"
        class="bot-group"
        @dragover.prevent
        @drop.prevent="onDropOnSection(sec.id)"
      >
        <div class="bot-group-label is-clickable">
          <button type="button" class="bot-section-toggle" @click="emit('toggleSection', sec)">
            <UiIcon :name="sec.collapsed ? 'chevron' : 'down'" :size="12" />
            <span>{{ sec.name }}</span>
            <small>{{ sectionBots(sec.id).length }}</small>
            <span v-if="sec.collapsed && sectionWorking(sec.id) > 0" class="bot-sec-dot is-working" title="有 Bot 正在干活"></span>
            <span v-if="sec.collapsed && sectionUnread(sec.id) > 0" class="bot-unread-badge is-mini">{{ sectionUnread(sec.id) }}</span>
          </button>
          <button type="button" class="icon-button section-del" title="删除分组（Bot 会保留）" @click="emit('deleteSection', sec.id)">
            <UiIcon name="close" :size="12" />
          </button>
        </div>
        <template v-if="!sec.collapsed || search">
          <button
            v-for="card in sectionBots(sec.id)"
            :key="card.bot.id"
            type="button"
            class="bot-row"
            :class="{ selected: card.bot.id === selectedBotId }"
            draggable="true"
            @mouseenter="onRowEnter(card, $event)"
            @mouseleave="onRowLeave"
            @contextmenu="openContextMenu(card, $event)"
            @dragstart="onDragStart(card.bot.id)"
            @dragover.prevent
            @drop.prevent="onDropOnBot(card)"
            @click="emit('select', card.bot.id)"
          >
            <BotLookAvatar
              :shape="card.bot.shape"
              :color="card.bot.color"
              :avatar-url="card.avatarUrl"
              :outfit-json="card.bot.outfitJson"
              :size="34"
              :status="card.status"
              :progress="card.experience.progress"
              show-ring
            />
            <div class="bot-row-copy">
              <div class="bot-row-top">
                <input
                  v-if="renamingBotId === card.bot.id"
                  ref="renameInputRef"
                  v-model="renameDraft"
                  class="bot-inline-rename"
                  maxlength="30"
                  @click.stop
                  @keydown.enter.prevent="commitInlineRename(card)"
                  @keydown.esc.prevent="renamingBotId = ''"
                  @blur="commitInlineRename(card)"
                />
                <strong v-else>{{ card.bot.name }}</strong>
                <span v-if="card.unreadCount > 0 && card.bot.id !== selectedBotId" class="bot-unread-badge">
                  {{ card.unreadCount > 99 ? '99+' : card.unreadCount }}
                </span>
                <span v-else class="bot-lv-tag">Lv.{{ card.experience.level }}</span>
              </div>
              <p class="bot-row-sub" :class="{ 'is-error': card.status === 'FAILED', 'is-active': card.status === 'WORKING' || card.status === 'REPLYING' }">
                {{ rowSubtitle(card) }}
              </p>
            </div>
          </button>
        </template>
      </section>

      <!-- Ungrouped -->
      <div v-if="ungroupedBots.length" class="bot-group" @dragover.prevent @drop.prevent="onDropOnSection('')">
        <div class="bot-group-label">团队成员</div>
        <button
          v-for="card in ungroupedBots"
          :key="card.bot.id"
          type="button"
          class="bot-row"
          :class="{ selected: card.bot.id === selectedBotId }"
          draggable="true"
          @mouseenter="onRowEnter(card, $event)"
          @mouseleave="onRowLeave"
          @contextmenu="openContextMenu(card, $event)"
          @dragstart="onDragStart(card.bot.id)"
          @dragover.prevent
          @drop.prevent="onDropOnBot(card)"
          @click="emit('select', card.bot.id)"
        >
          <BotLookAvatar
            :shape="card.bot.shape"
            :color="card.bot.color"
            :avatar-url="card.avatarUrl"
            :outfit-json="card.bot.outfitJson"
            :size="34"
            :status="card.status"
            :progress="card.experience.progress"
            show-ring
          />
          <div class="bot-row-copy">
            <div class="bot-row-top">
              <input
                v-if="renamingBotId === card.bot.id"
                ref="renameInputRef"
                v-model="renameDraft"
                class="bot-inline-rename"
                maxlength="30"
                @click.stop
                @keydown.enter.prevent="commitInlineRename(card)"
                @keydown.esc.prevent="renamingBotId = ''"
                @blur="commitInlineRename(card)"
              />
              <strong v-else>{{ card.bot.name }}</strong>
              <span v-if="card.unreadCount > 0 && card.bot.id !== selectedBotId" class="bot-unread-badge">
                {{ card.unreadCount > 99 ? '99+' : card.unreadCount }}
              </span>
              <span v-else class="bot-lv-tag">Lv.{{ card.experience.level }}</span>
            </div>
            <p class="bot-row-sub" :class="{ 'is-error': card.status === 'FAILED', 'is-active': card.status === 'WORKING' || card.status === 'REPLYING' }">
              {{ rowSubtitle(card) }}
            </p>
          </div>
        </button>
      </div>

      <!-- Hidden Bots Collapsible Group -->
      <section v-if="hiddenBots.length && !search" class="bot-group is-hidden-group">
        <div class="bot-group-label is-clickable">
          <button type="button" class="bot-section-toggle" @click="showHidden = !showHidden">
            <UiIcon :name="showHidden ? 'down' : 'chevron'" :size="12" />
            <span>已隐藏</span>
            <small>{{ hiddenBots.length }}</small>
          </button>
        </div>
        <template v-if="showHidden">
          <button
            v-for="card in hiddenBots"
            :key="card.bot.id"
            type="button"
            class="bot-row is-dim"
            :class="{ selected: card.bot.id === selectedBotId }"
            @mouseenter="onRowEnter(card, $event)"
            @mouseleave="onRowLeave"
            @contextmenu="openContextMenu(card, $event)"
            @click="emit('select', card.bot.id)"
          >
            <BotLookAvatar
              :shape="card.bot.shape"
              :color="card.bot.color"
              :avatar-url="card.avatarUrl"
              :outfit-json="card.bot.outfitJson"
              :size="30"
              :status="card.status"
            />
            <div class="bot-row-copy">
              <div class="bot-row-top">
                <strong>{{ card.bot.name }}</strong>
                <span class="bot-lv-tag">Lv.{{ card.experience.level }}</span>
              </div>
              <p class="bot-row-sub">{{ rowSubtitle(card) }}</p>
            </div>
          </button>
        </template>
      </section>
    </div>

    <footer class="bot-roster-footer">
      <button type="button" class="bot-roster-foot-btn" title="查看新手引导与角色模板库" @click="emit('openGuide')">
        <UiIcon name="wand" :size="14" />
        <span>使用指南</span>
      </button>
      <button type="button" class="bot-roster-foot-btn" title="局域网手机伴侣与扫码配对" @click="emit('openDevices')">
        <UiIcon name="globe" :size="14" />
        <span>手机伴侣</span>
      </button>
    </footer>

    <BotStatusPopover v-if="hovered" :card="hovered.card" :anchor="hovered.anchor" />
    <BotContextMenu
      v-if="contextMenu"
      :card="contextMenu.card"
      :sections="sections"
      :x="contextMenu.x"
      :y="contextMenu.y"
      @close="contextMenu = null"
      @toggle-pin="emit('togglePin', $event)"
      @mark-unread="emit('markUnread', $event)"
      @mark-seen="emit('markSeen', $event)"
      @rename="startInlineRename"
      @move-to-section="(b, s) => emit('moveBotToSection', b, s)"
      @create-section-and-move="(b, n) => emit('createSectionAndMove', b, n)"
      @toggle-hide="emit('toggleHide', $event)"
      @archive="emit('archiveBot', $event)"
    />
  </aside>
</template>

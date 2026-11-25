<script setup lang="ts">
import { computed, ref } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import { botStatusLabel, type BotCard, type BotLayoutItem, type BotSection } from './api'

const props = defineProps<{
  bots: BotCard[]
  sections: BotSection[]
  selectedBotId: string
  outerSidebarCollapsed: boolean
}>()

const emit = defineEmits<{
  select: [botId: string]
  createBot: []
  createSection: [name: string]
  toggleSection: [section: BotSection]
  deleteSection: [sectionId: string]
  togglePin: [card: BotCard]
  reorder: [items: BotLayoutItem[]]
  expandOuterSidebar: []
  openDevices: []
  openGuide: []
}>()

const search = ref('')
const statusFilter = ref<'ALL' | 'WORKING' | 'FAILED'>('ALL')
const addingSection = ref(false)
const newSectionName = ref('')
const draggingBotId = ref('')

const workingCount = computed(() => props.bots.filter(b => b.status === 'WORKING' || b.status === 'REPLYING').length)
const failedCount = computed(() => props.bots.filter(b => b.status === 'FAILED').length)

const filteredBots = computed(() => {
  const q = search.value.trim().toLowerCase()
  return props.bots.filter(card => {
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

function submitSection() {
  const name = newSectionName.value.trim()
  if (!name) return
  emit('createSection', name)
  newSectionName.value = ''
  addingSection.value = false
}

function onDragStart(botId: string) {
  draggingBotId.value = botId
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

function hoverSummary(card: BotCard): string {
  const parts = [`${card.bot.name} · Lv.${card.experience.level} · ${botStatusLabel(card.status)}`]
  if (card.failedReason) parts.push(`失败原因：${card.failedReason}`)
  if (card.activeTasks.length) parts.push(`进行中：${card.activeTasks.map(t => t.title).join('、')}`)
  if (card.routineCount) parts.push(`例行任务：${card.routineCount} 项`)
  return parts.join('\n')
}
</script>

<template>
  <aside class="bot-roster" aria-label="Bot 团队列表">
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

    <div v-if="workingCount > 0 || failedCount > 0" class="bot-status-filters">
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
    </div>

    <form v-if="addingSection" class="bot-new-section" @submit.prevent="submitSection">
      <input v-model="newSectionName" placeholder="输入分组名称…" maxlength="60" autofocus />
      <button type="submit">创建</button>
    </form>

    <div class="bot-roster-list">
      <div v-if="pinnedBots.length" class="bot-group">
        <div class="bot-group-label">置顶与总管</div>
        <button
          v-for="card in pinnedBots"
          :key="card.bot.id"
          type="button"
          class="bot-row"
          :class="{ selected: card.bot.id === selectedBotId }"
          :title="hoverSummary(card)"
          draggable="true"
          @dragstart="onDragStart(card.bot.id)"
          @dragover.prevent
          @drop.prevent="onDropOnBot(card)"
          @click="emit('select', card.bot.id)"
        >
          <BotLookAvatar :shape="card.bot.shape" :color="card.bot.color" :size="34" :status="card.status" :progress="card.experience.progress" show-ring />
          <div class="bot-row-copy">
            <div class="bot-row-top">
              <strong>{{ card.bot.name }}</strong>
              <span v-if="card.bot.main" class="bot-main-tag">总管</span>
              <span v-else class="bot-lv-tag">Lv.{{ card.experience.level }}</span>
            </div>
            <p class="bot-row-sub" :class="{ 'is-error': card.status === 'FAILED', 'is-active': card.status === 'WORKING' || card.status === 'REPLYING' }">
              {{ card.status === 'FAILED' ? (card.failedReason || '任务失败') : card.status === 'WORKING' ? (card.activeTasks[0]?.title || '后台干活中…') : card.status === 'REPLYING' ? '正在回复…' : card.status === 'QUEUED' ? `${card.queuedCount} 条消息排队中` : (card.preview || card.bot.roleSummary || '随时待命') }}
            </p>
          </div>
        </button>
      </div>

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
            :title="hoverSummary(card)"
            draggable="true"
            @dragstart="onDragStart(card.bot.id)"
            @dragover.prevent
            @drop.prevent="onDropOnBot(card)"
            @click="emit('select', card.bot.id)"
          >
            <BotLookAvatar :shape="card.bot.shape" :color="card.bot.color" :size="34" :status="card.status" :progress="card.experience.progress" show-ring />
            <div class="bot-row-copy">
              <div class="bot-row-top">
                <strong>{{ card.bot.name }}</strong>
                <span class="bot-lv-tag">Lv.{{ card.experience.level }}</span>
              </div>
              <p class="bot-row-sub" :class="{ 'is-error': card.status === 'FAILED', 'is-active': card.status === 'WORKING' || card.status === 'REPLYING' }">
                {{ card.status === 'FAILED' ? (card.failedReason || '任务失败') : card.status === 'WORKING' ? (card.activeTasks[0]?.title || '后台干活中…') : (card.preview || card.bot.roleSummary || '随时待命') }}
              </p>
            </div>
          </button>
        </template>
      </section>

      <div v-if="ungroupedBots.length" class="bot-group" @dragover.prevent @drop.prevent="onDropOnSection('')">
        <div class="bot-group-label">团队成员</div>
        <button
          v-for="card in ungroupedBots"
          :key="card.bot.id"
          type="button"
          class="bot-row"
          :class="{ selected: card.bot.id === selectedBotId }"
          :title="hoverSummary(card)"
          draggable="true"
          @dragstart="onDragStart(card.bot.id)"
          @dragover.prevent
          @drop.prevent="onDropOnBot(card)"
          @click="emit('select', card.bot.id)"
        >
          <BotLookAvatar :shape="card.bot.shape" :color="card.bot.color" :size="34" :status="card.status" :progress="card.experience.progress" show-ring />
          <div class="bot-row-copy">
            <div class="bot-row-top">
              <strong>{{ card.bot.name }}</strong>
              <span class="bot-lv-tag">Lv.{{ card.experience.level }}</span>
            </div>
            <p class="bot-row-sub" :class="{ 'is-error': card.status === 'FAILED', 'is-active': card.status === 'WORKING' || card.status === 'REPLYING' }">
              {{ card.status === 'FAILED' ? (card.failedReason || '任务失败') : card.status === 'WORKING' ? (card.activeTasks[0]?.title || '后台干活中…') : (card.preview || card.bot.roleSummary || '随时待命') }}
            </p>
          </div>
        </button>
      </div>
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
  </aside>
</template>

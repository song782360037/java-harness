<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import BotMarkdownContent from './BotMarkdownContent.vue'
import BotSkillOfferCard from './BotSkillOfferCard.vue'
import DynamicIsland from './DynamicIsland.vue'
import MiniAppCard from './MiniAppCard.vue'
import {
  activeMentionQuery,
  botApi,
  extractBotApps,
  formatElapsed,
  formatTimeDivider,
  isImageMediaType,
  miniAppIconName,
  parseAttachments,
  parseSuggestions,
  shouldShowTimeDivider,
  type BotCard,
  type BotEntry,
  type BotMiniApp,
  type BotSkillOffer,
  type BotTask,
  type BotThreadSnapshot,
  type IslandNotice,
  type UploadedAttachment,
} from './api'

interface ComposerDraftState {
  text: string
  attachments: UploadedAttachment[]
  quote: BotEntry | null
}

const props = defineProps<{
  thread: BotThreadSnapshot | null
  allBots: BotCard[]
  islandNotice: IslandNotice | null
  recentNotices: IslandNotice[]
  sending: boolean
  detailOpen: boolean
  detailDrawerMode: boolean
  compactLayout: boolean
  hasConfiguredModel: boolean
  viewingApp?: BotMiniApp | null
  backJump?: { botId: string; botName: string } | null
  answeringOfferId?: string
}>()

const emit = defineEmits<{
  send: [payload: { content: string; quoteSeq: number | null; attachmentIds?: string[]; viewingMiniAppId?: string }]
  stop: []
  deleteEntries: [seqs: number[]]
  selectBot: [botId: string]
  jumpToMention: [botId: string, scrollTop: number]
  jumpBack: []
  dismissNotice: []
  openTask: [taskId: string]
  cancelTask: [taskId: string]
  toggleDetail: []
  toggleRoster: []
  openManualTask: []
  openModels: []
  shareEntries: [entries: BotEntry[]]
  openApp: [appId: string]
  closeViewingApp: []
  openPlaybook: [playbookId: string]
  openSkill: [target: string]
  openFile: [botId: string, relativePath: string]
  answerSkillOffer: [offerId: string, names: string[]]
  viewOfferSkillFiles: [offerId: string, skillName: string]
  approveCommand: [commandId: string]
  rejectCommand: [commandId: string]
  openSession: [sessionId: string]
}>()

const draftByBot = ref<Record<string, ComposerDraftState>>({})
const draft = ref('')
const inputRef = ref<HTMLTextAreaElement>()
const fileInputRef = ref<HTMLInputElement>()
const timelineRef = ref<HTMLElement>()
const searchInputRef = ref<HTMLInputElement>()
const quoting = ref<BotEntry | null>(null)
const mentionIndex = ref(0)
const selecting = ref(false)
const selectedSeqs = ref<number[]>([])
const copyFlash = ref('')
const copiedEntryId = ref('')
const pendingAttachments = ref<UploadedAttachment[]>([])
const uploading = ref(false)
const uploadError = ref('')
const draggingFiles = ref(false)
const lightbox = ref<{ url: string; name: string } | null>(null)
const confirmingDeleteSeqs = ref<number[] | null>(null)
const autoScroll = ref(true)
const nowMs = ref(Date.now())
let tickTimer: number | undefined

// In-chat history search
const searchOpen = ref(false)
const searchQuery = ref('')
const searchResults = ref<BotEntry[]>([])
const searchIndex = ref(0)
const searching = ref(false)
const highlightedEntrySeq = ref<number | null>(null)
let searchTimer: number | undefined
let highlightTimer: number | undefined

const currentCard = computed(() => props.thread?.card || null)
const isReplying = computed(() => !!props.thread?.streaming || currentCard.value?.status === 'REPLYING')
const activeTasks = computed(() => currentCard.value?.activeTasks || [])
const pendingApprovals = computed(() => props.thread?.pendingApprovals || [])
const lastEntrySeq = computed(() => props.thread?.entries.at(-1)?.seq ?? -1)
const selectedEntries = computed(() =>
  (props.thread?.entries || []).filter(entry => selectedSeqs.value.includes(entry.seq)),
)
const canSend = computed(() => (!!draft.value.trim() || pendingAttachments.value.length > 0) && !props.sending && !uploading.value)
const showStopAction = computed(() => isReplying.value && !canSend.value)

const mentionMatch = computed(() => {
  const el = inputRef.value
  const cursor = el ? el.selectionStart ?? draft.value.length : draft.value.length
  return activeMentionQuery(draft.value, cursor)
})

const mentionCandidates = computed(() => {
  const match = mentionMatch.value
  if (!match) return []
  const q = match.query.toLowerCase()
  return props.allBots
    .filter(card => card.bot.id !== currentCard.value?.bot.id)
    .filter(card => !q || card.bot.name.toLowerCase().includes(q) || card.bot.roleSummary.toLowerCase().includes(q))
    .slice(0, 6)
})

function saveCurrentDraft(botId: string) {
  draftByBot.value[botId] = {
    text: draft.value,
    attachments: [...pendingAttachments.value],
    quote: quoting.value,
  }
}

watch(() => props.thread?.card.bot.id, (nextId, prevId) => {
  if (prevId) saveCurrentDraft(prevId)
  const restored = nextId ? draftByBot.value[nextId] : undefined
  draft.value = restored?.text ?? ''
  pendingAttachments.value = restored?.attachments ? [...restored.attachments] : []
  quoting.value = restored?.quote ?? null
  selecting.value = false
  selectedSeqs.value = []
  uploadError.value = ''
  confirmingDeleteSeqs.value = null
  closeChatSearch()
  autoScroll.value = true
  void scrollToBottom(true)
})

watch([draft, pendingAttachments, quoting], () => {
  const id = props.thread?.card.bot.id
  if (id) saveCurrentDraft(id)
}, { deep: true })

function parseSkillOffer(raw: string): BotSkillOffer | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<BotSkillOffer>
    if (!parsed || typeof parsed.offerId !== 'string' || !Array.isArray(parsed.skills)) return null
    return {
      offerId: parsed.offerId,
      targetBotId: parsed.targetBotId || '',
      targetBotName: parsed.targetBotName || '',
      skills: parsed.skills,
      skipped: Array.isArray(parsed.skipped) ? parsed.skipped : [],
      installed: Array.isArray(parsed.installed) ? parsed.installed : null,
    }
  } catch {
    return null
  }
}

function toggleChatSearch() {
  if (searchOpen.value) {
    closeChatSearch()
    return
  }
  searchOpen.value = true
  void nextTick(() => searchInputRef.value?.focus())
}

function closeChatSearch() {
  searchOpen.value = false
  searchQuery.value = ''
  searchResults.value = []
  searchIndex.value = 0
  searching.value = false
  clearTimeout(searchTimer)
}

watch(searchQuery, q => {
  clearTimeout(searchTimer)
  const trimmed = q.trim()
  const botId = currentCard.value?.bot.id
  if (!trimmed || !botId) {
    searchResults.value = []
    searchIndex.value = 0
    searching.value = false
    return
  }
  searching.value = true
  searchTimer = window.setTimeout(async () => {
    try {
      const hits = await botApi.searchEntries(botId, trimmed, 30)
      if (searchQuery.value.trim() !== trimmed || currentCard.value?.bot.id !== botId) return
      searchResults.value = hits
      searchIndex.value = 0
      if (hits.length) scrollToEntrySeq(hits[0].seq)
    } catch {
      searchResults.value = []
    } finally {
      searching.value = false
    }
  }, 220)
})

function stepSearch(delta: number) {
  if (!searchResults.value.length) return
  const len = searchResults.value.length
  searchIndex.value = (searchIndex.value + delta + len) % len
  scrollToEntrySeq(searchResults.value[searchIndex.value].seq)
}

function scrollToEntrySeq(seq: number) {
  autoScroll.value = false
  highlightedEntrySeq.value = seq
  clearTimeout(highlightTimer)
  highlightTimer = window.setTimeout(() => {
    if (highlightedEntrySeq.value === seq) highlightedEntrySeq.value = null
  }, 2600)
  void nextTick(() => {
    const el = timelineRef.value?.querySelector<HTMLElement>(`[data-entry-seq="${seq}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

function onTimelineScroll() {
  const el = timelineRef.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 90
}

function jumpToLatest() {
  autoScroll.value = true
  void scrollToBottom(true)
}

async function scrollToBottom(force = false) {
  if (!force && !autoScroll.value) return
  await nextTick()
  const el = timelineRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(() => props.thread?.entries.length, () => {
  void scrollToBottom()
})

watch(() => props.thread?.streaming?.output, () => {
  void scrollToBottom()
})

function enterSelection(seq: number) {
  selecting.value = true
  selectedSeqs.value = [seq]
}

function toggleSelected(seq: number) {
  if (!selecting.value) return
  if (selectedSeqs.value.includes(seq)) {
    selectedSeqs.value = selectedSeqs.value.filter(item => item !== seq)
  } else {
    selectedSeqs.value = [...selectedSeqs.value, seq]
  }
}

function selectAllEntries() {
  selectedSeqs.value = (props.thread?.entries || []).map(entry => entry.seq)
}

function exitSelection() {
  selecting.value = false
  selectedSeqs.value = []
}

async function copyEntry(entry: BotEntry) {
  try {
    await navigator.clipboard.writeText(entry.content)
    copiedEntryId.value = entry.id
    window.setTimeout(() => {
      if (copiedEntryId.value === entry.id) copiedEntryId.value = ''
    }, 1500)
  } catch { /* ignore */ }
}

async function copySelected() {
  if (!selectedEntries.value.length || !currentCard.value) return
  const text = selectedEntries.value
    .map(e => `${e.kind === 'USER' ? '我' : currentCard.value!.bot.name}：${e.content}`)
    .join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    copyFlash.value = '已复制'
    window.setTimeout(() => { copyFlash.value = '' }, 1800)
  } catch { /* ignore */ }
}

function requestDelete(seqs: number[]) {
  if (!seqs.length) return
  confirmingDeleteSeqs.value = [...seqs]
}

function confirmDelete() {
  if (!confirmingDeleteSeqs.value?.length) return
  emit('deleteEntries', confirmingDeleteSeqs.value)
  confirmingDeleteSeqs.value = null
  exitSelection()
}

function openShareSelected() {
  if (!selectedEntries.value.length) return
  emit('shareEntries', [...selectedEntries.value])
}

function fillSuggestion(text: string) {
  draft.value = text
  void nextTick(() => inputRef.value?.focus())
}

async function handleFiles(fileList: FileList | File[]) {
  const files = Array.from(fileList)
  if (!files.length) return
  uploadError.value = ''
  uploading.value = true
  try {
    for (const file of files) {
      if (pendingAttachments.value.length >= 8) {
        uploadError.value = '每条消息最多附带 8 个附件'
        break
      }
      const uploaded = await botApi.uploadAttachment(file)
      pendingAttachments.value = [...pendingAttachments.value, uploaded]
    }
  } catch (cause) {
    uploadError.value = cause instanceof Error ? cause.message : '上传附件失败'
  } finally {
    uploading.value = false
  }
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.length) void handleFiles(input.files)
  input.value = ''
}

function onPaste(event: ClipboardEvent) {
  const files = event.clipboardData?.files
  if (files && files.length > 0 && !event.clipboardData?.types.includes('text/plain')) {
    event.preventDefault()
    void handleFiles(files)
  }
}

function onDragOver(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  draggingFiles.value = true
}

function onDragLeave(event: DragEvent) {
  const current = event.currentTarget as HTMLElement | null
  if (event.relatedTarget instanceof Node && current?.contains(event.relatedTarget)) return
  draggingFiles.value = false
}

function onDrop(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) return
  event.preventDefault()
  draggingFiles.value = false
  if (event.dataTransfer.files.length) void handleFiles(event.dataTransfer.files)
}

function removePendingAttachment(id: string) {
  pendingAttachments.value = pendingAttachments.value.filter(item => item.id !== id)
}

function pickMention(card: BotCard) {
  const match = mentionMatch.value
  const el = inputRef.value
  if (!match) return
  const cursor = el ? el.selectionStart ?? draft.value.length : draft.value.length
  const before = draft.value.slice(0, match.start)
  const after = draft.value.slice(cursor)
  draft.value = `${before}@${card.bot.name} ${after}`
  mentionIndex.value = 0
  void nextTick(() => {
    const pos = before.length + card.bot.name.length + 2
    el?.focus()
    el?.setSelectionRange(pos, pos)
  })
}

function onKeydown(event: KeyboardEvent) {
  if (mentionCandidates.value.length) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      mentionIndex.value = (mentionIndex.value + 1) % mentionCandidates.value.length
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      mentionIndex.value = (mentionIndex.value - 1 + mentionCandidates.value.length) % mentionCandidates.value.length
      return
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault()
      pickMention(mentionCandidates.value[mentionIndex.value] || mentionCandidates.value[0])
      return
    }
  }
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

function onStopClick(event: MouseEvent) {
  if (event.detail >= 2) return
  emit('stop')
}

function focusComposer() {
  void nextTick(() => inputRef.value?.focus())
}

function setScrollTop(value: number) {
  autoScroll.value = false
  void nextTick(() => {
    if (timelineRef.value) timelineRef.value.scrollTop = value
  })
}

defineExpose({ focusComposer, setScrollTop })

function submit() {
  if (!canSend.value) return
  const content = draft.value.trim()
  const quoteSeq = quoting.value?.seq ?? null
  const attachmentIds = pendingAttachments.value.map(a => a.id)
  const viewingMiniAppId = props.viewingApp && props.viewingApp.botId === currentCard.value?.bot.id
    ? props.viewingApp.id
    : undefined
  draft.value = ''
  quoting.value = null
  pendingAttachments.value = []
  if (props.thread?.card.bot.id) {
    draftByBot.value[props.thread.card.bot.id] = { text: '', attachments: [], quote: null }
  }
  uploadError.value = ''
  autoScroll.value = true
  emit('send', { content, quoteSeq, attachmentIds, viewingMiniAppId })
}

function formatTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function openTaskFromIsland(task: BotTask) {
  emit('openTask', task.id)
}

interface StarterCard {
  icon: string
  title: string
  desc: string
  prompt: string
}

const starterCards = computed<StarterCard[]>(() => {
  if (currentCard.value?.bot.main) {
    return [
      {
        icon: 'users',
        title: '认识团队分工',
        desc: '看看当前有哪些 Bot 成员，各自负责什么事务',
        prompt: '帮我看看团队里现在有哪些成员，介绍一下大家各管什么，以及我们怎么配合效率最高',
      },
      {
        icon: 'wand',
        title: '做一个互动小程序',
        desc: '在后台生成可交互的「每日待办看板」小程序',
        prompt: '请派后台任务帮我做一个简洁好用的「每日待办看板」小程序，支持新增、勾选完成和筛选今日任务',
      },
      {
        icon: 'plus',
        title: '对话招募新成员',
        desc: '按需新增一位专职「周报助手」并立好职责',
        prompt: '帮我新招一个叫「周报助手」的 Bot，专门每周五整理我的工作进展并沉淀到资料库',
      },
      {
        icon: 'file',
        title: '记住我的工作偏好',
        desc: '让所有 Bot 长期记住你的输出风格与规范',
        prompt: '记住一个全局偏好：以后给我的汇报尽量先给结论，再用清晰的 Markdown 表格列出关键细节',
      },
    ]
  }
  return [
    {
      icon: 'chat',
      title: '聊聊如何配合',
      desc: '让它介绍自己的专长和最适合接手的日常工作',
      prompt: '介绍一下你最擅长帮我处理哪些事情？我们可以先从什么小任务开始配合？',
    },
    {
      icon: 'wand',
      title: '定制一个顺手小工具',
      desc: '结合岗位需求生成一个轻量互动小程序',
      prompt: '结合你的岗位职责，帮我在后台做一个最实用的小工具小程序吧',
    },
    {
      icon: 'clock',
      title: '安排一条定时例行',
      desc: '讨论哪些重复事务适合每天或每周自动跑',
      prompt: '有哪些工作适合设成你的定时例行任务？帮我推荐 2 个方案',
    },
    {
      icon: 'file',
      title: '写入第一份资料库笔记',
      desc: '在专属资料库中建立工作规范或模板',
      prompt: '请在后台帮我在你的资料库里建立一份 README.md，整理你的常用工作模板与输出规范',
    },
  ]
})

function triggerStarter(card: StarterCard) {
  if (props.hasConfiguredModel && !props.sending) {
    emit('send', { content: card.prompt, quoteSeq: null })
  } else {
    draft.value = card.prompt
    void nextTick(() => inputRef.value?.focus())
  }
}

onMounted(() => {
  tickTimer = window.setInterval(() => {
    if (activeTasks.value.length > 0) nowMs.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  clearInterval(tickTimer)
  clearTimeout(searchTimer)
  clearTimeout(highlightTimer)
})
</script>

<template>
  <section
    class="bot-chat-pane"
    aria-label="Bot 对话区"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div v-if="draggingFiles" class="bot-drop-overlay" aria-hidden="true">
      <UiIcon name="file" :size="24" />
      <span>松开鼠标，作为图片或文件附件发给 {{ currentCard?.bot.name || 'Bot' }}</span>
    </div>

    <header class="bot-chat-header" :class="{ 'is-rightmost': !detailOpen || detailDrawerMode }">
      <div class="bot-chat-header-left">
        <button
          v-if="compactLayout"
          type="button"
          class="icon-button"
          title="展开 Bot 列表"
          aria-label="展开 Bot 列表"
          @click="emit('toggleRoster')"
        >
          <UiIcon name="users" :size="16" />
        </button>
        <button
          v-if="backJump"
          type="button"
          class="bot-back-jump-pill"
          :title="`返回 @${backJump.botName}`"
          @click="emit('jumpBack')"
        >
          ← 返回 @{{ backJump.botName }}
        </button>
        <template v-if="currentCard">
          <BotLookAvatar
            :shape="currentCard.bot.shape"
            :color="currentCard.bot.color"
            :avatar-url="currentCard.avatarUrl"
            :outfit-json="currentCard.bot.outfitJson"
            :size="28"
            :status="currentCard.status"
            :progress="currentCard.experience.progress"
            show-ring
          />
          <div class="bot-chat-ident">
            <strong class="bot-chat-name">{{ currentCard.bot.name }}</strong>
            <span class="bot-chat-role">{{ currentCard.bot.roleSummary || '随时待命' }}</span>
          </div>
        </template>
      </div>
      <DynamicIsland
        :card="currentCard"
        :all-bots="allBots"
        :notice="islandNotice"
        :recent-notices="recentNotices"
        :version="thread?.version"
        @open-task="openTaskFromIsland"
        @cancel-task="emit('cancelTask', $event)"
        @select-bot="emit('selectBot', $event)"
        @dismiss-notice="emit('dismissNotice')"
      />
      <div class="bot-chat-header-right">
        <button
          type="button"
          class="icon-button"
          :class="{ active: searchOpen }"
          title="搜索本会话历史消息"
          aria-label="搜索聊天记录"
          @click="toggleChatSearch"
        >
          <UiIcon name="search" :size="15" />
        </button>
        <button
          type="button"
          class="bot-panel-toggle"
          :class="{ active: detailOpen }"
          :aria-pressed="detailOpen"
          title="展开或收起右侧工牌与详情面板"
          @click="emit('toggleDetail')"
        >
          <UiIcon name="panel" :size="15" />
          <span class="bot-panel-toggle-label">详情</span>
        </button>
      </div>
    </header>

    <div v-if="searchOpen" class="bot-chat-search-bar" role="search">
      <UiIcon name="search" :size="14" />
      <input
        ref="searchInputRef"
        v-model="searchQuery"
        type="search"
        placeholder="搜索本会话聊天记录…"
        @keydown.enter.prevent="stepSearch(1)"
        @keydown.esc.prevent="closeChatSearch"
      />
      <span v-if="searchQuery.trim()" class="bot-chat-search-count">
        <template v-if="searching">搜索中…</template>
        <template v-else-if="!searchResults.length">无匹配</template>
        <template v-else>{{ searchIndex + 1 }} / {{ searchResults.length }}</template>
      </span>
      <button
        type="button"
        class="icon-button"
        :disabled="!searchResults.length"
        title="上一条"
        @click="stepSearch(-1)"
      >
        <UiIcon name="chevron" :size="12" style="transform: rotate(-90deg)" />
      </button>
      <button
        type="button"
        class="icon-button"
        :disabled="!searchResults.length"
        title="下一条"
        @click="stepSearch(1)"
      >
        <UiIcon name="chevron" :size="12" style="transform: rotate(90deg)" />
      </button>
      <button type="button" class="icon-button" title="关闭搜索" @click="closeChatSearch">
        <UiIcon name="close" :size="13" />
      </button>
    </div>

    <div ref="timelineRef" class="bot-timeline" @scroll="onTimelineScroll">
      <div v-if="!thread" class="bot-empty-thread">
        <span class="loading-indicator"></span>
        <p>正在加载对话…</p>
      </div>
      <template v-else>
        <div v-if="!thread.entries.length && !thread.streaming" class="bot-welcome">
          <BotLookAvatar
            :shape="thread.card.bot.shape"
            :color="thread.card.bot.color"
            :avatar-url="thread.card.avatarUrl"
            :outfit-json="thread.card.bot.outfitJson"
            :size="60"
            :progress="thread.card.experience.progress"
            show-ring
          />
          <h3>你好，我是 {{ thread.card.bot.name }}</h3>
          <p class="bot-welcome-sub">{{ thread.card.bot.roleSummary || '随时把事情交给我，需要动手的活我会交给后台任务去做。' }}</p>
          <div class="bot-starter-grid">
            <button
              v-for="card in starterCards"
              :key="card.title"
              type="button"
              class="bot-starter-card"
              @click="triggerStarter(card)"
            >
              <span class="bot-starter-icon"><UiIcon :name="card.icon" :size="16" /></span>
              <span class="bot-starter-copy">
                <strong>{{ card.title }}</strong>
                <small>{{ card.desc }}</small>
              </span>
              <UiIcon class="bot-starter-arrow" name="chevron" :size="13" />
            </button>
          </div>
        </div>

        <template v-for="(entry, idx) in thread.entries" :key="entry.id">
          <div
            v-if="shouldShowTimeDivider(idx > 0 ? thread.entries[idx - 1].createdAt : null, entry.createdAt)"
            class="bot-time-divider"
          >
            <span>{{ formatTimeDivider(entry.createdAt) }}</span>
          </div>

          <article
            :data-entry-seq="entry.seq"
            class="bot-msg"
            :class="[
              `is-${entry.kind.toLowerCase()}`,
              {
                'is-selecting': selecting,
                'is-picked': selectedSeqs.includes(entry.seq),
                'is-search-hit': highlightedEntrySeq === entry.seq,
              },
            ]"
            @click="selecting && toggleSelected(entry.seq)"
          >
            <input
              v-if="selecting"
              type="checkbox"
              class="bot-select-check"
              :checked="selectedSeqs.includes(entry.seq)"
              @click.stop="toggleSelected(entry.seq)"
            />
            <div v-if="entry.kind === 'USER'" class="bot-user-bubble">
              <div v-if="entry.quoteSeq && entry.quotePreview" class="bot-quote-ref">
                <span>回复 #{{ entry.quoteSeq }}</span>
                <p>{{ entry.quotePreview }}</p>
              </div>
              <div v-if="parseAttachments(entry.attachmentsJson).length" class="bot-sent-attachments">
                <template v-for="att in parseAttachments(entry.attachmentsJson)" :key="att.id">
                  <button
                    v-if="isImageMediaType(att.mediaType)"
                    type="button"
                    class="bot-att-image-btn"
                    @click.stop="lightbox = { url: botApi.attachmentUrl(att.id), name: att.name }"
                  >
                    <img :src="botApi.attachmentUrl(att.id)" :alt="att.name" loading="lazy" />
                  </button>
                  <span v-else class="bot-att-file-pill" :title="att.libraryPath ? `资料库路径：${att.libraryPath}` : att.name">
                    <UiIcon name="file" :size="13" />
                    <span>{{ att.name }}</span>
                  </span>
                </template>
              </div>
              <p v-if="entry.content" class="bot-user-text">{{ entry.content }}</p>
              <div v-if="!selecting" class="bot-msg-meta is-hoverable">
                <small>{{ formatTime(entry.createdAt) }}</small>
                <button type="button" class="bot-msg-action" @click="copyEntry(entry)">
                  {{ copiedEntryId === entry.id ? '已复制' : '复制' }}
                </button>
                <button type="button" class="bot-msg-action" @click="quoting = entry">引用</button>
                <button type="button" class="bot-msg-action" @click="enterSelection(entry.seq)">多选</button>
                <button type="button" class="bot-msg-action is-danger" @click="requestDelete([entry.seq])">删除</button>
              </div>
            </div>

            <div v-else-if="entry.kind === 'SYSTEM'" class="bot-system-note">
              <UiIcon name="warning" :size="14" />
              <span>{{ entry.content }}</span>
            </div>

            <div v-else-if="entry.kind === 'SKILL_OFFER'" class="bot-assistant-row">
              <BotLookAvatar
                :shape="thread.card.bot.shape"
                :color="thread.card.bot.color"
                :avatar-url="thread.card.avatarUrl"
                :outfit-json="thread.card.bot.outfitJson"
                :size="30"
              />
              <div class="bot-assistant-body is-skill-offer">
                <BotSkillOfferCard
                  v-if="parseSkillOffer(entry.attachmentsJson)"
                  :offer="parseSkillOffer(entry.attachmentsJson)!"
                  :current-bot-id="thread.card.bot.id"
                  :busy="answeringOfferId === parseSkillOffer(entry.attachmentsJson)!.offerId"
                  @answer="(id, names) => emit('answerSkillOffer', id, names)"
                  @view-files="(id, name) => emit('viewOfferSkillFiles', id, name)"
                />
                <p v-else class="bot-system-note">{{ entry.content }}</p>
              </div>
            </div>

            <div v-else class="bot-assistant-row">
              <BotLookAvatar
                :shape="thread.card.bot.shape"
                :color="thread.card.bot.color"
                :avatar-url="thread.card.avatarUrl"
                :outfit-json="thread.card.bot.outfitJson"
                :size="30"
              />
              <div class="bot-assistant-body" :class="{ 'is-task-result': entry.kind === 'TASK_RESULT' }">
                <div class="bot-assistant-head">
                  <strong>{{ entry.kind === 'TASK_RESULT' ? '后台任务汇报' : thread.card.bot.name }}</strong>
                  <span v-if="entry.modelLabel" class="bot-model-pill">{{ entry.modelLabel }}</span>
                  <span v-if="entry.fallbackFrom" class="bot-fallback-pill" :title="`原模型 ${entry.fallbackFrom} 不可用，已回退`">
                    回退自 {{ entry.fallbackFrom }}
                  </span>
                  <small>{{ formatTime(entry.createdAt) }}</small>
                </div>
                <BotMarkdownContent
                  :content="entry.content"
                  :bot-id="thread.card.bot.id"
                  :bots="allBots"
                  :playbooks="thread.playbooks"
                  :apps="thread.apps"
                  :raw-file-url="botApi.rawFileUrl"
                  @open-bot="emit('jumpToMention', $event, timelineRef?.scrollTop ?? 0)"
                  @open-flow="emit('openPlaybook', $event)"
                  @open-skill="emit('openSkill', $event)"
                  @open-app="emit('openApp', $event)"
                  @open-file="emit('openFile', thread.card.bot.id, $event)"
                  @open-image="(url, name) => lightbox = { url, name }"
                />
                <div v-if="extractBotApps(entry.content).length" class="bot-app-cards is-thumbs">
                  <MiniAppCard
                    v-for="appRef in extractBotApps(entry.content).slice(0, 2)"
                    :key="appRef.appId"
                    :app-id="appRef.appId"
                    :fallback-name="appRef.name"
                    :app="thread.apps.find(a => a.id === appRef.appId) || null"
                    @open="emit('openApp', $event)"
                  />
                </div>
                <div
                  v-if="!selecting && entry.seq === lastEntrySeq && parseSuggestions(entry.suggestionsJson).length"
                  class="bot-suggestion-chips"
                >
                  <button
                    v-for="sug in parseSuggestions(entry.suggestionsJson)"
                    :key="sug"
                    type="button"
                    class="bot-suggestion-chip"
                    @click.stop="fillSuggestion(sug)"
                  >{{ sug }}</button>
                </div>
                <div v-if="!selecting" class="bot-msg-meta is-hoverable" :class="{ 'is-pinned-meta': !!entry.taskId }">
                  <button v-if="entry.taskId" type="button" class="bot-msg-action is-accent" @click="emit('openTask', entry.taskId)">
                    查看任务过程
                  </button>
                  <button type="button" class="bot-msg-action" @click="copyEntry(entry)">
                    {{ copiedEntryId === entry.id ? '已复制' : '复制' }}
                  </button>
                  <button type="button" class="bot-msg-action" @click="quoting = entry">引用</button>
                  <button type="button" class="bot-msg-action" @click="enterSelection(entry.seq)">多选</button>
                  <button type="button" class="bot-msg-action is-danger" @click="requestDelete([entry.seq])">删除</button>
                </div>
              </div>
            </div>
          </article>
        </template>

        <article v-if="thread.streaming" class="bot-msg is-assistant">
          <div class="bot-assistant-row">
            <BotLookAvatar
              :shape="thread.card.bot.shape"
              :color="thread.card.bot.color"
              :avatar-url="thread.card.avatarUrl"
              :outfit-json="thread.card.bot.outfitJson"
              :size="30"
              status="REPLYING"
            />
            <div class="bot-assistant-body is-streaming">
              <div class="bot-assistant-head">
                <strong>{{ thread.card.bot.name }}</strong>
                <span class="bot-streaming-tag">正在流式回复…</span>
              </div>
              <BotMarkdownContent
                v-if="thread.streaming.output"
                :content="thread.streaming.output"
                :bot-id="thread.card.bot.id"
                :bots="allBots"
                :playbooks="thread.playbooks"
                :apps="thread.apps"
                :raw-file-url="botApi.rawFileUrl"
                @open-bot="emit('jumpToMention', $event, timelineRef?.scrollTop ?? 0)"
                @open-flow="emit('openPlaybook', $event)"
                @open-skill="emit('openSkill', $event)"
                @open-app="emit('openApp', $event)"
                @open-file="emit('openFile', thread.card.bot.id, $event)"
                @open-image="(url, name) => lightbox = { url, name }"
              />
              <p v-else class="bot-thinking-dots">思考与调度中…</p>
            </div>
          </div>
        </article>

        <div v-if="activeTasks.length" class="bot-inline-task-progress" role="status">
          <BotLookAvatar
            :shape="thread.card.bot.shape"
            :color="thread.card.bot.color"
            :avatar-url="thread.card.avatarUrl"
            :outfit-json="thread.card.bot.outfitJson"
            :size="26"
            status="WORKING"
          />
          <div class="bot-inline-task-list">
            <div class="bot-inline-task-head">
              <span class="bot-shimmer-text">后台正在执行 {{ activeTasks.length }} 项任务</span>
            </div>
            <div v-for="task in activeTasks" :key="task.id" class="bot-inline-task-item">
              <button type="button" class="bot-inline-task-open" @click="emit('openTask', task.id)">
                <strong>{{ task.title }}</strong>
                <span class="bot-inline-task-stage">
                  {{ task.status === 'QUEUED' ? '排队等待并发槽位…' : (task.statusText || '后台执行中…') }}
                </span>
                <small>{{ formatElapsed(task.createdAt, nowMs) }}</small>
              </button>
              <button type="button" class="bot-inline-task-stop" @click="emit('cancelTask', task.id)">停止</button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <div class="bot-composer-wrap">
      <button
        v-if="!autoScroll && thread && thread.entries.length > 0"
        type="button"
        class="bot-scroll-latest"
        @click="jumpToLatest"
      >
        回到最新 ↓
      </button>

      <div v-if="pendingApprovals.length" class="bot-inline-approvals" role="region" aria-label="待审批的后台命令">
        <div v-for="item in pendingApprovals" :key="item.commandId" class="bot-approval-card">
          <div class="bot-approval-head">
            <span class="bot-approval-badge">
              <UiIcon name="shield" :size="13" />
              {{ item.status === 'REVIEWING' ? '智能审批中' : '等待命令审批' }}
            </span>
            <strong>{{ item.taskTitle }}</strong>
            <button type="button" class="bot-link-btn" @click="emit('openSession', item.sessionId)">查看会话</button>
          </div>
          <code class="bot-approval-cmd">{{ item.commandText }}</code>
          <p v-if="item.purpose" class="bot-approval-purpose">{{ item.purpose }}</p>
          <div class="bot-approval-actions">
            <button type="button" class="bot-primary-btn" @click="emit('approveCommand', item.commandId)">批准执行</button>
            <button type="button" class="bot-ghost-btn is-danger" @click="emit('rejectCommand', item.commandId)">拒绝</button>
          </div>
        </div>
      </div>

      <div v-if="thread?.queued.length" class="bot-queued-banner">
        <strong>排队中（{{ thread.queued.length }} 条，当前回复结束后一并读取）</strong>
        <ul>
          <li v-for="item in thread.queued" :key="item.id">{{ item.content || '（含附件）' }}</li>
        </ul>
      </div>

      <div v-if="viewingApp && viewingApp.botId === currentCard?.bot.id" class="bot-viewing-app-bar">
        <span class="miniapp-tile-dot" :style="{ background: viewingApp.color || '#2d5d9c' }">
          <UiIcon :name="miniAppIconName(viewingApp.icon)" :size="10" />
        </span>
        <span>正在查看小程序<strong>《{{ viewingApp.name }}》v{{ viewingApp.version }}</strong> · 聊天消息会自动附带此上下文</span>
        <button type="button" class="icon-button" title="关闭小程序窗口" @click="emit('closeViewingApp')">
          <UiIcon name="close" :size="13" />
        </button>
      </div>

      <div v-if="quoting" class="bot-quote-bar">
        <div>
          <strong>引用 #{{ quoting.seq }}</strong>
          <span>{{ quoting.content.slice(0, 100) }}</span>
        </div>
        <button type="button" class="icon-button" aria-label="取消引用" @click="quoting = null">
          <UiIcon name="close" :size="14" />
        </button>
      </div>

      <p v-if="uploadError" class="bot-upload-error" role="alert">
        <span>{{ uploadError }}</span>
        <button type="button" @click="uploadError = ''">关闭</button>
      </p>

      <div v-if="pendingAttachments.length" class="bot-pending-attachments">
        <div v-for="att in pendingAttachments" :key="att.id" class="bot-pending-att">
          <img
            v-if="isImageMediaType(att.mediaType)"
            :src="botApi.attachmentUrl(att.id)"
            :alt="att.name"
            class="bot-pending-thumb"
          />
          <span v-else class="bot-pending-file">
            <UiIcon name="file" :size="14" />
            <span>{{ att.name }}</span>
          </span>
          <button
            type="button"
            class="bot-pending-remove"
            :aria-label="`移除 ${att.name}`"
            @click="removePendingAttachment(att.id)"
          >
            <UiIcon name="close" :size="11" />
          </button>
        </div>
      </div>

      <div v-if="mentionCandidates.length" class="bot-mention-menu" role="listbox" aria-label="选择要提及的 Bot">
        <button
          v-for="(card, idx) in mentionCandidates"
          :key="card.bot.id"
          type="button"
          class="bot-mention-option"
          :class="{ active: idx === mentionIndex }"
          @mousedown.prevent="pickMention(card)"
        >
          <BotLookAvatar
            :shape="card.bot.shape"
            :color="card.bot.color"
            :avatar-url="card.avatarUrl"
            :outfit-json="card.bot.outfitJson"
            :size="22"
          />
          <div>
            <strong>{{ card.bot.name }}</strong>
            <small>{{ card.bot.roleSummary }}</small>
          </div>
        </button>
      </div>

      <p v-if="!hasConfiguredModel && !selecting" class="bot-model-hint">
        <span>连接一个模型后，即可与 Bot 对话并派发后台任务</span>
        <button type="button" @click="emit('openModels')">配置模型 <UiIcon name="chevron" :size="12" /></button>
      </p>

      <div v-if="selecting" class="bot-select-bar">
        <strong>已选 {{ selectedSeqs.length }} 条</strong>
        <button type="button" @click="selectAllEntries">全选</button>
        <button type="button" :disabled="!selectedSeqs.length" @click="copySelected">{{ copyFlash || '复制' }}</button>
        <button type="button" class="is-primary" :disabled="!selectedSeqs.length" @click="openShareSelected">生成分享图</button>
        <button type="button" class="is-danger" :disabled="!selectedSeqs.length" @click="requestDelete(selectedSeqs)">删除</button>
        <button type="button" @click="exitSelection">取消</button>
      </div>

      <form v-else class="bot-composer" @submit.prevent="submit">
        <input ref="fileInputRef" type="file" multiple hidden @change="onFilePicked" />
        <textarea
          ref="inputRef"
          v-model="draft"
          rows="2"
          :placeholder="isReplying ? '正在回复中，输入内容即可排队发送；清空输入框可点击停止…' : '给 Bot 发消息，支持粘贴/拖入图片和文件，输入 @ 提及成员（Enter 发送）'"
          @keydown="onKeydown"
          @paste="onPaste"
        ></textarea>
        <div class="bot-composer-actions">
          <button
            type="button"
            class="icon-button bot-composer-tool"
            :disabled="uploading"
            title="附加图片或文本文件（也支持直接粘贴截图或拖入）"
            aria-label="添加附件"
            @click="fileInputRef?.click()"
          >
            <UiIcon name="plus" :size="15" />
          </button>
          <button
            type="button"
            class="icon-button bot-composer-tool"
            title="手动派发一个后台任务"
            aria-label="手动派发后台任务"
            @click="emit('openManualTask')"
          >
            <UiIcon name="terminal" :size="15" />
          </button>
          <button
            v-if="showStopAction"
            type="button"
            class="bot-stop-btn"
            title="停止本轮回复（已输出内容会保留）"
            @click="onStopClick"
          >
            <UiIcon name="stop" :size="14" />
            <span>停止</span>
          </button>
          <button
            v-else
            type="submit"
            class="bot-send-btn"
            :disabled="!canSend"
            :title="isReplying ? '加入排队发送' : '发送消息'"
          >
            <UiIcon name="arrow" :size="16" />
          </button>
        </div>
      </form>
    </div>

    <div v-if="lightbox" class="bot-modal-scrim" @click.self="lightbox = null">
      <div class="bot-lightbox">
        <header>
          <span>{{ lightbox.name }}</span>
          <button type="button" class="icon-button" aria-label="关闭大图" @click="lightbox = null">
            <UiIcon name="close" :size="16" />
          </button>
        </header>
        <img :src="lightbox.url" :alt="lightbox.name" />
      </div>
    </div>

    <div v-if="confirmingDeleteSeqs" class="bot-modal-scrim" @click.self="confirmingDeleteSeqs = null">
      <div class="bot-modal">
        <header>
          <h3>确认删除 {{ confirmingDeleteSeqs.length }} 条消息？</h3>
          <button type="button" class="icon-button" @click="confirmingDeleteSeqs = null"><UiIcon name="close" :size="16" /></button>
        </header>
        <p class="bot-confirm-copy">删除后该消息不再参与后续对话上下文，此操作不可撤销。</p>
        <div class="bot-form-actions">
          <button type="button" @click="confirmingDeleteSeqs = null">取消</button>
          <button type="button" class="is-danger" @click="confirmDelete">确认删除</button>
        </div>
      </div>
    </div>
  </section>
</template>

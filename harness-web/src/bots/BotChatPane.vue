<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MarkdownContent from '../MarkdownContent.vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import DynamicIsland from './DynamicIsland.vue'
import {
  activeMentionQuery,
  extractBotApps,
  extractBotMentions,
  parseSuggestions,
  renderBotMarkdownContent,
  type BotCard,
  type BotEntry,
  type BotTask,
  type BotThreadSnapshot,
  type IslandNotice,
} from './api'

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
}>()

const emit = defineEmits<{
  send: [payload: { content: string; quoteSeq: number | null }]
  stop: []
  deleteEntries: [seqs: number[]]
  selectBot: [botId: string]
  openTask: [taskId: string]
  cancelTask: [taskId: string]
  toggleDetail: []
  toggleRoster: []
  openManualTask: []
  openModels: []
  shareEntries: [entries: BotEntry[]]
  openApp: [appId: string]
}>()

const draft = ref('')
const inputRef = ref<HTMLTextAreaElement>()
const timelineRef = ref<HTMLElement>()
const quoting = ref<BotEntry | null>(null)
const mentionIndex = ref(0)
const selecting = ref(false)
const selectedSeqs = ref<number[]>([])
const copyFlash = ref('')

const botById = computed(() => new Map(props.allBots.map(card => [card.bot.id, card])))
const currentCard = computed(() => props.thread?.card || null)
const isReplying = computed(() => !!props.thread?.streaming || currentCard.value?.status === 'REPLYING')
const lastEntrySeq = computed(() => props.thread?.entries.at(-1)?.seq ?? -1)
const selectedEntries = computed(() =>
  (props.thread?.entries || []).filter(entry => selectedSeqs.value.includes(entry.seq)),
)

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

watch(() => props.thread?.card.bot.id, () => {
  quoting.value = null
  selecting.value = false
  selectedSeqs.value = []
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

function deleteSelected() {
  if (!selectedSeqs.value.length) return
  emit('deleteEntries', [...selectedSeqs.value])
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

watch(() => props.thread?.entries.length, () => {
  void scrollToBottom()
})

watch(() => props.thread?.streaming?.output, () => {
  void scrollToBottom()
})

async function scrollToBottom() {
  await nextTick()
  const el = timelineRef.value
  if (el) el.scrollTop = el.scrollHeight
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

function submit() {
  const content = draft.value.trim()
  if (!content || props.sending) return
  const quoteSeq = quoting.value?.seq ?? null
  draft.value = ''
  quoting.value = null
  emit('send', { content, quoteSeq })
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
        title: '体验后台写资料库',
        desc: '在专属资料库建一份协作指南并回来汇报',
        prompt: '请在后台帮我在资料库里建一份 hello.md 笔记，总结今天我们可以怎么高效协作',
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
</script>

<template>
  <section class="bot-chat-pane" aria-label="Bot 对话区">
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
        <template v-if="currentCard">
          <BotLookAvatar
            :shape="currentCard.bot.shape"
            :color="currentCard.bot.color"
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
        :notice="islandNotice"
        :recent-notices="recentNotices"
        @open-task="openTaskFromIsland"
        @cancel-task="emit('cancelTask', $event)"
        @select-bot="emit('selectBot', $event)"
      />
      <div class="bot-chat-header-right">
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

    <div ref="timelineRef" class="bot-timeline">
      <div v-if="!thread" class="bot-empty-thread">
        <span class="loading-indicator"></span>
        <p>正在加载对话…</p>
      </div>
      <template v-else>
        <div v-if="!thread.entries.length && !thread.streaming" class="bot-welcome">
          <BotLookAvatar
            :shape="thread.card.bot.shape"
            :color="thread.card.bot.color"
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

        <article
          v-for="entry in thread.entries"
          :key="entry.id"
          class="bot-msg"
          :class="[`is-${entry.kind.toLowerCase()}`, { 'is-selecting': selecting, 'is-picked': selectedSeqs.includes(entry.seq) }]"
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
            <p class="bot-user-text">{{ entry.content }}</p>
            <div v-if="!selecting" class="bot-msg-meta">
              <small>{{ formatTime(entry.createdAt) }}</small>
              <button type="button" class="bot-msg-action" @click="quoting = entry">引用</button>
              <button type="button" class="bot-msg-action" @click="enterSelection(entry.seq)">多选</button>
              <button type="button" class="bot-msg-action is-danger" @click="emit('deleteEntries', [entry.seq])">删除</button>
            </div>
          </div>

          <div v-else-if="entry.kind === 'SYSTEM'" class="bot-system-note">
            <UiIcon name="warning" :size="14" />
            <span>{{ entry.content }}</span>
          </div>

          <div v-else class="bot-assistant-row">
            <BotLookAvatar
              :shape="thread.card.bot.shape"
              :color="thread.card.bot.color"
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
              <MarkdownContent :content="renderBotMarkdownContent(entry.content)" />
              <div v-if="extractBotApps(entry.content).length" class="bot-app-cards">
                <button
                  v-for="appRef in extractBotApps(entry.content)"
                  :key="appRef.appId"
                  type="button"
                  class="bot-app-card"
                  @click.stop="emit('openApp', appRef.appId)"
                >
                  <span class="bot-app-card-icon"><UiIcon name="cube" :size="16" /></span>
                  <span class="bot-app-card-copy">
                    <strong>{{ appRef.name }}</strong>
                    <small>
                      {{ thread.apps.find(a => a.id === appRef.appId)?.description || '点击打开交互小程序' }}
                    </small>
                  </span>
                  <span class="bot-app-card-ver">
                    v{{ thread.apps.find(a => a.id === appRef.appId)?.version || 1 }}
                  </span>
                </button>
              </div>
              <div v-if="extractBotMentions(entry.content).length" class="bot-mention-chips">
                <button
                  v-for="m in extractBotMentions(entry.content)"
                  :key="m.botId"
                  type="button"
                  class="bot-mention-chip"
                  @click.stop="emit('selectBot', m.botId)"
                >
                  <BotLookAvatar
                    v-if="botById.get(m.botId)"
                    :shape="botById.get(m.botId)!.bot.shape"
                    :color="botById.get(m.botId)!.bot.color"
                    :size="18"
                  />
                  <span>@{{ m.name }}</span>
                </button>
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
              <div v-if="!selecting" class="bot-msg-meta">
                <button v-if="entry.taskId" type="button" class="bot-msg-action is-accent" @click="emit('openTask', entry.taskId)">
                  查看任务过程
                </button>
                <button type="button" class="bot-msg-action" @click="quoting = entry">引用</button>
                <button type="button" class="bot-msg-action" @click="enterSelection(entry.seq)">多选</button>
                <button type="button" class="bot-msg-action is-danger" @click="emit('deleteEntries', [entry.seq])">删除</button>
              </div>
            </div>
          </div>
        </article>

        <article v-if="thread.streaming" class="bot-msg is-assistant">
          <div class="bot-assistant-row">
            <BotLookAvatar :shape="thread.card.bot.shape" :color="thread.card.bot.color" :size="30" status="REPLYING" />
            <div class="bot-assistant-body is-streaming">
              <div class="bot-assistant-head">
                <strong>{{ thread.card.bot.name }}</strong>
                <span class="bot-streaming-tag">正在回复…</span>
              </div>
              <MarkdownContent v-if="thread.streaming.output" :content="renderBotMarkdownContent(thread.streaming.output)" />
              <p v-else class="bot-thinking-dots">思考与调度中…</p>
            </div>
          </div>
        </article>
      </template>
    </div>

    <div class="bot-composer-wrap">
      <div v-if="thread?.queued.length" class="bot-queued-banner">
        <strong>排队中（{{ thread.queued.length }} 条，当前回复结束后一并读取）</strong>
        <ul>
          <li v-for="item in thread.queued" :key="item.id">{{ item.content }}</li>
        </ul>
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

      <div v-if="mentionCandidates.length" class="bot-mention-menu" role="listbox" aria-label="选择要提及的 Bot">
        <button
          v-for="(card, idx) in mentionCandidates"
          :key="card.bot.id"
          type="button"
          class="bot-mention-option"
          :class="{ active: idx === mentionIndex }"
          @mousedown.prevent="pickMention(card)"
        >
          <BotLookAvatar :shape="card.bot.shape" :color="card.bot.color" :size="22" />
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
        <button type="button" class="is-danger" :disabled="!selectedSeqs.length" @click="deleteSelected">删除</button>
        <button type="button" @click="exitSelection">取消</button>
      </div>

      <form v-else class="bot-composer" @submit.prevent="submit">
        <textarea
          ref="inputRef"
          v-model="draft"
          rows="2"
          :placeholder="isReplying ? '正在回复中，继续输入将自动排队等待本轮结束…' : '给 Bot 发消息，输入 @ 提及团队成员（Enter 发送，Shift+Enter 换行）'"
          @keydown="onKeydown"
        ></textarea>
        <div class="bot-composer-actions">
          <button
            type="button"
            class="icon-button bot-composer-tool"
            title="手动派发一个后台任务"
            aria-label="手动派发后台任务"
            @click="emit('openManualTask')"
          >
            <UiIcon name="terminal" :size="15" />
          </button>
          <button v-if="isReplying" type="button" class="bot-stop-btn" @click="emit('stop')">
            <UiIcon name="stop" :size="14" />
            <span>停止</span>
          </button>
          <button type="submit" class="bot-send-btn" :disabled="!draft.trim() || sending">
            <UiIcon name="arrow" :size="16" />
          </button>
        </div>
      </form>
    </div>
  </section>
</template>

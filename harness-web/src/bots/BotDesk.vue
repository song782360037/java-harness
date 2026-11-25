<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { resolvedTheme } from '../theme'
import { deskIdleActivity, renderBotAvatarInner, type BotCard, type DeskIdleActivity } from './api'

const props = defineProps<{
  card: BotCard
  playbookCount: number
  routineCount: number
  fileCount: number
  appCount: number
  daysOnboard: number
}>()

const emit = defineEmits<{
  jumpTab: [tab: 'activity' | 'playbooks' | 'apps' | 'files']
  flipBack: []
}>()

const now = ref(Date.now())
const poked = ref(false)
const speech = ref('')
let clockTimer: number | undefined
let pokeTimer: number | undefined

const isBusy = computed(() => props.card.status === 'WORKING' || props.card.status === 'REPLYING')
const isFailed = computed(() => props.card.status === 'FAILED')
const idleMode = computed<DeskIdleActivity>(() => deskIdleActivity(props.card.bot.id, now.value))
const avatarMarkup = computed(() => renderBotAvatarInner(props.card.bot.shape, props.card.bot.color))
const hour = computed(() => new Date(now.value).getHours())
const isDark = computed(() => resolvedTheme.value === 'dark')
const wallColor = computed(() => (isDark.value ? '#1b221e' : '#f3f6f4'))
const frameColor = computed(() => (isDark.value ? '#37443d' : '#cfd8d1'))
const deskWoodColor = computed(() => (isDark.value ? '#4a3a2d' : '#d7c3a8'))
const deskEdgeColor = computed(() => (isDark.value ? '#3a2d23' : '#c5ab8b'))
const paperColor = computed(() => (isDark.value ? '#2a332e' : '#ffffff'))
const paperLineColor = computed(() => (isDark.value ? '#44524b' : '#d9e1db'))
const skyColor = computed(() => {
  if (isDark.value) return '#152035'
  if (hour.value >= 6 && hour.value < 17) return '#cde7f5'
  if (hour.value >= 17 && hour.value < 20) return '#f6c39c'
  return '#1f2b40'
})
const plantStage = computed(() => Math.min(4, Math.max(1, Math.floor(props.daysOnboard / 5) + 1)))

const statusCaption = computed(() => {
  if (poked.value) return '正在看报表！绝对没摸鱼！'
  if (isFailed.value) return '任务出状况了，正在抓头发排障…'
  if (isBusy.value) return props.card.activeTasks[0]?.title || '戴着耳机专心敲键盘…'
  switch (idleMode.value) {
    case 'drama': return '没活干，正在偷偷刷短剧…'
    case 'gaming': return '没活干，正在工位打掌机…'
    case 'window': return '没活干，正托腮望窗外发呆…'
    default: return '没活干，正在工位小憩打盹…'
  }
})

function pokeBot() {
  poked.value = true
  speech.value = isBusy.value
    ? '收到！手头这活马上就好～'
    : isFailed.value
      ? '刚才那单卡壳了，帮我看看报错吧！'
      : '哎呀别戳！我这就切回工作报表！'
  clearTimeout(pokeTimer)
  pokeTimer = window.setTimeout(() => {
    poked.value = false
    speech.value = ''
  }, 3600)
}

onMounted(() => {
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 30_000)
})

onUnmounted(() => {
  clearInterval(clockTimer)
  clearTimeout(pokeTimer)
})
</script>

<template>
  <div class="bot-desk">
    <div v-if="speech" class="bot-desk-speech">{{ speech }}</div>
    <svg class="bot-desk-svg" viewBox="0 0 280 164" role="img" :aria-label="`${card.bot.name} 的工位`">
      <!-- Room wall & window -->
      <rect x="0" y="0" width="280" height="164" rx="12" :fill="wallColor" class="desk-wall" />
      <rect x="14" y="14" width="66" height="52" rx="6" :fill="skyColor" :stroke="frameColor" stroke-width="2" />
      <circle v-if="!isDark && hour >= 6 && hour < 19" cx="30" cy="28" r="6" fill="#fde68a" />
      <circle v-else cx="62" cy="26" r="5" fill="#fef3c7" />
      <circle v-if="isDark" cx="28" cy="25" r="1.2" fill="#e2e8f0" fill-opacity="0.7" />
      <circle v-if="isDark" cx="40" cy="32" r="1" fill="#e2e8f0" fill-opacity="0.55" />
      <line x1="47" y1="14" x2="47" y2="66" :stroke="frameColor" stroke-width="1.5" />
      <line x1="14" y1="40" x2="80" y2="40" :stroke="frameColor" stroke-width="1.5" />

      <!-- Bookshelf (Playbooks) -->
      <g class="desk-hotspot" @click="emit('jumpTab', 'playbooks')">
        <title>书架：{{ playbookCount }} 条做法（点击查看）</title>
        <rect x="96" y="16" width="84" height="7" rx="2" fill="#b79674" />
        <rect
          v-for="i in Math.min(6, Math.max(1, playbookCount))"
          :key="i"
          :x="100 + (i - 1) * 12"
          :y="playbookCount === 0 ? 11 : 4"
          width="9"
          :height="playbookCount === 0 ? 5 : 12"
          rx="1.5"
          :fill="playbookCount === 0 ? '#d5ddd7' : (i % 2 === 0 ? card.bot.color : '#5b8a72')"
        />
      </g>

      <!-- Sticky notes (Routines) -->
      <g class="desk-hotspot" @click="emit('jumpTab', 'activity')">
        <title>便利贴：{{ routineCount }} 项例行任务（点击查看）</title>
        <rect x="196" y="16" width="24" height="24" rx="2" fill="#fef08a" stroke="#e5c749" stroke-width="1" />
        <rect v-if="routineCount > 1" x="224" y="20" width="22" height="22" rx="2" fill="#fed7aa" stroke="#e0a96d" stroke-width="1" />
        <text x="208" y="32" text-anchor="middle" font-size="10" font-weight="700" fill="#7c5d10">{{ routineCount }}</text>
      </g>

      <!-- Desk surface -->
      <rect x="10" y="114" width="260" height="40" rx="6" :fill="deskWoodColor" />
      <rect x="10" y="114" width="260" height="7" rx="3" :fill="deskEdgeColor" />

      <!-- Growing Potted Plant -->
      <g>
        <title>工位绿植（已入职第 {{ daysOnboard }} 天）</title>
        <rect x="22" y="98" width="18" height="16" rx="3" fill="#c47d5a" />
        <path
          :d="`M31 98 L31 ${98 - plantStage * 7}`"
          stroke="#2f7d57"
          stroke-width="2.5"
          stroke-linecap="round"
        />
        <ellipse cx="26" :cy="92 - plantStage * 4" rx="5" ry="3" fill="#3f9a70" transform="rotate(-25 26 88)" />
        <ellipse v-if="plantStage >= 2" cx="36" :cy="90 - plantStage * 5" rx="5" ry="3" fill="#2f7d57" transform="rotate(25 36 86)" />
        <circle v-if="plantStage >= 4" cx="31" :cy="95 - plantStage * 7" r="3" fill="#f59e0b" />
      </g>

      <!-- Paper stack (Library files) -->
      <g class="desk-hotspot" @click="emit('jumpTab', 'files')">
        <title>资料库纸堆：{{ fileCount }} 个文件（点击查看）</title>
        <rect x="218" y="102" width="34" height="12" rx="2" :fill="paperColor" :stroke="frameColor" stroke-width="1" />
        <line x1="220" y1="106" x2="250" y2="106" :stroke="paperLineColor" stroke-width="1" />
        <line x1="220" y1="110" x2="246" y2="110" :stroke="paperLineColor" stroke-width="1" />
      </g>

      <!-- Mini App phone stand -->
      <g class="desk-hotspot" @click="emit('jumpTab', 'apps')">
        <title>小程序支架：{{ appCount }} 个小程序（点击查看）</title>
        <rect x="192" y="92" width="18" height="24" rx="4" fill="#1e2420" />
        <rect x="195" y="96" width="12" height="16" rx="2" :fill="appCount > 0 ? '#6ee7b7' : '#3a463f'" />
      </g>

      <!-- Monitor -->
      <g>
        <rect x="92" y="68" width="76" height="44" rx="5" fill="#1b221e" />
        <rect x="96" y="72" width="68" height="34" rx="3" :fill="isFailed && !poked ? '#472121' : '#263830'" />
        <!-- Chart bars when working or poked -->
        <template v-if="isBusy || poked">
          <rect x="104" y="90" width="8" height="12" rx="1" fill="#6ee7b7" />
          <rect x="116" y="82" width="8" height="20" rx="1" fill="#93c5fd" />
          <rect x="128" y="86" width="8" height="16" rx="1" fill="#fde68a" />
          <rect x="140" y="78" width="8" height="24" rx="1" fill="#6ee7b7" />
        </template>
        <template v-else-if="isFailed">
          <text x="130" y="93" text-anchor="middle" font-size="14" font-weight="700" fill="#fca5a5">ERR!</text>
        </template>
        <template v-else>
          <text x="130" y="93" text-anchor="middle" font-size="10" fill="#9eb0a3">
            {{ idleMode === 'drama' ? '▶ 短剧中' : idleMode === 'gaming' ? '🎮 GG' : idleMode === 'nap' ? 'Zzz…' : '☁ 发呆' }}
          </text>
        </template>
        <rect x="124" y="112" width="12" height="4" fill="#3a453f" />
      </g>

      <!-- Bot character at desk (clickable to poke) -->
      <g class="desk-bot-char" :class="{ 'is-working': isBusy || poked, 'is-nap': !isBusy && !poked && idleMode === 'nap' }" @click="pokeBot">
        <title>点击戳一戳 {{ card.bot.name }}</title>
        <g transform="translate(106, 108) scale(1.0)" v-html="avatarMarkup" />
        <!-- Headphones when working -->
        <path v-if="isBusy || poked" d="M111 131a19 19 0 0 1 38 0" fill="none" stroke="#1b221e" stroke-width="3" />
        <rect v-if="isBusy || poked" x="108" y="128" width="5" height="9" rx="2" fill="#1b221e" />
        <rect v-if="isBusy || poked" x="147" y="128" width="5" height="9" rx="2" fill="#1b221e" />
        <!-- Sleep badge when napping -->
        <text v-if="!isBusy && !poked && idleMode === 'nap'" x="150" y="118" font-size="10" font-weight="700" fill="#64748b">Zzz</text>
      </g>
    </svg>
    <div class="bot-desk-foot">
      <span>{{ statusCaption }}</span>
      <button type="button" class="bot-desk-flip" @click="emit('flipBack')">翻回工牌</button>
    </div>
  </div>
</template>

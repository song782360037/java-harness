<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { resolvedColorScheme } from '../theme'
import {
  deskIdleActivity,
  parseOutfitJson,
  renderBotAvatarInner,
  renderOutfitOverlays,
  type BotCard,
  type DeskIdleActivity,
} from './api'

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
const dizzy = ref(false)
const pokeCount = ref(0)
const lampOn = ref(true)
const watering = ref(false)
const speech = ref('')
let clockTimer: number | undefined
let pokeTimer: number | undefined
let resetPokeTimer: number | undefined
let waterTimer: number | undefined

const isBusy = computed(() => props.card.status === 'WORKING' || props.card.status === 'REPLYING')
const isFailed = computed(() => props.card.status === 'FAILED')
const idleMode = computed<DeskIdleActivity>(() => deskIdleActivity(props.card.bot.id, now.value))
const tier = computed(() => props.card.experience.tier)
const tierRank = computed(() => {
  switch (tier.value) {
    case 'MASTER': return 5
    case 'EXPERT': return 4
    case 'SENIOR': return 3
    case 'ADEPT': return 2
    default: return 1
  }
})
const avatarMarkup = computed(() => {
  const outfit = parseOutfitJson(props.card.bot.outfitJson)
  return renderBotAvatarInner(props.card.bot.shape, props.card.bot.color) + renderOutfitOverlays(props.card.bot.shape, outfit)
})
const hour = computed(() => new Date(now.value).getHours())
const isDark = computed(() => resolvedColorScheme.value === 'dark')
const wallColor = computed(() => (isDark.value ? '#1b221e' : '#f3f6f4'))
const frameColor = computed(() => (isDark.value ? '#37443d' : '#cfd8d1'))
const deskWoodColor = computed(() => {
  if (tierRank.value >= 4) return isDark.value ? '#533b2a' : '#c69e74'
  return isDark.value ? '#4a3a2d' : '#d7c3a8'
})
const deskEdgeColor = computed(() => {
  if (tierRank.value >= 4) return isDark.value ? '#3e2a1d' : '#b2875c'
  return isDark.value ? '#3a2d23' : '#c5ab8b'
})
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
  if (dizzy.value) return '被连戳 5 下头好晕 @.@'
  if (poked.value) return '正在看报表！绝对没摸鱼！'
  if (isFailed.value) return '任务出状况了，正在抓头发排障…'
  if (isBusy.value) return props.card.activeTasks[0]?.statusText || props.card.activeTasks[0]?.title || '戴着耳机专心敲键盘…'
  switch (idleMode.value) {
    case 'drama': return '没活干，正在偷偷刷短剧…'
    case 'gaming': return '没活干，正在工位打掌机…'
    case 'window': return '没活干，正托腮望窗外想小鱼干…'
    default: return '没活干，正在工位小憩打盹…'
  }
})

function pokeBot() {
  pokeCount.value += 1
  clearTimeout(resetPokeTimer)
  resetPokeTimer = window.setTimeout(() => { pokeCount.value = 0 }, 2400)

  if (pokeCount.value >= 5) {
    dizzy.value = true
    poked.value = false
    pokeCount.value = 0
    speech.value = '哎呀别戳啦！转得头好晕 @.@'
    clearTimeout(pokeTimer)
    pokeTimer = window.setTimeout(() => {
      dizzy.value = false
      speech.value = ''
    }, 3400)
    return
  }

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
  }, 3200)
}

function waterPlant() {
  watering.value = true
  speech.value = '谢谢浇水，小绿植精神多了！'
  clearTimeout(waterTimer)
  waterTimer = window.setTimeout(() => {
    watering.value = false
    if (speech.value.startsWith('谢谢浇水')) speech.value = ''
  }, 2200)
}

function toggleLamp() {
  lampOn.value = !lampOn.value
}

onMounted(() => {
  clockTimer = window.setInterval(() => { now.value = Date.now() }, 30_000)
})

onUnmounted(() => {
  clearInterval(clockTimer)
  clearTimeout(pokeTimer)
  clearTimeout(resetPokeTimer)
  clearTimeout(waterTimer)
})
</script>

<template>
  <div class="bot-desk">
    <div v-if="speech" class="bot-desk-speech">{{ speech }}</div>
    <svg class="bot-desk-svg" viewBox="0 0 280 164" role="img" :aria-label="`${card.bot.name} 的工位`">
      <!-- Room wall & window -->
      <rect x="0" y="0" width="280" height="164" rx="12" :fill="wallColor" class="desk-wall" />
      <!-- Expert+ warm rug under desk -->
      <ellipse v-if="tierRank >= 4" cx="140" cy="155" rx="92" ry="8" :fill="card.bot.color" fill-opacity="0.16" />

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
        <rect x="96" y="18" width="86" height="6" rx="2" fill="#b79674" />
        <rect
          v-for="i in Math.min(7, Math.max(1, playbookCount))"
          :key="i"
          :x="100 + (i - 1) * 11"
          :y="playbookCount === 0 ? 13 : (i % 3 === 0 ? 4 : 6)"
          width="8.5"
          :height="playbookCount === 0 ? 5 : (i % 3 === 0 ? 14 : 12)"
          rx="1.5"
          :fill="playbookCount === 0 ? '#d5ddd7' : (i % 2 === 0 ? card.bot.color : '#5b8a72')"
        />
      </g>

      <!-- Sticky notes (Routines) -->
      <g class="desk-hotspot" @click="emit('jumpTab', 'activity')">
        <title>便利贴：{{ routineCount }} 项例行任务（点击查看）</title>
        <rect x="196" y="14" width="24" height="24" rx="2" fill="#fef08a" stroke="#e5c749" stroke-width="1" />
        <rect v-if="routineCount > 1" x="224" y="18" width="22" height="22" rx="2" fill="#fed7aa" stroke="#e0a96d" stroke-width="1" />
        <text x="208" y="30" text-anchor="middle" font-size="10" font-weight="700" fill="#7c5d10">{{ routineCount }}</text>
      </g>

      <!-- Senior+ Clickable Desk Lamp -->
      <g v-if="tierRank >= 3" class="desk-hotspot" @click="toggleLamp">
        <title>护眼台灯（点击开关）</title>
        <polygon v-if="lampOn" points="244,74 220,115 268,115" fill="#fde047" fill-opacity="0.16" />
        <path d="M254 114 L254 88 L244 74" fill="none" stroke="#475569" stroke-width="2.5" stroke-linecap="round" />
        <path d="M236 74 L252 74 L249 66 L239 66 Z" :fill="lampOn ? '#f59e0b' : '#64748b'" />
        <rect x="247" y="111" width="14" height="4" rx="2" fill="#334155" />
      </g>

      <!-- Master Golden Trophy on Shelf -->
      <g v-if="tierRank >= 5">
        <title>满级大师金杯</title>
        <path d="M256 30 h10 v6 a5 5 0 0 1 -10 0 Z" fill="#fbbf24" stroke="#b45309" stroke-width="1" />
        <rect x="259" y="40" width="4" height="4" fill="#d97706" />
        <rect x="256" y="44" width="10" height="2.5" rx="1" fill="#92400e" />
      </g>

      <!-- Chair back behind character (upgrades by tier) -->
      <g>
        <rect v-if="tierRank === 1" x="115" y="128" width="30" height="20" rx="4" fill="#9ca3af" />
        <rect v-else-if="tierRank <= 3" x="111" y="112" width="38" height="36" rx="8" :fill="isDark ? '#334155' : '#475569'" />
        <g v-else>
          <rect x="108" y="106" width="44" height="42" rx="10" :fill="card.bot.color" fill-opacity="0.85" />
          <rect x="114" y="100" width="32" height="10" rx="5" :fill="card.bot.color" />
        </g>
      </g>

      <!-- Desk surface -->
      <rect x="10" y="114" width="260" height="40" rx="6" :fill="deskWoodColor" />
      <rect x="10" y="114" width="260" height="7" rx="3" :fill="deskEdgeColor" />

      <!-- Clickable Potted Plant with watering drops -->
      <g class="desk-hotspot" @click="waterPlant">
        <title>工位绿植（已入职第 {{ daysOnboard }} 天，点击浇水）</title>
        <rect x="20" y="98" width="18" height="16" rx="3" fill="#c47d5a" />
        <path
          :d="`M29 98 L29 ${98 - plantStage * 7}`"
          stroke="#2f7d57"
          stroke-width="2.5"
          stroke-linecap="round"
        />
        <ellipse cx="24" :cy="92 - plantStage * 4" rx="5" ry="3" fill="#3f9a70" transform="rotate(-25 24 88)" />
        <ellipse v-if="plantStage >= 2" cx="34" :cy="90 - plantStage * 5" rx="5" ry="3" fill="#2f7d57" transform="rotate(25 34 86)" />
        <circle v-if="plantStage >= 4" cx="29" :cy="95 - plantStage * 7" r="3" fill="#f59e0b" />
        <g v-if="watering" class="desk-water-drops">
          <circle cx="23" cy="70" r="2" fill="#38bdf8" />
          <circle cx="29" cy="64" r="2.2" fill="#38bdf8" />
          <circle cx="35" cy="72" r="1.8" fill="#38bdf8" />
        </g>
      </g>

      <!-- Adept+ Steaming Coffee Mug -->
      <g v-if="tierRank >= 2">
        <title>提神咖啡</title>
        <rect x="52" y="101" width="12" height="13" rx="2.5" fill="#ffffff" stroke="#cbd5e1" stroke-width="1" />
        <path d="M64 104 h2.5 a2.5 2.5 0 0 1 0 5 h-2.5" fill="none" stroke="#cbd5e1" stroke-width="1.3" />
        <path class="desk-steam" d="M55 98 q1.5 -3 0 -6 M60 97 q1.5 -3 0 -6" fill="none" stroke="#94a3b8" stroke-width="1.2" stroke-linecap="round" />
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

      <!-- Idle fish daydream thought bubble -->
      <g v-if="!isBusy && !poked && !dizzy && idleMode === 'window'" opacity="0.88">
        <circle cx="152" cy="108" r="2.2" fill="#ffffff" stroke="#cbd5e1" stroke-width="0.8" />
        <circle cx="158" cy="102" r="3.4" fill="#ffffff" stroke="#cbd5e1" stroke-width="0.8" />
        <ellipse cx="171" cy="91" rx="13" ry="8.5" fill="#ffffff" stroke="#cbd5e1" stroke-width="1" />
        <text x="171" y="94.5" text-anchor="middle" font-size="9">🐟</text>
      </g>

      <!-- Bot character at desk (clickable to poke, 5 pokes = dizzy!) -->
      <g
        class="desk-bot-char"
        :class="{ 'is-working': isBusy || poked, 'is-nap': !isBusy && !poked && idleMode === 'nap', 'is-dizzy': dizzy }"
        @click="pokeBot"
      >
        <title>点击戳一戳 {{ card.bot.name }}（连戳 5 下有彩蛋）</title>
        <g transform="translate(106, 108) scale(1.0)" v-html="avatarMarkup" />
        <path v-if="isBusy || poked" d="M111 131a19 19 0 0 1 38 0" fill="none" stroke="#1b221e" stroke-width="3" />
        <rect v-if="isBusy || poked" x="108" y="128" width="5" height="9" rx="2" fill="#1b221e" />
        <rect v-if="isBusy || poked" x="147" y="128" width="5" height="9" rx="2" fill="#1b221e" />
        <text v-if="!isBusy && !poked && !dizzy && idleMode === 'nap'" x="150" y="118" font-size="10" font-weight="700" fill="#64748b">Zzz</text>
        <g v-if="dizzy" class="desk-dizzy-stars">
          <text x="112" y="112" font-size="10">✦</text>
          <text x="145" y="110" font-size="11">★</text>
          <text x="128" y="106" font-size="9">✦</text>
        </g>
      </g>
    </svg>
    <div class="bot-desk-foot">
      <span>{{ statusCaption }}</span>
      <button type="button" class="bot-desk-flip" @click="emit('flipBack')">翻回工牌</button>
    </div>
  </div>
</template>

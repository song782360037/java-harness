<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { StartupAnimationStyle } from './preferences'
import type { StartupSplashMode } from './startupAnimation'

const props = defineProps<{
  mode: Exclude<StartupSplashMode, 'none'>
  styleName: StartupAnimationStyle
}>()

const emit = defineEmits<{
  reveal: []
  done: []
}>()

type SplashPhase = 'enter' | 'ignite' | 'exiting'

const isHandoff = computed(() => props.mode === 'handoff')
const phase = ref<SplashPhase>(props.mode === 'handoff' ? 'ignite' : 'enter')
const skipping = ref(false)
const progress = ref(props.mode === 'handoff' ? 74 : 0)
const fxCanvas = ref<HTMLCanvasElement>()

const terminalLines = [
  { tag: 'OK', module: 'KERNEL', detail: 'Java 21 Pure Core · Local-First' },
  { tag: 'OK', module: 'AGENTS', detail: 'Bot Team · Flows · MCP / ACP' },
  { tag: 'OK', module: 'MEMORY', detail: '127.0.0.1 Workbench Online' },
] as const

const timers: number[] = []
let rafId = 0
let mountedAt = 0
let finished = false
let revealEmitted = false
let shockwaveTriggered = props.mode === 'handoff'

function schedule(fn: () => void, ms: number) {
  const id = window.setTimeout(fn, ms)
  timers.push(id)
}

function clearTimers() {
  while (timers.length) window.clearTimeout(timers.pop())
}

function startExit(fast = false) {
  if (finished || phase.value === 'exiting') return
  clearTimers()
  skipping.value = fast
  phase.value = 'exiting'
  if (!revealEmitted) {
    revealEmitted = true
    emit('reveal')
  }
  const exitMs = fast ? 260 : props.styleName === 'prism' ? 580 : 500
  schedule(() => {
    if (finished) return
    finished = true
    emit('done')
  }, exitMs)
}

function onSkip() {
  if (performance.now() - mountedAt < 140) return
  startExit(true)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' || event.key === ' ' || event.key === 'Enter') {
    event.preventDefault()
    onSkip()
  }
}

function parseColor(value: string, fallback: [number, number, number]): [number, number, number] {
  const text = value.trim()
  if (text.startsWith('#')) {
    const hex = text.slice(1)
    if (hex.length === 3) {
      return [
        parseInt(hex[0] + hex[0], 16),
        parseInt(hex[1] + hex[1], 16),
        parseInt(hex[2] + hex[2], 16),
      ]
    }
    if (hex.length >= 6) {
      return [
        parseInt(hex.slice(0, 2), 16),
        parseInt(hex.slice(2, 4), 16),
        parseInt(hex.slice(4, 6), 16),
      ]
    }
  }
  const match = text.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/)
  if (match) return [Number(match[1]), Number(match[2]), Number(match[3])]
  return fallback
}

function startCanvasFx() {
  const canvas = fxCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const computedStyle = getComputedStyle(document.documentElement)
  const isDark = document.documentElement.dataset.colorScheme === 'dark'
  const accentRgb = parseColor(computedStyle.getPropertyValue('--accent'), isDark ? [159, 216, 181] : [39, 102, 73])
  const jadeRgb = parseColor(computedStyle.getPropertyValue('--brand-jade'), [141, 217, 182])

  let width = 0
  let height = 0
  let dpr = 1

  const resize = () => {
    dpr = Math.min(window.devicePixelRatio || 1, 2)
    width = window.innerWidth
    height = window.innerHeight
    canvas.width = Math.floor(width * dpr)
    canvas.height = Math.floor(height * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  resize()
  window.addEventListener('resize', resize)

  interface Particle {
    angle: number
    radius: number
    radialVel: number
    spin: number
    size: number
    alpha: number
    bright: boolean
    x: number
    y: number
    vx: number
    vy: number
  }

  const maxSpan = Math.hypot(width, height) * 0.56
  const particleCount = props.styleName === 'orbital' ? 60 : props.styleName === 'prism' ? 36 : 32
  const particles: Particle[] = Array.from({ length: particleCount }, (_, idx) => {
    const angle = (idx / particleCount) * Math.PI * 2 + (Math.random() - 0.5) * 0.45
    const radius = 84 + Math.random() * maxSpan
    return {
      angle,
      radius,
      radialVel: 0.75 + Math.random() * 1.45,
      spin: (idx % 2 === 0 ? 1 : -1) * (0.004 + Math.random() * 0.008),
      size: 1.2 + Math.random() * 2.2,
      alpha: 0.25 + Math.random() * 0.58,
      bright: idx % 3 === 0,
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.55 + (props.styleName === 'prism' ? 0.5 : 0),
      vy: (Math.random() - 0.5) * 0.45 - (props.styleName === 'prism' ? 0.28 : 0),
    }
  })

  const shockwaves: { radius: number; alpha: number; width: number }[] = []
  let burstApplied = false
  const startTime = performance.now()
  const totalProgressMs = isHandoff.value ? 520 : 1480
  const initialProgress = isHandoff.value ? 74 : 0

  const draw = (now: number) => {
    const elapsed = now - startTime
    if (props.styleName === 'terminal') {
      const ratio = Math.min(1, elapsed / totalProgressMs)
      const eased = 1 - Math.pow(1 - ratio, 2.4)
      progress.value = Math.min(100, Math.round(initialProgress + (100 - initialProgress) * eased))
    }

    ctx.clearRect(0, 0, width, height)
    const cx = width * 0.5
    const cy = height * 0.48
    const rgb = (bright: boolean) => (bright ? jadeRgb : accentRgb)

    if (shockwaveTriggered && !burstApplied) {
      burstApplied = true
      if (props.styleName === 'orbital') {
        shockwaves.push({ radius: 44, alpha: isDark ? 0.72 : 0.48, width: 2.2 })
        shockwaves.push({ radius: 30, alpha: isDark ? 0.42 : 0.28, width: 1.2 })
      } else if (props.styleName === 'prism') {
        shockwaves.push({ radius: 36, alpha: isDark ? 0.52 : 0.34, width: 1.6 })
      }
    }

    if (props.styleName === 'orbital') {
      for (const p of particles) {
        if (burstApplied) {
          p.radius += p.radialVel * 2.15
          p.angle += p.spin * 0.65
        } else {
          p.radius = Math.max(46, p.radius - p.radialVel * 1.45)
          p.angle += p.spin * 1.35
        }
        const x = cx + Math.cos(p.angle) * p.radius
        const y = cy + Math.sin(p.angle) * p.radius
        const prevAngle = p.angle - p.spin * (burstApplied ? 3 : 6)
        const prevRadius = burstApplied ? Math.max(42, p.radius - p.radialVel * 5.2) : p.radius + p.radialVel * 3.8
        const px = cx + Math.cos(prevAngle) * prevRadius
        const py = cy + Math.sin(prevAngle) * prevRadius
        const [r, g, b] = rgb(p.bright)
        const alphaScale = isDark ? 1 : 0.68

        ctx.beginPath()
        ctx.moveTo(px, py)
        ctx.lineTo(x, y)
        ctx.strokeStyle = `rgba(${r}, ${g}, ${b}, ${(p.alpha * 0.45 * alphaScale).toFixed(3)})`
        ctx.lineWidth = p.size * 0.75
        ctx.lineCap = 'round'
        ctx.stroke()

        ctx.beginPath()
        ctx.arc(x, y, p.size, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${(p.alpha * alphaScale).toFixed(3)})`
        ctx.fill()
      }
    } else if (props.styleName === 'prism') {
      for (const p of particles) {
        p.x += p.vx * (burstApplied ? 2.1 : 1)
        p.y += p.vy * (burstApplied ? 2.1 : 1)
        if (p.x > width + 20) p.x = -20
        if (p.y < -20) p.y = height + 20
        const [r, g, b] = rgb(p.bright)
        const alpha = p.alpha * (isDark ? 0.72 : 0.48)
        ctx.beginPath()
        ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha.toFixed(3)})`
        ctx.fill()
      }
    } else {
      const step = 48
      for (let i = 0; i < particles.length; i++) {
        const p = particles[i]
        const gx = Math.round(p.x / step) * step
        const gy = Math.round(p.y / step) * step
        const pulse = 0.25 + 0.75 * Math.abs(Math.sin(elapsed * 0.0032 + i * 0.7))
        const [r, g, b] = rgb(i % 2 === 0)
        ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${(pulse * (isDark ? 0.42 : 0.28)).toFixed(3)})`
        ctx.fillRect(gx - 1.5, gy - 1.5, 3, 3)
      }
    }

    for (let i = shockwaves.length - 1; i >= 0; i--) {
      const wave = shockwaves[i]
      wave.radius += 11.5
      wave.alpha *= 0.925
      if (wave.alpha < 0.01) {
        shockwaves.splice(i, 1)
        continue
      }
      const [r, g, b] = accentRgb
      ctx.beginPath()
      ctx.arc(cx, cy, wave.radius, 0, Math.PI * 2)
      ctx.strokeStyle = `rgba(${r}, ${g}, ${b}, ${wave.alpha.toFixed(3)})`
      ctx.lineWidth = wave.width
      ctx.stroke()
    }

    rafId = window.requestAnimationFrame(draw)
  }

  rafId = window.requestAnimationFrame(draw)

  return () => {
    window.cancelAnimationFrame(rafId)
    window.removeEventListener('resize', resize)
  }
}

let stopCanvasFx = () => {}

onMounted(() => {
  mountedAt = performance.now()
  window.addEventListener('keydown', onKeydown)
  stopCanvasFx = startCanvasFx() || (() => {})

  if (props.mode === 'handoff') {
    const exitDelay = props.styleName === 'terminal' ? 580 : 540
    schedule(() => startExit(false), exitDelay)
    return
  }

  const igniteDelay = props.styleName === 'prism' ? 420 : props.styleName === 'terminal' ? 460 : 450
  const exitDelay = props.styleName === 'terminal' ? 1620 : props.styleName === 'prism' ? 1480 : 1500
  schedule(() => {
    if (phase.value === 'enter') {
      phase.value = 'ignite'
      shockwaveTriggered = true
    }
  }, igniteDelay)
  schedule(() => startExit(false), exitDelay)
})

onUnmounted(() => {
  clearTimers()
  stopCanvasFx()
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div
    class="startup-splash"
    :class="[
      `style-${styleName}`,
      `phase-${phase}`,
      { 'is-handoff': isHandoff, 'is-skipping': skipping, 'is-preview': mode === 'preview' },
    ]"
    role="status"
    aria-live="polite"
    aria-label="正在启动 Java Harness"
    @click="onSkip"
  >
    <!-- 背景与风格化几何舞台 -->
    <div v-if="styleName === 'prism'" class="splash-prism-shutters" aria-hidden="true">
      <div class="prism-door prism-door-left">
        <span class="prism-ribbon prism-ribbon-a"></span>
      </div>
      <div class="prism-door prism-door-right">
        <span class="prism-ribbon prism-ribbon-b"></span>
      </div>
      <div class="prism-seam-beam"></div>
    </div>
    <div v-else class="splash-backdrop" aria-hidden="true">
      <div class="splash-aurora"></div>
      <div class="splash-grid"></div>
    </div>

    <canvas ref="fxCanvas" class="splash-canvas" aria-hidden="true"></canvas>

    <!-- 极客终端风格：HUD 四角取景框与雷达扫描线 -->
    <div v-if="styleName === 'terminal'" class="splash-terminal-hud" aria-hidden="true">
      <i class="hud-corner tl"></i>
      <i class="hud-corner tr"></i>
      <i class="hud-corner bl"></i>
      <i class="hud-corner br"></i>
      <div class="hud-scanline"></div>
      <span class="hud-meta hud-meta-tl">SYS // 127.0.0.1</span>
      <span class="hud-meta hud-meta-tr">JAVA 21 · LOCAL CORE</span>
    </div>

    <!-- 中央品牌舞台 -->
    <div class="splash-center">
      <div class="splash-emblem-stage" aria-hidden="true">
        <div class="splash-emblem-halo"></div>

        <!-- 星轨脉冲专属双环星轨 -->
        <svg v-if="styleName === 'orbital'" class="splash-orbital-rings" viewBox="0 0 220 220" fill="none">
          <g class="orbital-ring-outer">
            <circle cx="110" cy="110" r="96" class="orbital-track" stroke-dasharray="3 8" />
            <path d="M 110 14 A 96 96 0 0 1 190 56" class="orbital-arc" />
            <circle cx="190" cy="56" r="3.2" class="orbital-node" />
          </g>
          <g class="orbital-ring-inner">
            <circle cx="110" cy="110" r="72" class="orbital-track-sub" stroke-dasharray="16 12" />
            <path d="M 110 182 A 72 72 0 0 1 46 144" class="orbital-arc-sub" />
            <circle cx="46" cy="144" r="2.4" class="orbital-node" />
          </g>
        </svg>

        <!-- 棱镜开幕专属菱形光框 -->
        <div v-if="styleName === 'prism'" class="splash-prism-frame"></div>

        <!-- 核心徽标：还原 Java Harness [ — ] 几何结构 -->
        <div class="splash-emblem-card">
          <span class="splash-emblem-sheen"></span>
          <svg class="splash-logo-svg" viewBox="0 0 32 32" fill="none">
            <path class="splash-bracket splash-bracket-left" d="M12 8H8V24H12" />
            <path class="splash-bracket splash-bracket-right" d="M20 8H24V24H20" />
            <path class="splash-bridge" d="M8 16H24" />
          </svg>
        </div>
        <div class="splash-flare-beam"></div>
      </div>

      <div class="splash-wordmark">
        <span class="splash-title">Java Harness</span>
        <span class="splash-tagline">
          <i class="splash-tagline-dot" aria-hidden="true"></i>
          LOCAL-FIRST AI WORKBENCH
        </span>
      </div>

      <!-- 极客终端风格：自检日志与加载百分比 -->
      <div v-if="styleName === 'terminal'" class="splash-terminal-panel" aria-hidden="true">
        <div v-for="(line, index) in terminalLines" :key="line.module" class="terminal-line" :style="{ '--line-index': index }">
          <span class="terminal-tag">[ {{ line.tag }} ]</span>
          <span class="terminal-module">{{ line.module }}</span>
          <span class="terminal-detail">{{ line.detail }}</span>
        </div>
        <div class="terminal-meter">
          <div class="terminal-meter-track">
            <span class="terminal-meter-fill" :style="{ width: `${progress}%` }"></span>
          </div>
          <span class="terminal-meter-value">{{ String(progress).padStart(2, '0') }}%</span>
        </div>
      </div>
    </div>

    <div class="splash-skip-hint" aria-hidden="true">
      {{ mode === 'preview' ? '动画预览中 · 点击或按 Esc 退出' : '点击任意处或按 Esc 跳过' }}
    </div>
  </div>
</template>

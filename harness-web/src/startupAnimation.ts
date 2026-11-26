import { computed, readonly, ref } from 'vue'
import {
  normalizeStartupAnimationStyle,
  preferences,
  updatePreferences,
  type StartupAnimationStyle,
} from './preferences'
import type { StartupBootstrap } from './desktopUpdates'

export type StartupSplashMode = 'none' | 'full' | 'handoff' | 'preview'

export interface StartupAnimationOption {
  value: StartupAnimationStyle
  label: string
  badge?: string
  description: string
}

export const STARTUP_ANIMATION_OPTIONS: readonly StartupAnimationOption[] = [
  {
    value: 'orbital',
    label: '星轨脉冲',
    badge: '默认',
    description: '双轨星环汇聚与中心光晕脉冲',
  },
  {
    value: 'prism',
    label: '棱镜开幕',
    description: '极光棱镜光带与双开建筑幕布启幕',
  },
  {
    value: 'terminal',
    label: '极客终端',
    description: '本地内核自检脉动与雷达扫描解锁',
  },
] as const

export function getStartupAnimationOption(value: StartupAnimationStyle): StartupAnimationOption {
  return STARTUP_ANIMATION_OPTIONS.find(option => option.value === value) || STARTUP_ANIMATION_OPTIONS[0]
}

export interface InitialSplashInput {
  desktopBootstrap?: StartupBootstrap | null
  webEnabled: boolean
  webStyle: StartupAnimationStyle
  reducedMotion: boolean
}

export interface InitialSplashResolution {
  enabled: boolean
  style: StartupAnimationStyle
  mode: Exclude<StartupSplashMode, 'preview'>
}

/** 计算首屏开场动画策略：桌面端优先以主进程持久化配置为准，并与加载页衔接。 */
export function resolveInitialSplash(input: InitialSplashInput): InitialSplashResolution {
  const desktop = input.desktopBootstrap
  if (desktop && typeof desktop === 'object') {
    const enabled = typeof desktop.enabled === 'boolean' ? desktop.enabled : true
    const style = normalizeStartupAnimationStyle(desktop.style)
    const rawMode = desktop.mode === 'handoff' || desktop.mode === 'full' ? desktop.mode : 'none'
    return {
      enabled,
      style,
      mode: !enabled || input.reducedMotion ? 'none' : rawMode,
    }
  }
  const enabled = typeof input.webEnabled === 'boolean' ? input.webEnabled : true
  const style = normalizeStartupAnimationStyle(input.webStyle)
  return {
    enabled,
    style,
    mode: !enabled || input.reducedMotion ? 'none' : 'full',
  }
}

function prefersReducedMotion(): boolean {
  try {
    return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

function readDesktopBootstrap(): StartupBootstrap | null {
  try {
    return typeof window !== 'undefined' ? window.harnessDesktop?.getStartupBootstrap?.() ?? null : null
  } catch {
    return null
  }
}

const desktopBootstrap = readDesktopBootstrap()
const initial = resolveInitialSplash({
  desktopBootstrap,
  webEnabled: preferences.value.startupAnimation,
  webStyle: preferences.value.startupAnimationStyle,
  reducedMotion: prefersReducedMotion(),
})

if (desktopBootstrap) {
  updatePreferences({
    startupAnimation: initial.enabled,
    startupAnimationStyle: initial.style,
  })
}

let splashSequence = initial.mode === 'none' ? 0 : 1
const activeSplash = ref<{ mode: StartupSplashMode; style: StartupAnimationStyle; runId: number }>({
  mode: initial.mode,
  style: initial.style,
  runId: splashSequence,
})
const saving = ref(false)
const saveError = ref('')

export const startupSplash = readonly(activeSplash)
export const startupAnimationEnabled = computed(() => preferences.value.startupAnimation)
export const startupAnimationStyle = computed(() => preferences.value.startupAnimationStyle)
export const startupAnimationSaving = readonly(saving)
export const startupAnimationError = readonly(saveError)

let persistSequence = 0

async function persistDesktopStartup(
  patch: { enabled?: boolean; style?: StartupAnimationStyle },
  previous: { startupAnimation: boolean; startupAnimationStyle: StartupAnimationStyle },
) {
  const bridge = typeof window !== 'undefined' ? window.harnessDesktop : undefined
  if (!bridge?.setStartupAnimation) return
  const seq = ++persistSequence
  saving.value = true
  saveError.value = ''
  try {
    const saved = await bridge.setStartupAnimation(patch)
    if (seq !== persistSequence) return
    updatePreferences({
      startupAnimation: saved.enabled,
      startupAnimationStyle: normalizeStartupAnimationStyle(saved.style),
    })
  } catch {
    if (seq !== persistSequence) return
    updatePreferences(previous)
    saveError.value = '无法保存桌面端启动动画设置，请重试'
  } finally {
    if (seq === persistSequence) saving.value = false
  }
}

export async function setStartupAnimationEnabled(enabled: boolean) {
  const previous = {
    startupAnimation: preferences.value.startupAnimation,
    startupAnimationStyle: preferences.value.startupAnimationStyle,
  }
  updatePreferences({ startupAnimation: enabled })
  await persistDesktopStartup({ enabled }, previous)
}

export async function setStartupAnimationStyle(style: StartupAnimationStyle) {
  const normalized = normalizeStartupAnimationStyle(style)
  const previous = {
    startupAnimation: preferences.value.startupAnimation,
    startupAnimationStyle: preferences.value.startupAnimationStyle,
  }
  updatePreferences({ startupAnimationStyle: normalized })
  await persistDesktopStartup({ style: normalized }, previous)
}

/** 随时全屏预览当前或指定风格的开场动画，方便在设置页直接感受效果。 */
export function previewStartupAnimation(style?: StartupAnimationStyle) {
  activeSplash.value = {
    mode: 'preview',
    style: style ? normalizeStartupAnimationStyle(style) : preferences.value.startupAnimationStyle,
    runId: ++splashSequence,
  }
}

export function finishStartupSplash() {
  if (activeSplash.value.mode === 'none') return
  activeSplash.value = {
    ...activeSplash.value,
    mode: 'none',
  }
}

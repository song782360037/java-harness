import { readonly, ref } from 'vue'

export type ThemeId = 'pure' | 'light' | 'paper' | 'ocean' | 'sakura' | 'dark' | 'midnight' | 'espresso' | 'nebula'
export type ThemePreference = 'system' | ThemeId
export type ThemeColorScheme = 'light' | 'dark'
export type ThemeGroup = 'light' | 'dark'

export interface ThemeOption {
  value: ThemeId
  label: string
  description: string
  group: ThemeGroup
  scheme: ThemeColorScheme
  keywords: string
  swatches: readonly [string, string, string]
}

export const THEME_OPTIONS: readonly ThemeOption[] = [
  {
    value: 'pure',
    label: '纯白极简',
    description: '纯净留白与利落黑白灰',
    group: 'light',
    scheme: 'light',
    keywords: 'pure white minimal clean monochrome 纯白 极简 简洁 白色 浅色',
    swatches: ['#ffffff', '#fafafa', '#18181b'],
  },
  {
    value: 'light',
    label: '翡翠浅色',
    description: '默认青玉柔和浅色',
    group: 'light',
    scheme: 'light',
    keywords: 'light jade default 明亮 浅色 默认 翡翠',
    swatches: ['#f7f9f7', '#eef2ee', '#276649'],
  },
  {
    value: 'paper',
    label: '暖纸米白',
    description: '温润书卷护眼米色',
    group: 'light',
    scheme: 'light',
    keywords: 'paper warm sepia ivory cream 暖纸 米白 护眼 浅色',
    swatches: ['#faf6ef', '#f2ebe0', '#9c4f21'],
  },
  {
    value: 'ocean',
    label: '晴空海蓝',
    description: '清透冷静钴蓝浅色',
    group: 'light',
    scheme: 'light',
    keywords: 'ocean blue sky light 晴空 海蓝 蓝色 浅色',
    swatches: ['#f5f8fc', '#eaf0f7', '#1d5fa8'],
  },
  {
    value: 'sakura',
    label: '落樱浅粉',
    description: '淡雅柔粉轻盈明亮',
    group: 'light',
    scheme: 'light',
    keywords: 'sakura rose pink light 落樱 浅粉 樱花 浅色',
    swatches: ['#fcf7f9', '#f5ecf0', '#a63a68'],
  },
  {
    value: 'dark',
    label: '翡翠深色',
    description: '墨绿低眩暗夜',
    group: 'dark',
    scheme: 'dark',
    keywords: 'dark jade default 深色 黑暗 夜间 翡翠',
    swatches: ['#181d1a', '#131715', '#9fd8b5'],
  },
  {
    value: 'midnight',
    label: '午夜星蓝',
    description: '深邃蓝灰夜间编码',
    group: 'dark',
    scheme: 'dark',
    keywords: 'midnight slate blue night dark 午夜 星蓝 深蓝 暗色 深色',
    swatches: ['#131823', '#0e121b', '#7cb6f7'],
  },
  {
    value: 'espresso',
    label: '暖棕咖啡',
    description: '暖调琥珀低蓝光暗色',
    group: 'dark',
    scheme: 'dark',
    keywords: 'espresso coffee brown amber warm dark 暖棕 咖啡 琥珀 暗色 深色',
    swatches: ['#1c1815', '#151210', '#e5b272'],
  },
  {
    value: 'nebula',
    label: '星云暗紫',
    description: '柔和暮紫微光暗夜',
    group: 'dark',
    scheme: 'dark',
    keywords: 'nebula violet purple lavender dark 星云 暗紫 紫色 暗色 深色',
    swatches: ['#181622', '#12101b', '#bca6fa'],
  },
] as const

export function getThemeOption(value: ThemePreference): ThemeOption {
  if (value === 'system') {
    return {
      value: 'light',
      label: '跟随系统',
      description: '随操作系统浅色 / 深色自动切换',
      group: 'light',
      scheme: 'light',
      keywords: 'system auto 跟随 系统 自动',
      swatches: ['#f7f9f7', '#276649', '#181d1a'],
    }
  }
  return THEME_OPTIONS.find(option => option.value === value) || THEME_OPTIONS[1]
}

declare global {
  interface Window {
    harnessTheme: {
      readonly preference: ThemePreference
      readonly resolved: ThemeId
      readonly colorScheme: ThemeColorScheme
      readonly persistent: boolean
      setPreference(value: ThemePreference): void
      subscribe(listener: () => void): () => void
    }
  }
}
const controller = window.harnessTheme
const preference = ref<ThemePreference>(controller.preference)
const resolved = ref<ThemeId>(controller.resolved)
const colorScheme = ref<ThemeColorScheme>(controller.colorScheme)
const persistent = ref(controller.persistent)
const unsubscribe = controller.subscribe(() => {
  preference.value = controller.preference
  resolved.value = controller.resolved
  colorScheme.value = controller.colorScheme
  persistent.value = controller.persistent
})
if (import.meta.hot) import.meta.hot.dispose(unsubscribe)
export const themePreference = readonly(preference)
export const resolvedTheme = readonly(resolved)
export const resolvedColorScheme = readonly(colorScheme)
export const themePersistent = readonly(persistent)
export const setTheme = (value: ThemePreference) => controller.setPreference(value)

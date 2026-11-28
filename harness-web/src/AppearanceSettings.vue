<script setup lang="ts">
import { computed } from 'vue'
import {
  THEME_OPTIONS,
  getThemeOption,
  setTheme,
  themePersistent,
  themePreference,
  type ThemeGroup,
} from './theme'
import { preferencesPersistent } from './preferences'
import {
  STARTUP_ANIMATION_OPTIONS,
  getStartupAnimationOption,
  previewStartupAnimation,
  setStartupAnimationEnabled,
  setStartupAnimationStyle,
  startupAnimationEnabled,
  startupAnimationError,
  startupAnimationSaving,
  startupAnimationStyle,
} from './startupAnimation'
import UiIcon from './UiIcon.vue'

const groups: readonly { id: ThemeGroup; label: string }[] = [
  { id: 'light', label: '浅色主题' },
  { id: 'dark', label: '深色主题' },
]

const groupedChoices = computed(() =>
  groups.map(group => ({
    ...group,
    items: THEME_OPTIONS.filter(option => option.group === group.id),
  })),
)

const activeThemeSummary = computed(() => getThemeOption(themePreference.value).label)
const activeStartupSummary = computed(() =>
  startupAnimationEnabled.value
    ? `已开启 · ${getStartupAnimationOption(startupAnimationStyle.value).label}`
    : '已关闭',
)
const desktopStartup = !!window.harnessDesktop?.setStartupAnimation
const startupStatusText = computed(() => {
  if (desktopStartup) return '设置会自动保存到本地桌面配置，下次打开客户端时即刻生效。'
  return preferencesPersistent.value
    ? '设置会自动保存，刷新或下次打开页面时生效。'
    : '浏览器无法保存偏好，更改仅在当前页面生效。'
})
</script>
<template>
  <section class="appearance-settings" aria-labelledby="theme-title">
    <div class="appearance-heading">
      <h2 id="theme-title">主题</h2>
      <span class="appearance-current-badge">当前：{{ activeThemeSummary }}</span>
    </div>
    <div class="theme-group">
      <div class="theme-choices" role="group" aria-label="自动跟随系统">
        <button
          type="button"
          class="theme-choice"
          :aria-pressed="themePreference === 'system'"
          @click="setTheme('system')"
        >
          <span class="theme-preview preview-light" style="background: linear-gradient(135deg, #f7f9f7 50%, #181d1a 50%);" aria-hidden="true">
            <span class="preview-sidebar"><i></i><i></i><i></i></span>
            <span class="preview-main"><i></i><i></i><span class="preview-composer"></span></span>
          </span>
          <span class="theme-choice-meta">
            <span class="theme-choice-heading">
              <strong>跟随系统</strong>
              <UiIcon v-if="themePreference === 'system'" name="check" :size="16" aria-hidden="true" />
              <UiIcon v-else name="monitor" :size="15" aria-hidden="true" />
            </span>
            <span class="theme-choice-desc">自动跟随操作系统浅色与深色模式切换</span>
          </span>
        </button>
      </div>
    </div>
    <div v-for="group in groupedChoices" :key="group.id" class="theme-group">
      <h3 class="theme-group-title">{{ group.label }}</h3>
      <div class="theme-choices" role="group" :aria-label="group.label">
        <button
          v-for="choice in group.items"
          :key="choice.value"
          type="button"
          class="theme-choice"
          :aria-pressed="themePreference === choice.value"
          @click="setTheme(choice.value)"
        >
          <span class="theme-preview" :class="'preview-' + choice.value" aria-hidden="true">
            <span class="preview-sidebar"><i></i><i></i><i></i></span>
            <span class="preview-main"><i></i><i></i><span class="preview-composer"></span></span>
          </span>
          <span class="theme-choice-meta">
            <span class="theme-choice-heading">
              <strong>{{ choice.label }}</strong>
              <UiIcon v-if="themePreference === choice.value" name="check" :size="16" aria-hidden="true" />
              <span v-else class="theme-swatches" aria-hidden="true">
                <i v-for="(color, index) in choice.swatches" :key="index" :style="{ background: color }"></i>
              </span>
            </span>
            <span class="theme-choice-desc">{{ choice.description }}</span>
          </span>
        </button>
      </div>
    </div>
    <p class="appearance-status" role="status">{{ themePersistent ? '主题切换立即生效，选择会自动保存。' : '浏览器无法保存偏好，本次切换仍然生效。' }}</p>

    <section class="startup-settings" aria-labelledby="startup-animation-title">
      <div class="appearance-heading">
        <h2 id="startup-animation-title">启动动画</h2>
        <span class="appearance-current-badge">{{ activeStartupSummary }}</span>
      </div>

      <div class="general-settings startup-toggle-card">
        <div class="preference-row">
          <div class="preference-copy">
            <label for="startup-animation-toggle">打开客户端时播放启动动画</label>
            <p id="startup-animation-help">首次使用默认开启，可在进入工作台前展示品牌开场动效，随时支持一键关闭或跳过</p>
          </div>
          <div class="startup-row-controls">
            <button
              type="button"
              class="startup-preview-btn"
              aria-label="预览当前风格的启动动画"
              @click="previewStartupAnimation()"
            >
              <UiIcon name="play" :size="13" />
              <span>预览效果</span>
            </button>
            <input
              id="startup-animation-toggle"
              type="checkbox"
              :checked="startupAnimationEnabled"
              :disabled="startupAnimationSaving"
              aria-describedby="startup-animation-help"
              @change="setStartupAnimationEnabled(($event.target as HTMLInputElement).checked)"
            />
          </div>
        </div>
      </div>

      <div class="theme-group">
        <h3 class="theme-group-title">开场风格</h3>
        <div class="startup-style-choices" :class="{ 'is-muted': !startupAnimationEnabled }" role="group" aria-label="启动动画风格">
          <button
            v-for="option in STARTUP_ANIMATION_OPTIONS"
            :key="option.value"
            type="button"
            class="startup-style-card"
            :disabled="startupAnimationSaving"
            :aria-pressed="startupAnimationStyle === option.value"
            @click="setStartupAnimationStyle(option.value)"
          >
            <span class="startup-thumb" :class="'thumb-' + option.value" aria-hidden="true">
              <template v-if="option.value === 'orbital'">
                <span class="thumb-ring thumb-ring-a"></span>
                <span class="thumb-ring thumb-ring-b"></span>
              </template>
              <template v-else-if="option.value === 'prism'">
                <span class="thumb-seam"></span>
                <span class="thumb-diamond"></span>
              </template>
              <template v-else>
                <span class="thumb-scan"></span>
                <span class="thumb-bars"><i></i><i></i><i></i></span>
              </template>
              <span class="startup-thumb-emblem">
                <svg viewBox="0 0 32 32" fill="none">
                  <path d="M12 8H8V24H12M20 8H24V24H20" stroke="#F5F4EE" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M8 16H24" stroke="#8DD9B6" stroke-width="2.5" stroke-linecap="round" />
                </svg>
              </span>
            </span>
            <span class="theme-choice-meta">
              <span class="theme-choice-heading">
                <span class="startup-style-heading-left">
                  <strong>{{ option.label }}</strong>
                  <span v-if="option.badge" class="startup-style-tag">{{ option.badge }}</span>
                </span>
                <UiIcon v-if="startupAnimationStyle === option.value" name="check" :size="16" aria-hidden="true" />
              </span>
              <span class="theme-choice-desc">{{ option.description }}</span>
            </span>
          </button>
        </div>
      </div>

      <p v-if="startupAnimationError" class="appearance-status" role="alert">{{ startupAnimationError }}</p>
      <p v-else class="appearance-status" role="status">{{ startupStatusText }}</p>
    </section>
  </section>
</template>

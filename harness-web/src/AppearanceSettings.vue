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
</script>
<template>
  <section class="appearance-settings" aria-labelledby="theme-title">
    <div class="appearance-heading">
      <h2 id="theme-title">主题</h2>
      <span class="appearance-current-badge">当前：{{ activeThemeSummary }}</span>
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
  </section>
</template>

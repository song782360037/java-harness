<script setup lang="ts">
import { themePreference, resolvedTheme, themePersistent, setTheme } from './theme'
import UiIcon from './UiIcon.vue'
const choices = [
  { value: 'light', label: '明亮', description: '明亮背景，清晰简洁' },
  { value: 'dark', label: '黑暗', description: '深色背景，柔和对比' },
  { value: 'system', label: '跟随系统', description: '自动使用系统外观' },
] as const
</script>
<template>
  <section class="appearance-settings" aria-labelledby="theme-title">
    <div class="appearance-heading"><h2 id="theme-title">主题</h2><p>应用于整个工作台，包括聊天、设置和所有菜单。</p></div>
    <div class="theme-choices" role="group" aria-label="主题选择">
      <button v-for="choice in choices" :key="choice.value" type="button" class="theme-choice" :aria-pressed="themePreference === choice.value" @click="setTheme(choice.value)">
        <span class="theme-preview" :class="'preview-' + choice.value" aria-hidden="true"><span class="preview-sidebar"><i></i><i></i><i></i></span><span class="preview-main"><i></i><i></i><span class="preview-composer"></span></span></span>
        <span class="theme-choice-heading"><strong>{{ choice.label }}</strong><UiIcon v-if="themePreference === choice.value" name="check" :size="16" aria-hidden="true" /></span>
        <small>{{ choice.description }}</small>
      </button>
    </div>
    <p class="appearance-status" role="status">当前为{{ resolvedTheme === 'dark' ? '黑暗' : '明亮' }}主题。{{ themePersistent ? '选择已保存在此浏览器，下次打开自动恢复。' : '浏览器无法保存偏好，本次切换仍然生效。' }}</p>
  </section>
</template>

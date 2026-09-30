<script setup lang="ts">
import { computed } from 'vue'
import PreferenceSelect from './PreferenceSelect.vue'
import { preferences, preferencesPersistent, updatePreferences } from './preferences'
const shortcuts = [{ value: 'modifier-enter', label: 'Ctrl / ⌘ + Enter' }, { value: 'enter', label: 'Enter' }] as const
const fontSizes = [{ value: 'small', label: '小 · 13 px' }, { value: 'standard', label: '标准 · 14 px' }, { value: 'large', label: '大 · 16 px' }] as const
const sendShortcut = computed(() => preferences.value.sendShortcut)
const fontSize = computed(() => preferences.value.chatFontSize)
const autoScroll = computed({ get: () => preferences.value.autoScroll, set: value => updatePreferences({ autoScroll: value }) })
</script>
<template>
  <section class="general-settings" aria-label="常规偏好">
    <div class="preference-row">
      <div class="preference-copy"><label id="send-shortcut-label" for="send-shortcut">发送快捷键</label><p id="send-shortcut-help">{{ sendShortcut === 'enter' ? 'Enter 发送，Shift + Enter 换行。' : 'Ctrl / ⌘ + Enter 发送，Enter 换行。' }}</p></div>
      <PreferenceSelect id="send-shortcut" :model-value="sendShortcut" :options="shortcuts" @update:model-value="updatePreferences({ sendShortcut: $event === 'enter' ? 'enter' : 'modifier-enter' })" />
    </div>
    <div class="preference-row">
      <div class="preference-copy"><label id="font-size-label" for="font-size">全局字号</label><p id="font-size-help">调整整个工作台的文字大小，包括侧栏、设置、菜单和聊天内容。</p></div>
      <PreferenceSelect id="font-size" :model-value="fontSize" :options="fontSizes" @update:model-value="updatePreferences({ chatFontSize: $event === 'small' || $event === 'large' ? $event : 'standard' })" />
    </div>
    <div class="preference-row">
      <div class="preference-copy"><label for="auto-scroll">自动跟随最新消息</label><p id="auto-scroll-help">位于对话底部时跟随新内容；向上阅读时暂停。关闭后新内容不会自动滚动。</p></div>
      <input id="auto-scroll" v-model="autoScroll" type="checkbox" aria-describedby="auto-scroll-help" />
    </div>
    <p class="preferences-status" role="status">{{ preferencesPersistent ? '更改立即生效，已保存在此浏览器。' : '浏览器无法保存偏好，更改仅在当前页面生效。' }}</p>
  </section>
</template>

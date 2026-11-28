<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import { botApi, miniAppIconName, type BotMiniApp, type BotMiniAppBundle } from './api'

const thumbBundleCache = new Map<string, BotMiniAppBundle>()

function cacheThumbBundle(key: string, value: BotMiniAppBundle) {
  if (thumbBundleCache.size >= 60) {
    const oldest = thumbBundleCache.keys().next().value
    if (oldest) thumbBundleCache.delete(oldest)
  }
  thumbBundleCache.set(key, value)
}

const props = defineProps<{
  appId: string
  fallbackName: string
  app?: BotMiniApp | null
}>()

const emit = defineEmits<{
  open: [appId: string]
}>()

const bundle = ref<BotMiniAppBundle | null>(null)
const failed = ref(false)

const displayName = computed(() => props.app?.name || bundle.value?.app.name || props.fallbackName)
const version = computed(() => props.app?.version || bundle.value?.app.version || 1)
const accentColor = computed(() => props.app?.color || bundle.value?.app.color || '#2d5d9c')
const iconName = computed(() => miniAppIconName(props.app?.icon || bundle.value?.app.icon))
const changeLabel = computed(() => {
  const latest = bundle.value?.versions?.at(-1)
  return latest?.changeSummary || props.app?.description || '交互式单文件小程序'
})

async function loadThumb() {
  failed.value = false
  const cacheKey = `${props.appId}:${props.app?.updatedAt || ''}`
  const cached = thumbBundleCache.get(cacheKey)
  if (cached) {
    bundle.value = cached
    return
  }
  try {
    const loaded = await botApi.miniApp(props.appId)
    bundle.value = loaded
    cacheThumbBundle(cacheKey, loaded)
    if (!props.app?.updatedAt && loaded.app?.updatedAt) {
      cacheThumbBundle(`${props.appId}:${loaded.app.updatedAt}`, loaded)
    }
  } catch {
    failed.value = true
  }
}

onMounted(() => { void loadThumb() })
watch(() => [props.appId, props.app?.updatedAt], () => { void loadThumb() })
</script>

<template>
  <button type="button" class="miniapp-chat-card" @click="emit('open', appId)">
    <div class="miniapp-chat-card-head">
      <span class="miniapp-tile-dot" :style="{ background: accentColor }">
        <UiIcon :name="iconName" :size="11" />
      </span>
      <strong class="miniapp-chat-card-name">{{ displayName }}</strong>
      <span class="miniapp-ver">v{{ version }}</span>
    </div>
    <p class="miniapp-chat-card-desc">{{ changeLabel }}</p>
    <div class="miniapp-chat-thumb">
      <iframe
        v-if="bundle && !failed"
        class="miniapp-thumb-frame"
        sandbox="allow-scripts"
        referrerpolicy="no-referrer"
        tabindex="-1"
        aria-hidden="true"
        :title="displayName"
        :srcdoc="bundle.html"
      ></iframe>
      <div v-else class="miniapp-thumb-placeholder">
        <UiIcon :name="iconName" :size="20" />
        <span>{{ failed ? '点击打开小程序' : '加载预览中…' }}</span>
      </div>
    </div>
    <div class="miniapp-chat-card-foot">
      <span>小程序 · v{{ version }}</span>
      <span class="miniapp-chat-card-open">打开 ↗</span>
    </div>
  </button>
</template>

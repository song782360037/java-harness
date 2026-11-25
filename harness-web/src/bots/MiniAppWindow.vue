<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import { botApi, type BotMiniAppBundle } from './api'

const props = defineProps<{
  bundle: BotMiniAppBundle
  minimized: boolean
}>()

const emit = defineEmits<{
  minimize: []
  close: []
  sendMessage: [botId: string, text: string]
  dataUpdated: [appId: string]
}>()

const iframeRef = ref<HTMLIFrameElement>()
const wide = ref(false)
const latestData = ref<unknown>(props.bundle.data)
const statusText = ref('')

watch(() => props.bundle.data, value => {
  latestData.value = value
  pushDataToFrame(value)
})

function pushDataToFrame(data: unknown) {
  iframeRef.value?.contentWindow?.postMessage({ source: 'harness-host', type: 'data', data }, '*')
}

async function onMessage(event: MessageEvent) {
  if (!iframeRef.value || event.source !== iframeRef.value.contentWindow) return
  const msg = event.data as { source?: string; id?: string; action?: string; payload?: unknown } | null
  if (!msg || msg.source !== 'harness-mini-app' || !msg.id) return

  const reply = (result?: unknown, error?: string) => {
    iframeRef.value?.contentWindow?.postMessage({ source: 'harness-host', id: msg.id, result, error }, '*')
  }

  try {
    if (msg.action === 'getData') {
      reply(latestData.value)
    } else if (msg.action === 'setData') {
      await botApi.saveMiniAppData(props.bundle.app.id, msg.payload ?? {})
      latestData.value = msg.payload ?? {}
      emit('dataUpdated', props.bundle.app.id)
      statusText.value = '数据已保存'
      window.setTimeout(() => { if (statusText.value === '数据已保存') statusText.value = '' }, 1600)
      reply({ ok: true })
    } else if (msg.action === 'sendMessage') {
      const text = typeof msg.payload === 'string' ? msg.payload.trim() : ''
      if (!text) { reply(undefined, '消息不能为空'); return }
      emit('sendMessage', props.bundle.app.botId, text)
      statusText.value = '已发给 Bot'
      window.setTimeout(() => { if (statusText.value === '已发给 Bot') statusText.value = '' }, 1800)
      reply({ ok: true })
    } else {
      reply(undefined, '不支持的操作')
    }
  } catch (cause) {
    reply(undefined, cause instanceof Error ? cause.message : '执行失败')
  }
}

onMounted(() => window.addEventListener('message', onMessage))
onUnmounted(() => window.removeEventListener('message', onMessage))
</script>

<template>
  <div v-show="!minimized" class="miniapp-overlay" @click.self="emit('minimize')">
    <div class="miniapp-window" :class="{ 'is-wide': wide }" role="dialog" :aria-label="bundle.app.name">
      <header class="miniapp-header">
        <div class="miniapp-title">
          <strong>{{ bundle.app.name }}</strong>
          <span class="miniapp-ver">v{{ bundle.app.version }}</span>
          <span v-if="statusText" class="miniapp-flash">{{ statusText }}</span>
        </div>
        <div class="miniapp-capsule">
          <button type="button" :title="wide ? '切回竖屏' : '切换宽屏看板'" @click="wide = !wide">
            <UiIcon :name="wide ? 'panel' : 'scan'" :size="13" />
          </button>
          <button type="button" title="收起到浮窗" @click="emit('minimize')">
            <UiIcon name="down" :size="13" />
          </button>
          <button type="button" title="关闭小程序" @click="emit('close')">
            <UiIcon name="close" :size="13" />
          </button>
        </div>
      </header>
      <iframe
        ref="iframeRef"
        class="miniapp-frame"
        sandbox="allow-scripts"
        :title="bundle.app.name"
        :srcdoc="bundle.html"
        @load="pushDataToFrame(latestData)"
      ></iframe>
    </div>
  </div>
</template>

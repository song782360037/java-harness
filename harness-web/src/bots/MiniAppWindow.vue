<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import { botApi, miniAppIconName, type BotMiniAppBundle } from './api'

const props = defineProps<{
  bundle: BotMiniAppBundle
  minimized: boolean
  isEditing?: boolean
  botName?: string
  zIndex?: number
}>()

const emit = defineEmits<{
  minimize: []
  close: []
  focus: []
  sendMessage: [botId: string, text: string, appId: string]
  editInChat: [appId: string]
  dataUpdated: [appId: string]
  versionRestored: [appId: string]
  deleteApp: [appId: string]
}>()

const iframeRef = ref<HTMLIFrameElement>()
const wide = ref(false)
const latestData = ref<unknown>(props.bundle.data)
const statusText = ref('')
const sheet = ref<'menu' | 'versions' | null>(null)
const confirmingDelete = ref(false)
const pageError = ref('')
const reloadNonce = ref(0)

// Old version preview state (null = latest)
const previewBundle = ref<BotMiniAppBundle | null>(null)
const activeBundle = computed(() => previewBundle.value || props.bundle)
const shownVersion = computed(() => activeBundle.value.shownVersion || activeBundle.value.app.version)
const isPreviewingOld = computed(() => !!previewBundle.value && shownVersion.value !== props.bundle.app.version)
const versions = computed(() => {
  const list = props.bundle.versions || []
  return [...list].sort((a, b) => b.version - a.version)
})

// Dragging state (non-modal floating window)
const pos = ref({ x: 0, y: 0 })
const dragging = ref(false)
let dragStart = { pointerX: 0, pointerY: 0, origX: 0, origY: 0 }

function onHeaderPointerDown(event: PointerEvent) {
  if ((event.target as HTMLElement)?.closest('button')) return
  emit('focus')
  dragging.value = true
  dragStart = { pointerX: event.clientX, pointerY: event.clientY, origX: pos.value.x, origY: pos.value.y }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onHeaderPointerMove(event: PointerEvent) {
  if (!dragging.value) return
  const dx = event.clientX - dragStart.pointerX
  const dy = event.clientY - dragStart.pointerY
  const maxX = Math.max(200, window.innerWidth - 180)
  const maxY = Math.max(120, window.innerHeight - 120)
  pos.value = {
    x: Math.max(-maxX, Math.min(260, dragStart.origX + dx)),
    y: Math.max(-44, Math.min(maxY, dragStart.origY + dy)),
  }
}

function onHeaderPointerUp(event: PointerEvent) {
  if (!dragging.value) return
  dragging.value = false
  try { (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId) } catch { /* ignore */ }
}

function flash(text: string) {
  statusText.value = text
  window.setTimeout(() => {
    if (statusText.value === text) statusText.value = ''
  }, 2000)
}

watch(() => props.bundle.data, value => {
  latestData.value = value
  pushDataToFrame(value)
})

watch(() => props.bundle.app.version, () => {
  if (previewBundle.value) return
  pageError.value = ''
})

function pushDataToFrame(data: unknown) {
  iframeRef.value?.contentWindow?.postMessage({ source: 'harness-host', type: 'data', data }, '*')
}

async function selectVersion(version: number) {
  sheet.value = null
  pageError.value = ''
  if (version === props.bundle.app.version) {
    previewBundle.value = null
    return
  }
  try {
    previewBundle.value = await botApi.miniAppVersion(props.bundle.app.id, version)
  } catch (cause) {
    flash(cause instanceof Error ? cause.message : '加载历史版本失败')
  }
}

async function restorePreviewVersion() {
  if (!previewBundle.value) return
  try {
    await botApi.restoreMiniApp(props.bundle.app.id, shownVersion.value)
    previewBundle.value = null
    emit('versionRestored', props.bundle.app.id)
    flash('已恢复为新版本')
  } catch (cause) {
    flash(cause instanceof Error ? cause.message : '恢复失败')
  }
}

function exportHtml() {
  sheet.value = null
  const url = botApi.miniAppExportUrl(props.bundle.app.id, shownVersion.value)
  const a = document.createElement('a')
  a.href = url
  a.download = `${props.bundle.app.name}-v${shownVersion.value}.html`
  document.body.appendChild(a)
  a.click()
  a.remove()
  flash('正在导出 HTML')
}

function restartApp() {
  sheet.value = null
  pageError.value = ''
  reloadNonce.value += 1
  flash('已重新加载')
}

function askBotToFix() {
  const err = pageError.value
  pageError.value = ''
  const prompt = `我当前打开的小程序《${props.bundle.app.name}》v${shownVersion.value} 页面出现脚本报错：${err}。请帮我排查并修复后发布新版本。`
  emit('sendMessage', props.bundle.app.botId, prompt, props.bundle.app.id)
  flash('已请求 Bot 修复')
}

function formatVersionTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function onMessage(event: MessageEvent) {
  if (!iframeRef.value || event.source !== iframeRef.value.contentWindow) return
  const msg = event.data as { source?: string; id?: string; action?: string; payload?: unknown } | null
  if (!msg || msg.source !== 'harness-mini-app') return

  if (msg.action === 'error') {
    const text = typeof msg.payload === 'string' ? msg.payload : String(msg.payload || '脚本错误')
    pageError.value = text.slice(0, 260)
    return
  }

  if (!msg.id) return
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
      flash('数据已保存')
      reply({ ok: true })
    } else if (msg.action === 'sendMessage') {
      if (props.minimized) { reply(undefined, '后台收起时不可发送消息'); return }
      const text = typeof msg.payload === 'string' ? msg.payload.trim() : ''
      if (!text) { reply(undefined, '消息不能为空'); return }
      emit('sendMessage', props.bundle.app.botId, text, props.bundle.app.id)
      flash('已发给 Bot')
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
  <div
    v-show="!minimized"
    class="miniapp-floating-wrap"
    :class="{ 'is-wide': wide, 'is-dragging': dragging }"
    :style="{ transform: `translate3d(${pos.x}px, ${pos.y}px, 0)`, zIndex: zIndex || 60 }"
    role="dialog"
    :aria-label="bundle.app.name"
    @pointerdown="emit('focus')"
  >
    <div class="miniapp-window" :class="{ 'is-wide': wide }">
      <header
        class="miniapp-header is-draggable"
        title="按住拖动窗口"
        @pointerdown="onHeaderPointerDown"
        @pointermove="onHeaderPointerMove"
        @pointerup="onHeaderPointerUp"
        @pointercancel="onHeaderPointerUp"
      >
        <div class="miniapp-title">
          <span class="miniapp-tile-dot" :style="{ background: bundle.app.color || '#2d5d9c' }">
            <UiIcon :name="miniAppIconName(bundle.app.icon)" :size="11" />
          </span>
          <strong>{{ bundle.app.name }}</strong>
          <span class="miniapp-ver" :class="{ 'is-old': isPreviewingOld }">v{{ shownVersion }}</span>
          <span v-if="isEditing" class="miniapp-editing-pill">改版中…</span>
          <span v-else-if="statusText" class="miniapp-flash">{{ statusText }}</span>
        </div>
        <div class="miniapp-capsule">
          <button type="button" title="更多操作与历史版本" @click="sheet = sheet === 'menu' ? null : 'menu'">
            <UiIcon name="more" :size="13" />
          </button>
          <button type="button" :title="wide ? '切回竖屏' : '切换宽屏看板'" @click="wide = !wide">
            <UiIcon :name="wide ? 'panel' : 'scan'" :size="13" />
          </button>
          <button type="button" title="收起到浮窗" @click="sheet = null; emit('minimize')">
            <UiIcon name="down" :size="13" />
          </button>
          <button type="button" title="关闭小程序（30 分钟内快速热恢复）" @click="sheet = null; emit('close')">
            <UiIcon name="close" :size="13" />
          </button>
        </div>
      </header>

      <!-- Old version preview bar -->
      <div v-if="isPreviewingOld" class="miniapp-preview-bar">
        <span>正在预览历史版本 v{{ shownVersion }}</span>
        <div class="miniapp-preview-actions">
          <button type="button" class="is-primary" @click="restorePreviewVersion">恢复为此版本</button>
          <button type="button" @click="previewBundle = null">返回最新 v{{ bundle.app.version }}</button>
        </div>
      </div>

      <div class="miniapp-stage">
        <iframe
          :key="`${bundle.app.id}-${shownVersion}-${reloadNonce}`"
          ref="iframeRef"
          class="miniapp-frame"
          sandbox="allow-scripts"
          referrerpolicy="no-referrer"
          :title="bundle.app.name"
          :srcdoc="activeBundle.html"
          @load="pushDataToFrame(latestData)"
        ></iframe>

        <!-- Runtime JS Error Banner -->
        <div v-if="pageError" class="miniapp-error-banner" role="alert">
          <span class="miniapp-error-text" :title="pageError">页面报错：{{ pageError }}</span>
          <button type="button" class="miniapp-error-fix" @click="askBotToFix">让 Bot 修复</button>
          <button type="button" class="miniapp-error-close" aria-label="忽略" @click="pageError = ''">
            <UiIcon name="close" :size="12" />
          </button>
        </div>

        <!-- Menu Bottom Sheet -->
        <div v-if="sheet === 'menu'" class="miniapp-sheet-scrim" @click.self="sheet = null">
          <div class="miniapp-sheet">
            <header class="miniapp-sheet-head">
              <div>
                <strong>{{ bundle.app.name }}</strong>
                <small>{{ botName ? `由 ${botName} 维护 · ` : '' }}当前 v{{ bundle.app.version }}</small>
              </div>
              <button type="button" class="icon-button" @click="sheet = null"><UiIcon name="close" :size="13" /></button>
            </header>
            <div class="miniapp-sheet-actions">
              <button type="button" @click="sheet = null; emit('editInChat', bundle.app.id)">
                <UiIcon name="chat" :size="14" />
                <span>在聊天中让 Bot 改版</span>
              </button>
              <button type="button" @click="sheet = 'versions'">
                <UiIcon name="clock" :size="14" />
                <span>历史版本（{{ Math.max(1, versions.length) }} 个）</span>
              </button>
              <button type="button" @click="restartApp">
                <UiIcon name="refresh" :size="14" />
                <span>刷新重载页面</span>
              </button>
              <button type="button" @click="exportHtml">
                <UiIcon name="download" :size="14" />
                <span>导出独立 HTML</span>
              </button>
              <button type="button" class="is-danger" @click="sheet = null; confirmingDelete = true">
                <UiIcon name="trash" :size="14" />
                <span>删除此小程序</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Versions Bottom Sheet -->
        <div v-if="sheet === 'versions'" class="miniapp-sheet-scrim" @click.self="sheet = null">
          <div class="miniapp-sheet">
            <header class="miniapp-sheet-head">
              <strong>版本历史（共 {{ Math.max(1, versions.length) }} 版）</strong>
              <button type="button" class="icon-button" @click="sheet = null"><UiIcon name="close" :size="13" /></button>
            </header>
            <div class="miniapp-versions-list">
              <button
                v-for="ver in versions"
                :key="ver.version"
                type="button"
                class="miniapp-version-row"
                :class="{ 'is-current': ver.version === shownVersion }"
                @click="selectVersion(ver.version)"
              >
                <span class="miniapp-ver">v{{ ver.version }}</span>
                <div class="miniapp-version-meta">
                  <strong>{{ ver.changeSummary || `版本 v${ver.version}` }}</strong>
                  <small>{{ formatVersionTime(ver.createdAt) }}</small>
                </div>
                <span v-if="ver.version === bundle.app.version" class="miniapp-latest-tag">最新</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Delete Confirmation -->
        <div v-if="confirmingDelete" class="miniapp-sheet-scrim" @click.self="confirmingDelete = false">
          <div class="miniapp-sheet">
            <header class="miniapp-sheet-head">
              <strong>删除小程序「{{ bundle.app.name }}」？</strong>
            </header>
            <p class="miniapp-sheet-body">将同时移除该小程序的所有历史版本与本地数据，操作不可撤销。</p>
            <div class="bot-form-actions">
              <button type="button" @click="confirmingDelete = false">取消</button>
              <button type="button" class="is-danger" @click="confirmingDelete = false; emit('deleteApp', bundle.app.id)">确认删除</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

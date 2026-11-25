<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import UiIcon from '../UiIcon.vue'
import { botApi, type BotLanStatus } from './api'
import { qrSvgData } from './qr'

const emit = defineEmits<{ busy: [value: boolean] }>()

const status = ref<BotLanStatus | null>(null)
const enabled = ref(false)
const port = ref(4317)
const allowRemoteApproval = ref(false)
const selectedUrl = ref('')
const pairCode = ref('')
const deviceName = ref('')
const loading = ref(true)
const saving = ref(false)
const pairing = ref(false)
const revokingId = ref('')
const copied = ref(false)
const error = ref('')
const notice = ref('')
let pollTimer: number | undefined

const isDirty = computed(() => {
  const s = status.value?.settings
  if (!s) return false
  return enabled.value !== s.enabled || Number(port.value) !== s.port || allowRemoteApproval.value !== s.allowRemoteApproval
})

const activeUrl = computed(() => {
  const urls = status.value?.urls || []
  if (!urls.length) return ''
  return urls.includes(selectedUrl.value) ? selectedUrl.value : urls[0]
})

const qr = computed(() => {
  if (!activeUrl.value) return null
  try {
    return qrSvgData(activeUrl.value)
  } catch {
    return null
  }
})

function syncDraft(next: BotLanStatus, force = false) {
  const hadDraft = status.value !== null && isDirty.value
  status.value = next
  if (force || !hadDraft) {
    enabled.value = next.settings.enabled
    port.value = next.settings.port
    allowRemoteApproval.value = next.settings.allowRemoteApproval
  }
  if (next.urls.length && !next.urls.includes(selectedUrl.value)) {
    selectedUrl.value = next.urls[0]
  }
}

async function refresh(force = false) {
  try {
    const next = await botApi.lanStatus()
    syncDraft(next, force)
  } catch (cause) {
    if (force) error.value = cause instanceof Error ? cause.message : '读取局域网状态失败'
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  if (!status.value || saving.value) return
  saving.value = true
  emit('busy', true)
  error.value = ''
  notice.value = ''
  try {
    const next = await botApi.updateLanSettings({
      enabled: enabled.value,
      port: Number(port.value) || 4317,
      allowRemoteApproval: allowRemoteApproval.value,
      revision: status.value.settings.revision,
    })
    syncDraft(next, true)
    notice.value = next.running
      ? `局域网伴侣服务已启动（端口 ${next.actualPort}），手机扫码即可配对。`
      : '局域网伴侣服务已关闭，主工作台保持仅本机 127.0.0.1 访问。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存局域网设置失败'
  } finally {
    saving.value = false
    emit('busy', false)
  }
}

async function confirmPair() {
  if (pairing.value) return
  const code = pairCode.value.trim()
  if (!/^\d{4}$/.test(code)) {
    error.value = '请输入手机屏幕上显示的 4 位数字配对码'
    return
  }
  pairing.value = true
  emit('busy', true)
  error.value = ''
  notice.value = ''
  try {
    const device = await botApi.confirmLanPairing({ code, deviceName: deviceName.value.trim() })
    pairCode.value = ''
    deviceName.value = ''
    notice.value = `设备「${device.name}」已配对成功。`
    await refresh(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '配对确认失败'
  } finally {
    pairing.value = false
    emit('busy', false)
  }
}

async function revoke(id: string, name: string) {
  if (revokingId.value) return
  revokingId.value = id
  emit('busy', true)
  error.value = ''
  notice.value = ''
  try {
    await botApi.revokeLanDevice(id)
    notice.value = `已移除设备「${name}」，其局域网令牌立即失效。`
    await refresh(true)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '移除设备失败'
  } finally {
    revokingId.value = ''
    emit('busy', false)
  }
}

async function copyUrl() {
  if (!activeUrl.value) return
  try {
    await navigator.clipboard.writeText(activeUrl.value)
    copied.value = true
    window.setTimeout(() => { copied.value = false }, 1800)
  } catch {
    error.value = '复制失败，请手动选中地址复制'
  }
}

function formatTime(iso: string | null) {
  if (!iso) return '从未'
  try {
    return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch {
    return iso
  }
}

onMounted(() => {
  void refresh(true)
  pollTimer = window.setInterval(() => {
    if (!saving.value && !pairing.value && !revokingId.value) void refresh(false)
  }, 2000)
})

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<template>
  <section class="bot-lan-settings" aria-label="局域网移动伴侣与设备管理">
    <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
    <p v-if="notice" role="status" class="saved-note">{{ notice }}</p>

    <div class="bot-lan-card">
      <div class="bot-lan-card-head">
        <div>
          <h2>局域网移动伴侣</h2>
          <p>按需开启独立端口供同一 Wi-Fi 下的手机或平板使用 Bot 团队、查看定时任务和打开小程序。桌面主端口始终仅限本机 <code>127.0.0.1</code> 访问。</p>
        </div>
        <span class="bot-lan-pill" :class="{ running: status?.running }">
          {{ status?.running ? `运行中 · :${status.actualPort}` : '已关闭' }}
        </span>
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-lan-enabled">开启局域网访问</label>
          <p id="bot-lan-enabled-help">默认关闭；随时开关立即生效，无需重启应用</p>
        </div>
        <input id="bot-lan-enabled" v-model="enabled" type="checkbox" :disabled="loading || saving" aria-describedby="bot-lan-enabled-help" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-lan-port">独立服务端口</label>
          <p id="bot-lan-port-help">范围 1024–65535，默认 4317（与主服务端口隔离）</p>
        </div>
        <input id="bot-lan-port" v-model.number="port" class="bot-lan-port-input" type="number" min="1024" max="65535" :disabled="loading || saving" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-lan-approval">允许手机端审批 Bot 后台任务命令</label>
          <p id="bot-lan-approval-help">默认关闭；开启后也仅限审批 Bot 后台任务触发的待批命令，无法触及普通开发会话</p>
        </div>
        <input id="bot-lan-approval" v-model="allowRemoteApproval" type="checkbox" :disabled="loading || saving" aria-describedby="bot-lan-approval-help" />
      </div>

      <div class="bot-lan-actions">
        <button type="button" class="primary" :disabled="loading || saving || !isDirty" @click="saveSettings">
          {{ saving ? '应用中…' : '保存并应用' }}
        </button>
      </div>
    </div>

    <div v-if="status?.running" class="bot-lan-card bot-lan-connect-card">
      <div class="bot-lan-qr-pane">
        <svg
          v-if="qr"
          class="bot-lan-qr-svg"
          :viewBox="`0 0 ${qr.viewBoxSize} ${qr.viewBoxSize}`"
          role="img"
          :aria-label="`扫码打开 ${activeUrl}`"
          shape-rendering="crispEdges"
        >
          <rect width="100%" height="100%" fill="#ffffff" />
          <path :d="qr.path" fill="#111827" />
        </svg>
        <small>同一局域网手机扫码打开</small>
      </div>

      <div class="bot-lan-connect-info">
        <h3>连接地址</h3>
        <div v-if="status.urls.length > 1" class="bot-lan-nic-picker">
          <label for="bot-lan-nic">本机网卡地址</label>
          <select id="bot-lan-nic" v-model="selectedUrl">
            <option v-for="url in status.urls" :key="url" :value="url">{{ url }}</option>
          </select>
        </div>
        <div class="bot-lan-url-row">
          <code>{{ activeUrl || '未检测到局域网 IP' }}</code>
          <button type="button" :disabled="!activeUrl" @click="copyUrl">
            <UiIcon :name="copied ? 'check' : 'copy'" :size="14" />
            <span>{{ copied ? '已复制' : '复制' }}</span>
          </button>
        </div>
        <ol class="bot-lan-steps">
          <li>确保手机与当前电脑连接在同一 Wi-Fi / 局域网。</li>
          <li>用手机相机或浏览器打开上方二维码或地址，点击「获取配对码」。</li>
          <li>在下方的配对框中输入手机屏幕上的 <strong>4 位数字</strong> 完成信任授权。</li>
        </ol>
      </div>
    </div>

    <div v-if="status?.running" class="bot-lan-card" :class="{ 'is-waiting-pair': !!status.pendingPairing }">
      <div class="bot-lan-card-head">
        <div>
          <h3>配对新设备</h3>
          <p v-if="status.pendingPairing">
            收到来自 <strong>{{ status.pendingPairing.userAgent || '移动设备' }}</strong> 的配对请求，请在 2 分钟内输入手机上显示的 4 位配对码。
          </p>
          <p v-else>等待手机发起配对请求… 在手机页面点击「获取配对码」后此处会自动亮起。</p>
        </div>
        <span v-if="status.pendingPairing" class="bot-lan-pill running">等待确认</span>
      </div>
      <form class="bot-lan-pair-form" @submit.prevent="confirmPair">
        <input
          v-model="pairCode"
          type="text"
          inputmode="numeric"
          maxlength="4"
          placeholder="4 位配对码"
          class="bot-lan-code-input"
          :disabled="pairing"
        />
        <input
          v-model="deviceName"
          type="text"
          maxlength="80"
          placeholder="设备备注名（可选，如 我的 iPhone）"
          class="bot-lan-name-input"
          :disabled="pairing"
        />
        <button type="submit" class="primary" :disabled="pairing || pairCode.trim().length !== 4">
          {{ pairing ? '确认中…' : '确认配对' }}
        </button>
      </form>
    </div>

    <div class="bot-lan-card">
      <div class="bot-lan-card-head">
        <div>
          <h3>已配对设备（{{ status?.devices.length ?? 0 }}）</h3>
          <p>仅在本地数据库保存设备令牌的 SHA-256 哈希；移除设备后手机端 Cookie 立即失效。</p>
        </div>
      </div>

      <div v-if="status?.devices.length" class="bot-lan-devices">
        <div v-for="dev in status.devices" :key="dev.id" class="bot-lan-device-row">
          <div class="bot-lan-device-meta">
            <strong>{{ dev.name }}</strong>
            <small>{{ dev.userAgent || '未知客户端' }}</small>
            <small>配对：{{ formatTime(dev.createdAt) }} · 最近活跃：{{ formatTime(dev.lastSeenAt) }}</small>
          </div>
          <button type="button" class="bot-lan-revoke-btn" :disabled="revokingId === dev.id" @click="revoke(dev.id, dev.name)">
            {{ revokingId === dev.id ? '移除中…' : '移除设备' }}
          </button>
        </div>
      </div>
      <p v-else class="bot-lan-empty">暂无已配对设备。</p>
    </div>
  </section>
</template>

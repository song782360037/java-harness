<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import UiIcon from '../UiIcon.vue'
import { botApi, type BotLimits, type BotWebSearchSettings } from './api'

const emit = defineEmits<{ busy: [value: boolean] }>()

const searchState = ref<BotWebSearchSettings | null>(null)
const limitsState = ref<BotLimits | null>(null)
const loading = ref(true)
const savingSearch = ref(false)
const savingLimits = ref(false)
const error = ref('')
const notice = ref('')
const showKey = ref(false)

const searchForm = reactive({
  provider: 'DUCKDUCKGO',
  endpointUrl: '',
  apiKey: '',
  clearApiKey: false,
  fallbackFreeEnabled: true,
  maxResults: 6,
  timeoutSeconds: 15,
})

const limitsForm = reactive({
  maxConcurrentTasks: 3,
  taskTimeoutMinutes: 15,
  commandTimeoutSeconds: 300,
  maxFactsPerScope: 50,
  modelSilenceSeconds: 300,
})

const needsApiKey = computed(() => ['TAVILY', 'BRAVE', 'DEEPSEEK', 'CUSTOM'].includes(searchForm.provider))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [search, limits] = await Promise.all([botApi.webSearchSettings(), botApi.limits()])
    searchState.value = search
    searchForm.provider = search.provider
    searchForm.endpointUrl = search.endpointUrl
    searchForm.apiKey = ''
    searchForm.clearApiKey = false
    searchForm.fallbackFreeEnabled = search.fallbackFreeEnabled
    searchForm.maxResults = search.maxResults
    searchForm.timeoutSeconds = search.timeoutSeconds

    limitsState.value = limits
    limitsForm.maxConcurrentTasks = limits.maxConcurrentTasks
    limitsForm.taskTimeoutMinutes = limits.taskTimeoutMinutes
    limitsForm.commandTimeoutSeconds = limits.commandTimeoutSeconds
    limitsForm.maxFactsPerScope = limits.maxFactsPerScope
    limitsForm.modelSilenceSeconds = limits.modelSilenceSeconds ?? 300
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载 Bot 配置失败'
  } finally {
    loading.value = false
  }
}

async function saveSearch() {
  if (!searchState.value || savingSearch.value) return
  savingSearch.value = true
  emit('busy', true)
  error.value = ''
  notice.value = ''
  try {
    const saved = await botApi.saveWebSearchSettings({
      provider: searchForm.provider,
      endpointUrl: searchForm.endpointUrl.trim(),
      apiKey: searchForm.apiKey.trim(),
      clearApiKey: searchForm.clearApiKey,
      fallbackFreeEnabled: searchForm.fallbackFreeEnabled,
      maxResults: Number(searchForm.maxResults) || 6,
      timeoutSeconds: Number(searchForm.timeoutSeconds) || 15,
      revision: searchState.value.revision,
    })
    searchState.value = saved
    searchForm.apiKey = ''
    searchForm.clearApiKey = false
    notice.value = 'Bot 联网搜索设置已保存并加密落盘。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存联网搜索设置失败'
  } finally {
    savingSearch.value = false
    emit('busy', false)
  }
}

async function saveLimits() {
  if (!limitsState.value || savingLimits.value) return
  savingLimits.value = true
  emit('busy', true)
  error.value = ''
  notice.value = ''
  try {
    const saved = await botApi.saveLimits({
      maxConcurrentTasks: Number(limitsForm.maxConcurrentTasks) || 3,
      taskTimeoutMinutes: Number(limitsForm.taskTimeoutMinutes) || 15,
      commandTimeoutSeconds: Number(limitsForm.commandTimeoutSeconds) || 300,
      maxFactsPerScope: Number(limitsForm.maxFactsPerScope) || 50,
      modelSilenceSeconds: Number(limitsForm.modelSilenceSeconds) || 300,
      revision: limitsState.value.revision,
    })
    limitsState.value = saved
    notice.value = 'Bot 运行限流与超时参数已保存并立即生效。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存运行限流设置失败'
  } finally {
    savingLimits.value = false
    emit('busy', false)
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="bot-lan-settings" aria-label="Bot 联网搜索与运行配置">
    <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
    <p v-if="notice" role="status" class="saved-note">{{ notice }}</p>

    <form class="bot-lan-card" @submit.prevent="saveSearch">
      <div class="bot-lan-card-head">
        <div>
          <h2>Bot 联网搜索（web_search）</h2>
          <p>供 Bot 后台任务检索公开网页。API Key 使用本地 AES-GCM 密钥加密保存；网页正文抓取 <code>web_fetch</code> 默认内建 SSRF 内网防护。</p>
        </div>
        <span class="bot-lan-pill" :class="{ running: searchForm.provider !== 'NONE' || searchForm.fallbackFreeEnabled }">
          {{ searchForm.provider === 'NONE' ? (searchForm.fallbackFreeEnabled ? '免 Key 兜底' : '已关闭') : searchForm.provider }}
        </span>
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-search-provider">搜索引擎</label>
          <p>支持 Tavily、Brave、DeepSeek Search（可自动复用已配 DeepSeek 提供商 Key）、自定义 HTTP 接口或免 Key 搜索</p>
        </div>
        <select id="bot-search-provider" v-model="searchForm.provider" :disabled="loading || savingSearch">
          <option value="DUCKDUCKGO">DuckDuckGo（免 Key 默认）</option>
          <option value="TAVILY">Tavily Search API</option>
          <option value="BRAVE">Brave Search API</option>
          <option value="DEEPSEEK">DeepSeek Search API</option>
          <option value="CUSTOM">自定义 JSON 搜索接口</option>
          <option value="NONE">关闭主搜索引擎</option>
        </select>
      </div>

      <div v-if="searchForm.provider === 'CUSTOM'" class="preference-row">
        <div class="preference-copy">
          <label for="bot-search-endpoint">自定义接口 URL</label>
          <p>接收 POST <code>{"query","count"}</code>，返回 <code>{"results":[{"title","url","snippet"}]}</code></p>
        </div>
        <input
          id="bot-search-endpoint"
          v-model="searchForm.endpointUrl"
          type="url"
          placeholder="https://search.example.com/api"
          :disabled="loading || savingSearch"
        />
      </div>

      <div v-if="needsApiKey" class="preference-row">
        <div class="preference-copy">
          <label for="bot-search-key">
            搜索 API Key
            <small v-if="searchState?.apiKeyConfigured">（当前已保存 {{ searchState.apiKeyHint }}）</small>
            <small v-else-if="searchForm.provider === 'DEEPSEEK' && searchState?.deepSeekKeyAvailable">（留空将自动复用已配置的 DeepSeek 提供商 Key）</small>
          </label>
          <p>留空保持原 Key 不变；勾选清除可移除已存密钥</p>
        </div>
        <div class="bot-key-field">
          <input
            id="bot-search-key"
            v-model="searchForm.apiKey"
            :type="showKey ? 'text' : 'password'"
            autocomplete="new-password"
            placeholder="输入新的 API Key…"
            :disabled="loading || savingSearch || searchForm.clearApiKey"
          />
          <button type="button" class="icon-button" :aria-label="showKey ? '隐藏' : '显示'" @click="showKey = !showKey">
            <UiIcon :name="showKey ? 'eye' : 'eye-off'" :size="15" />
          </button>
        </div>
      </div>

      <div v-if="needsApiKey && searchState?.apiKeyConfigured" class="preference-row">
        <div class="preference-copy">
          <label for="bot-search-clear-key">清除已保存的搜索 API Key</label>
          <p>保存后将删除本地加密存储的搜索密钥</p>
        </div>
        <input id="bot-search-clear-key" v-model="searchForm.clearApiKey" type="checkbox" :disabled="loading || savingSearch" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-search-fallback">允许免 Key 免费搜索兜底</label>
          <p>当未填 Key 或主搜索引擎异常时，自动回退到免 Key 搜索避免任务卡壳</p>
        </div>
        <input id="bot-search-fallback" v-model="searchForm.fallbackFreeEnabled" type="checkbox" :disabled="loading || savingSearch" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-search-results">单次最大结果数（1–10）</label>
          <p>控制搜索结果条数以平衡上下文长度</p>
        </div>
        <input id="bot-search-results" v-model.number="searchForm.maxResults" class="bot-lan-port-input" type="number" min="1" max="10" :disabled="loading || savingSearch" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-search-timeout">联网请求超时（秒，5–45）</label>
          <p>同时应用于 web_search 与 web_fetch</p>
        </div>
        <input id="bot-search-timeout" v-model.number="searchForm.timeoutSeconds" class="bot-lan-port-input" type="number" min="5" max="45" :disabled="loading || savingSearch" />
      </div>

      <div class="bot-lan-actions">
        <button type="submit" class="primary" :disabled="loading || savingSearch">
          {{ savingSearch ? '保存中…' : '保存联网设置' }}
        </button>
      </div>
    </form>

    <form class="bot-lan-card" @submit.prevent="saveLimits">
      <div class="bot-lan-card-head">
        <div>
          <h2>Bot 后台任务精细化限流与超时</h2>
          <p>控制后台任务并发排队、总时长上限、单条命令超时保护以及长期记忆条数上限。</p>
        </div>
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-limit-concurrent">最大并发后台任务数（1–10）</label>
          <p>超出上限的新后台任务自动进入排队（QUEUED），有空位即自动拉起</p>
        </div>
        <input id="bot-limit-concurrent" v-model.number="limitsForm.maxConcurrentTasks" class="bot-lan-port-input" type="number" min="1" max="10" :disabled="loading || savingLimits" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-limit-task-min">单个后台任务总超时（分钟，1–120）</label>
          <p>超时后自动中止该后台任务，防止无限空转</p>
        </div>
        <input id="bot-limit-task-min" v-model.number="limitsForm.taskTimeoutMinutes" class="bot-lan-port-input" type="number" min="1" max="120" :disabled="loading || savingLimits" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-limit-cmd-sec">单条命令最大超时（秒，10–1800）</label>
          <p>默认 300 秒（5 分钟）；超时仅中止该条命令，后台任务可继续收尾</p>
        </div>
        <input id="bot-limit-cmd-sec" v-model.number="limitsForm.commandTimeoutSeconds" class="bot-lan-port-input" type="number" min="10" max="1800" :disabled="loading || savingLimits" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-limit-silence-sec">模型连续无响应超时（秒，10–3600）</label>
          <p>默认 300 秒；聊天或后台任务连续无新输出/工具推进时自动中止并保留已输出内容，等待命令审批期间自动暂停计时</p>
        </div>
        <input id="bot-limit-silence-sec" v-model.number="limitsForm.modelSilenceSeconds" class="bot-lan-port-input" type="number" min="10" max="3600" :disabled="loading || savingLimits" />
      </div>

      <div class="preference-row">
        <div class="preference-copy">
          <label for="bot-limit-facts">每范围长期记忆条数上限（5–200）</label>
          <p>用户共享记忆与每个 Bot 专属记忆分别计数，超出后淘汰最早条目</p>
        </div>
        <input id="bot-limit-facts" v-model.number="limitsForm.maxFactsPerScope" class="bot-lan-port-input" type="number" min="5" max="200" :disabled="loading || savingLimits" />
      </div>

      <div class="bot-lan-actions">
        <button type="submit" class="primary" :disabled="loading || savingLimits">
          {{ savingLimits ? '保存中…' : '保存运行限流' }}
        </button>
      </div>
    </form>
  </section>
</template>

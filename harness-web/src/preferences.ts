import { readonly, ref } from 'vue'

export interface Preferences {
  sendShortcut: 'modifier-enter' | 'enter'
  // 沿用旧存储字段，既有字号选择现在作用于整个工作台。
  chatFontSize: 'small' | 'standard' | 'large'
  autoScroll: boolean
}
const key = 'harness.preferences'

// 逐项校验浏览器偏好，旧值或无效值恢复为当前默认值。
export function normalizePreferences(value: unknown): Preferences {
  const saved = value && typeof value === 'object' ? value as Partial<Preferences> : {}
  return {
    sendShortcut: saved.sendShortcut === 'enter' ? 'enter' : 'modifier-enter',
    chatFontSize: saved.chatFontSize === 'small' || saved.chatFontSize === 'large' ? saved.chatFontSize : 'standard',
    autoScroll: typeof saved.autoScroll === 'boolean' ? saved.autoScroll : true,
  }
}
const state = ref(normalizePreferences(null))
const persistent = ref(true)
let storage: Storage | undefined
try {
  storage = window.localStorage
  state.value = normalizePreferences(JSON.parse(storage.getItem(key) || 'null'))
} catch { persistent.value = false }

export const preferences = readonly(state)
export const preferencesPersistent = readonly(persistent)

// 立即应用偏好；存储不可用时仍保留当前页面的选择。
export function updatePreferences(value: Partial<Preferences>) {
  state.value = normalizePreferences({ ...state.value, ...value })
  try { window.localStorage.setItem(key, JSON.stringify(state.value)); persistent.value = true }
  catch { persistent.value = false }
}

// 同站点的其他标签页同步偏好，清除存储时恢复默认值。
function syncPreferences(event: StorageEvent) {
  if (event.storageArea !== storage || (event.key !== key && event.key !== null)) return
  try { state.value = normalizePreferences(JSON.parse(event.newValue || 'null')); persistent.value = true }
  catch { state.value = normalizePreferences(null) }
}
window.addEventListener('storage', syncPreferences)
if (import.meta.hot) import.meta.hot.dispose(() => window.removeEventListener('storage', syncPreferences))

// 输入法确认、换行和长按回车不触发发送。
export function shouldSendPrompt(event: Pick<KeyboardEvent, 'key' | 'keyCode' | 'isComposing' | 'repeat' | 'shiftKey' | 'altKey' | 'ctrlKey' | 'metaKey'>, shortcut: Preferences['sendShortcut']) {
  if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229 || event.repeat || event.shiftKey || event.altKey) return false
  return shortcut === 'enter' ? !event.ctrlKey && !event.metaKey : event.ctrlKey || event.metaKey
}

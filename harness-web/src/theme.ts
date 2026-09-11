import { readonly, ref } from 'vue'

export type ThemePreference = 'light' | 'dark' | 'system'
declare global {
  interface Window {
    harnessTheme: {
      readonly preference: ThemePreference
      readonly resolved: 'light' | 'dark'
      readonly persistent: boolean
      setPreference(value: ThemePreference): void
      subscribe(listener: () => void): () => void
    }
  }
}
const controller = window.harnessTheme
const preference = ref(controller.preference)
const resolved = ref(controller.resolved)
const persistent = ref(controller.persistent)
const unsubscribe = controller.subscribe(() => {
  preference.value = controller.preference
  resolved.value = controller.resolved
  persistent.value = controller.persistent
})
if (import.meta.hot) import.meta.hot.dispose(unsubscribe)
export const themePreference = readonly(preference)
export const resolvedTheme = readonly(resolved)
export const themePersistent = readonly(persistent)
export const setTheme = (value: ThemePreference) => controller.setPreference(value)

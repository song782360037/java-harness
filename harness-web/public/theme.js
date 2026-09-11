// Runs before the app stylesheet to restore the theme without a light-page flash.
(() => {
  const key = 'harness.theme'
  const normalize = value => ['light', 'dark', 'system'].includes(value) ? value : 'system'
  const system = window.matchMedia('(prefers-color-scheme: dark)')
  const listeners = new Set()
  let preference = 'system'
  let persistent = true
  let storage
  try { storage = window.localStorage; preference = normalize(storage.getItem(key)) }
  catch { persistent = false }
  const resolved = () => preference === 'system' ? (system.matches ? 'dark' : 'light') : preference
  function apply() {
    document.documentElement.dataset.theme = resolved()
    document.documentElement.style.colorScheme = resolved()
    for (const listener of listeners) listener()
  }
  window.harnessTheme = Object.freeze({
    get preference() { return preference },
    get resolved() { return resolved() },
    get persistent() { return persistent },
    setPreference(value) {
      preference = normalize(value)
      try { window.localStorage.setItem(key, preference); persistent = true }
      catch { persistent = false }
      apply()
    },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener) },
  })
  system.addEventListener('change', () => { if (preference === 'system') apply() })
  window.addEventListener('storage', event => {
    if (event.storageArea !== storage || (event.key !== key && event.key !== null)) return
    preference = normalize(event.newValue)
    persistent = true
    apply()
  })
  apply()
})()

// Runs before the app stylesheet to restore the theme without a light-page flash.
(() => {
  const key = 'harness.theme'
  const fallback = 'light'
  const schemes = {
    pure: 'light',
    light: 'light',
    paper: 'light',
    ocean: 'light',
    sakura: 'light',
    dark: 'dark',
    midnight: 'dark',
    espresso: 'dark',
    nebula: 'dark',
  }
  const hasTheme = value => typeof value === 'string' && Object.prototype.hasOwnProperty.call(schemes, value)
  const normalize = value => hasTheme(value) ? value : fallback
  const listeners = new Set()
  let preference = fallback
  let persistent = true
  let storage
  try { storage = window.localStorage; preference = normalize(storage.getItem(key)) }
  catch { persistent = false }
  const colorScheme = () => schemes[preference] || 'light'
  function apply() {
    const scheme = colorScheme()
    document.documentElement.dataset.theme = preference
    document.documentElement.dataset.colorScheme = scheme
    document.documentElement.style.colorScheme = scheme
    for (const listener of listeners) listener()
  }
  window.harnessTheme = Object.freeze({
    get preference() { return preference },
    get resolved() { return preference },
    get colorScheme() { return colorScheme() },
    get persistent() { return persistent },
    setPreference(value) {
      preference = normalize(value)
      try { window.localStorage.setItem(key, preference); persistent = true }
      catch { persistent = false }
      apply()
    },
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener) },
  })
  window.addEventListener('storage', event => {
    if (event.storageArea !== storage || (event.key !== key && event.key !== null)) return
    preference = normalize(event.newValue)
    persistent = true
    apply()
  })
  apply()
})()

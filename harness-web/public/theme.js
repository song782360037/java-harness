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
  const normalize = value => (value === 'system' || hasTheme(value)) ? value : fallback
  const mediaQuery = typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null
  const listeners = new Set()
  let preference = fallback
  let persistent = true
  let storage
  try { storage = window.localStorage; preference = normalize(storage.getItem(key)) }
  catch { persistent = false }
  const resolve = () => {
    if (preference === 'system') return mediaQuery && mediaQuery.matches ? 'dark' : 'light'
    return preference
  }
  const colorScheme = () => schemes[resolve()] || 'light'
  function apply() {
    const resolvedTheme = resolve()
    const scheme = colorScheme()
    document.documentElement.dataset.theme = resolvedTheme
    document.documentElement.dataset.colorScheme = scheme
    document.documentElement.style.colorScheme = scheme
    for (const listener of listeners) listener()
  }
  window.harnessTheme = Object.freeze({
    get preference() { return preference },
    get resolved() { return resolve() },
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
  if (mediaQuery) {
    const onSystemChange = () => { if (preference === 'system') apply() }
    if (typeof mediaQuery.addEventListener === 'function') mediaQuery.addEventListener('change', onSystemChange)
    else if (typeof mediaQuery.addListener === 'function') mediaQuery.addListener(onSystemChange)
  }
  window.addEventListener('storage', event => {
    if (event.storageArea !== storage || (event.key !== key && event.key !== null)) return
    preference = normalize(event.newValue)
    persistent = true
    apply()
  })
  apply()
})()

import fs from 'node:fs'
import path from 'node:path'

export const STARTUP_ANIMATION_STYLES = ['orbital', 'prism', 'terminal']
export const DEFAULT_STARTUP_PREFERENCES = Object.freeze({
  enabled: true,
  style: 'orbital',
})

/** 规范化启动动画设置；首次启动或配置损坏时回退到默认开启与星轨脉冲风格。 */
export function normalizeStartupPreferences(value) {
  const saved = value && typeof value === 'object' ? value : {}
  return {
    enabled: typeof saved.enabled === 'boolean' ? saved.enabled : DEFAULT_STARTUP_PREFERENCES.enabled,
    style: STARTUP_ANIMATION_STYLES.includes(saved.style) ? saved.style : DEFAULT_STARTUP_PREFERENCES.style,
  }
}

/** 根据加载页已展示时长决定进入工作台后的衔接模式，避免冷启动播放两遍完整动画。 */
export function resolveDesktopSplashMode(enabled, loadingElapsedMs, handoffThresholdMs = 900) {
  if (!enabled) return 'none'
  return loadingElapsedMs >= handoffThresholdMs ? 'handoff' : 'full'
}

/** 在用户目录持久化桌面启动动画偏好，避免每次随机端口导致 localStorage 丢失。 */
export function createStartupPreferencesStore(userDataDir) {
  const file = path.join(userDataDir, 'startup-animation.json')
  let state = { ...DEFAULT_STARTUP_PREFERENCES }
  try {
    state = normalizeStartupPreferences(JSON.parse(fs.readFileSync(file, 'utf8')))
  } catch {
    // 首次启动尚未写入配置文件，使用默认开启状态。
  }

  return {
    file,
    snapshot() {
      return { ...state }
    },
    save(patch) {
      if (!patch || typeof patch !== 'object') throw new Error('无效的启动动画设置')
      if ('enabled' in patch && typeof patch.enabled !== 'boolean') throw new Error('无效的启动动画开关')
      if ('style' in patch && !STARTUP_ANIMATION_STYLES.includes(patch.style)) throw new Error('无效的启动动画风格')
      const next = normalizeStartupPreferences({ ...state, ...patch })
      fs.mkdirSync(path.dirname(file), { recursive: true })
      fs.writeFileSync(file, JSON.stringify(next, null, 2), 'utf8')
      state = next
      return { ...state }
    },
  }
}

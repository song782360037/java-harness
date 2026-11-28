export type BotStatus = 'FAILED' | 'REPLYING' | 'WORKING' | 'QUEUED' | 'IDLE'

export interface BotExperience {
  xp: number
  level: number
  tier: 'NOVICE' | 'ADEPT' | 'SENIOR' | 'EXPERT' | 'MASTER'
  currentLevelXp: number
  nextLevelXp: number
  progress: number
}

export function botStatusLabel(status: BotStatus): string {
  switch (status) {
    case 'FAILED': return '任务失败'
    case 'REPLYING': return '回复中…'
    case 'WORKING': return '后台干活中'
    case 'QUEUED': return '消息排队中'
    default: return '空闲'
  }
}

export function botTierLabel(tier: BotExperience['tier']): string {
  switch (tier) {
    case 'MASTER': return '满级大师'
    case 'EXPERT': return '专家'
    case 'SENIOR': return '资深'
    case 'ADEPT': return '熟练'
    default: return '新手'
  }
}

export interface BotMentionMatch {
  name: string
  botId: string
}

export interface BotAppMatch {
  name: string
  appId: string
}

export interface BotAttachmentMeta {
  id: string
  name: string
  mediaType: string
  size: number
  libraryPath?: string
}

export function isImageMediaType(mediaType: string | null | undefined): boolean {
  return !!mediaType && mediaType.toLowerCase().startsWith('image/')
}

export function parseAttachments(raw: string | null | undefined): BotAttachmentMeta[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((item): item is BotAttachmentMeta => !!item && typeof item === 'object' && typeof item.id === 'string')
  } catch {
    return []
  }
}

export function formatElapsed(iso: string | null | undefined, nowMs = Date.now()): string {
  if (!iso) return ''
  const started = new Date(iso).getTime()
  if (Number.isNaN(started)) return ''
  const sec = Math.max(0, Math.floor((nowMs - started) / 1000))
  if (sec < 60) return `${sec}s`
  const min = Math.floor(sec / 60)
  const rem = sec % 60
  if (min < 60) return `${min}m ${rem}s`
  return `${Math.floor(min / 60)}h ${min % 60}m`
}

export interface BotFlowMatch {
  name: string
  flowId: string
}

export interface BotSkillMatch {
  name: string
  target: string
}

const BOT_MENTION_RE = /\[([^\]]+)\]\(bot:([a-zA-Z0-9-]+)\)/g
const BOT_APP_RE = /\[([^\]]+)\]\(app:([a-zA-Z0-9_-]+)\)/g
const BOT_FLOW_RE = /\[([^\]]+)\]\(flow:([a-zA-Z0-9_-]+)\)/g
const BOT_SKILL_RE = /\[([^\]]+)\]\(skill:([a-zA-Z0-9._/-]+)\)/g

export function miniAppIconName(icon: string | null | undefined): string {
  switch ((icon || '').toLowerCase()) {
    case 'chart': return 'chart'
    case 'table': return 'table'
    case 'calendar': return 'calendar'
    case 'doc': return 'file'
    case 'check': return 'check'
    case 'clock': return 'clock'
    case 'team': return 'users'
    case 'chat': return 'chat'
    case 'folder': return 'folder'
    case 'cube': return 'cube'
    default: return 'sparkles'
  }
}

export function extractBotSkills(content: string): BotSkillMatch[] {
  const seen = new Set<string>()
  const matches: BotSkillMatch[] = []
  for (const match of content.matchAll(BOT_SKILL_RE)) {
    const target = match[2]
    if (!seen.has(target)) {
      seen.add(target)
      matches.push({ name: match[1], target })
    }
  }
  return matches
}

export function formatTimeDivider(iso: string, nowMs = Date.now()): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date(nowMs)
  const hhmm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const startOf = (dt: Date) => new Date(dt.getFullYear(), dt.getMonth(), dt.getDate()).getTime()
  const diffDays = Math.round((startOf(now) - startOf(d)) / 86_400_000)
  if (diffDays === 0) return `今天 ${hhmm}`
  if (diffDays === 1) return `昨天 ${hhmm}`
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日 ${hhmm}`
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${hhmm}`
}

export function shouldShowTimeDivider(prevIso: string | null | undefined, currIso: string): boolean {
  if (!prevIso) return true
  const prev = new Date(prevIso)
  const curr = new Date(currIso)
  if (Number.isNaN(prev.getTime()) || Number.isNaN(curr.getTime())) return false
  if (prev.getDate() !== curr.getDate() || prev.getMonth() !== curr.getMonth() || prev.getFullYear() !== curr.getFullYear()) {
    return true
  }
  return curr.getTime() - prev.getTime() >= 15 * 60_000
}

export function extractBotMentions(content: string): BotMentionMatch[] {
  const seen = new Set<string>()
  const matches: BotMentionMatch[] = []
  for (const match of content.matchAll(BOT_MENTION_RE)) {
    const botId = match[2]
    if (!seen.has(botId)) {
      seen.add(botId)
      matches.push({ name: match[1], botId })
    }
  }
  return matches
}

export function extractBotApps(content: string): BotAppMatch[] {
  const seen = new Set<string>()
  const matches: BotAppMatch[] = []
  for (const match of content.matchAll(BOT_APP_RE)) {
    const appId = match[2]
    if (!seen.has(appId)) {
      seen.add(appId)
      matches.push({ name: match[1], appId })
    }
  }
  return matches
}

export function extractBotFlows(content: string): BotFlowMatch[] {
  const seen = new Set<string>()
  const matches: BotFlowMatch[] = []
  for (const match of content.matchAll(BOT_FLOW_RE)) {
    const flowId = match[2]
    if (!seen.has(flowId)) {
      seen.add(flowId)
      matches.push({ name: match[1], flowId })
    }
  }
  return matches
}

export function renderBotMarkdownContent(content: string): string {
  return content
    .replace(BOT_MENTION_RE, (_full, name: string) => `**@${name}**`)
    .replace(BOT_APP_RE, (_full, name: string) => `**📱 ${name}**`)
    .replace(BOT_FLOW_RE, (_full, name: string) => `**📋 ${name}**`)
    .replace(BOT_SKILL_RE, (_full, name: string) => `**🧩 ${name}**`)
}

export type DeskIdleActivity = 'drama' | 'gaming' | 'window' | 'nap'

export function deskIdleActivity(botId: string, nowMs = Date.now()): DeskIdleActivity {
  const activities: DeskIdleActivity[] = ['drama', 'gaming', 'window', 'nap']
  let hash = 0
  for (let i = 0; i < botId.length; i++) hash = (hash * 31 + botId.charCodeAt(i)) >>> 0
  const slot = Math.floor(nowMs / 600_000)
  return activities[(hash + slot) % activities.length]
}

export function activeMentionQuery(text: string, cursor: number): { start: number; query: string } | null {
  const before = text.slice(0, cursor)
  const at = before.lastIndexOf('@')
  if (at === -1) return null
  if (at > 0 && !/\s/.test(before[at - 1])) return null
  const query = before.slice(at + 1)
  if (/\s/.test(query) || query.length > 30) return null
  return { start: at, query }
}

export function parseSuggestions(raw: string | null | undefined): string[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item): item is string => typeof item === 'string' && item.trim().length > 0).slice(0, 3)
  } catch {
    return []
  }
}

const WEEKDAY_NAMES = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export function formatRoutineSchedule(schedule: {
  scheduleKind: string
  scheduleTime: string
  scheduleWeekday: number
  cron: string
}): string {
  switch (schedule.scheduleKind) {
    case 'HOURLY': {
      const minute = (schedule.scheduleTime || '00:00').split(':')[1] || '00'
      return `每小时第 ${minute} 分`
    }
    case 'DAILY': return `每天 ${schedule.scheduleTime || '09:00'}`
    case 'WEEKDAYS': return `工作日 ${schedule.scheduleTime || '09:00'}`
    case 'WEEKLY': return `每${WEEKDAY_NAMES[schedule.scheduleWeekday] || '周一'} ${schedule.scheduleTime || '09:00'}`
    case 'CUSTOM': return `Cron: ${schedule.cron}`
    case 'MANUAL': return '仅手动触发'
    default: return schedule.cron || schedule.scheduleKind
  }
}

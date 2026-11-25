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

const BOT_MENTION_RE = /\[([^\]]+)\]\(bot:([a-zA-Z0-9-]+)\)/g
const BOT_APP_RE = /\[([^\]]+)\]\(app:([a-zA-Z0-9_-]+)\)/g

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

export function renderBotMarkdownContent(content: string): string {
  return content
    .replace(BOT_MENTION_RE, (_full, name: string) => `**@${name}**`)
    .replace(BOT_APP_RE, (_full, name: string) => `**📱 ${name}**`)
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

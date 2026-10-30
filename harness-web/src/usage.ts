export interface UsageTotals { inputTokens: number; outputTokens: number; cachedTokens: number; calls: number }
export interface UsageSource { id: string; label: string; available: boolean; totals: UsageTotals }
export interface UsageModel { source: string; model: string; totals: UsageTotals }
export interface UsageDay { date: string; tokens: Record<string, number> }
export interface UsageHeatmap { from: string; zone: string; tokens: number[] }
export interface UsageStatistics { from: string | null; totals: UsageTotals; sources: UsageSource[]; models: UsageModel[]; days: UsageDay[]; heatmap: UsageHeatmap }
export interface HeatCell { date: string; tokens: number; level: number }
export interface HeatWeek { month: string | null; days: HeatCell[] }

/** 以 k / M / B 缩写 token 数量。 */
export function compactTokens(value: number) {
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)}B`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 1 : 2)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(value >= 100_000 ? 0 : 1)}k`
  return String(value)
}

/** 缓存命中占输入的比例；无输入时为 0。 */
export const cacheRate = (totals: UsageTotals) => totals.inputTokens ? totals.cachedTokens / totals.inputTokens : 0

/** 超过 60 天的序列按月合并，避免"全部"范围下柱子过密。 */
export function chartDays(days: UsageDay[]): UsageDay[] {
  if (days.length <= 60) return days
  const months = new Map<string, Record<string, number>>()
  for (const day of days) {
    const month = day.date.slice(0, 7)
    const tokens = months.get(month) ?? {}
    for (const [source, value] of Object.entries(day.tokens)) tokens[source] = (tokens[source] ?? 0) + value
    months.set(month, tokens)
  }
  return [...months].map(([date, tokens]) => ({ date, tokens }))
}

export const dayTotal = (day: UsageDay) => Object.values(day.tokens).reduce((sum, value) => sum + value, 0)

const addDays = (date: string, days: number) => new Date(Date.parse(`${date}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10)

/** 按周排成列（from 为周日，每列自上而下为周日到周六）；非零日按四分位分为 1–4 级，月份变化的列标注月份。 */
export function heatmapWeeks(heatmap: UsageHeatmap): HeatWeek[] {
  const sorted = heatmap.tokens.filter(value => value > 0).sort((a, b) => a - b)
  const quantile = (p: number) => sorted[Math.floor(p * (sorted.length - 1))]
  const bounds = sorted.length ? [quantile(0.25), quantile(0.5), quantile(0.75)] : []
  // 峰值固定为最深一档，避免只有一个或全部相同的非零值时整体偏浅。
  const level = (value: number) => value <= 0 ? 0 : value >= sorted[sorted.length - 1] ? 4 : 1 + bounds.filter(bound => value > bound).length
  const weeks: HeatWeek[] = []
  heatmap.tokens.forEach((tokens, index) => {
    if (index % 7 === 0) weeks.push({ month: null, days: [] })
    weeks[weeks.length - 1].days.push({ date: addDays(heatmap.from, index), tokens, level: level(tokens) })
  })
  let previous = ''
  for (const week of weeks) {
    const month = week.days[0].date.slice(5, 7)
    if (month !== previous) week.month = `${Number(month)}月`
    previous = month
  }
  // 首列只露出不到 3 周时隐藏其标签，避免与下一个月份重叠。
  const next = weeks.findIndex((week, index) => index > 0 && week.month)
  if (next > 0 && next < 3) weeks[0].month = null
  return weeks
}

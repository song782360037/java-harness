import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cacheRate, chartBars, chartDays, compactTokens, dayTotal, heatmapWeeks } from '../src/usage.ts'

test('token counts are abbreviated by magnitude', () => {
  assert.equal(compactTokens(999), '999')
  assert.equal(compactTokens(1500), '1.5k')
  assert.equal(compactTokens(250_000), '250k')
  assert.equal(compactTokens(3_456_000), '3.46M')
  assert.equal(compactTokens(12_300_000_000), '12.30B')
})

test('cache rate is zero without input', () => {
  assert.equal(cacheRate({ inputTokens: 0, outputTokens: 5, cachedTokens: 0, calls: 1 }), 0)
  assert.equal(cacheRate({ inputTokens: 200, outputTokens: 5, cachedTokens: 50, calls: 1 }), 0.25)
})

test('long ranges are merged by month while short ranges stay daily', () => {
  const days = Array.from({ length: 61 }, (_, index) => {
    const date = new Date(Date.UTC(2026, 0, 1 + index)).toISOString().slice(0, 10)
    return { date, tokens: index === 0 ? { harness: 5, codex: 1 } : { harness: 1 } }
  })
  assert.equal(chartDays(days.slice(0, 60)).length, 60)
  const months = chartDays(days)
  assert.deepEqual(months.map(month => month.date), ['2026-01', '2026-02', '2026-03'])
  assert.deepEqual(months[0].tokens, { harness: 35, codex: 1 })
  assert.equal(dayTotal(months[0]), 36)
})

test('heatmap lays out Sunday-first weeks with quartile levels and month labels', () => {
  const tokens = Array.from({ length: 30 }, (_, index) => index % 7 === 6 ? 0 : index + 1)
  const weeks = heatmapWeeks({ from: '2026-01-25', zone: 'UTC+08:00', tokens })
  assert.equal(weeks.length, 5)
  assert.deepEqual(weeks.map(week => week.days.length), [7, 7, 7, 7, 2])
  assert.equal(weeks[0].days[0].date, '2026-01-25')
  assert.equal(weeks[1].days[0].date, '2026-02-01')
  // The first column is hidden when the next month label follows within three weeks.
  assert.deepEqual(weeks.map(week => week.month), [null, '2月', null, null, null])
  assert.equal(weeks[0].days[6].level, 0)
  assert.equal(weeks[0].days[0].level, 1)
  assert.equal(weeks[4].days[1].level, 4)
  assert.ok(weeks.flatMap(week => week.days).every(day => day.level >= 0 && day.level <= 4))
})

test('empty heatmap stays at level zero', () => {
  const weeks = heatmapWeeks({ from: '2026-01-04', zone: 'UTC+00:00', tokens: [0, 0, 0] })
  assert.deepEqual(weeks[0].days.map(day => day.level), [0, 0, 0])
  assert.equal(weeks[0].month, '1月')
})

test('the busiest heatmap day is always the darkest level', () => {
  const weeks = heatmapWeeks({ from: '2026-01-04', zone: 'UTC+00:00', tokens: [0, 5, 5, 0, 5] })
  assert.deepEqual(weeks[0].days.map(day => day.level), [0, 4, 4, 0, 4])
})

test('today charts hours while longer ranges chart days or months', () => {
  const hours = Array.from({ length: 24 }, (_, hour) => ({ hour, tokens: hour === 9 ? { codex: 3 } : {} }))
  const today = chartBars({ days: [{ date: '2026-10-30', tokens: { codex: 3 } }], hours })
  assert.equal(today.unit, 'hour')
  assert.equal(today.bars.length, 24)
  assert.deepEqual(today.bars[9], { date: '09:00', tokens: { codex: 3 } })
  assert.equal(today.bars[23].date, '23:00')
  assert.equal(chartBars({ days: [{ date: '2026-10-30', tokens: {} }], hours: [] }).unit, 'day')
})

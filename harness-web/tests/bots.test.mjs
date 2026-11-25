import assert from 'node:assert/strict'
import test from 'node:test'
import {
  activeMentionQuery,
  botStatusLabel,
  botTierLabel,
  deskIdleActivity,
  extractBotApps,
  extractBotMentions,
  formatRoutineSchedule,
  parseSuggestions,
  renderBotMarkdownContent,
} from '../src/bots/model.ts'
import {
  BOT_AVATARS,
  BOT_AVATAR_CATEGORIES,
  BOT_COLORS,
  BOT_SHAPES,
  buildBotAvatarSvg,
  getBotAvatar,
} from '../src/bots/avatars.ts'
import { taskSourceLabel } from '../src/taskActivityModel.ts'
import { encodeQr, qrSvgData } from '../src/bots/qr.ts'

test('extractBotMentions parses unique clickable bot references and renders readable text', () => {
  const raw = '已派给 [发版助理](bot:bot-1) 和 [文档官](bot:bot-2)，并再次通知 [发版助理](bot:bot-1)。'
  assert.deepEqual(extractBotMentions(raw), [
    { name: '发版助理', botId: 'bot-1' },
    { name: '文档官', botId: 'bot-2' },
  ])
  assert.equal(renderBotMarkdownContent(raw), '已派给 **@发版助理** 和 **@文档官**，并再次通知 **@发版助理**。')
})

test('activeMentionQuery triggers at word boundary and ignores email addresses', () => {
  assert.deepEqual(activeMentionQuery('帮我问问 @发版', 8), { start: 5, query: '发版' })
  assert.deepEqual(activeMentionQuery('@', 1), { start: 0, query: '' })
  assert.equal(activeMentionQuery('user@example.com', 16), null)
  assert.equal(activeMentionQuery('@发版 之后呢', 8), null)
})

test('status, tier and task source labels cover bot workbench values', () => {
  assert.equal(botStatusLabel('WORKING'), '后台干活中')
  assert.equal(botStatusLabel('FAILED'), '任务失败')
  assert.equal(botTierLabel('MASTER'), '满级大师')
  assert.equal(taskSourceLabel('BOT_TASK'), 'Bot 任务')
})

test('parseSuggestions safely decodes up to 3 suggestions and ignores invalid payloads', () => {
  assert.deepEqual(parseSuggestions('["立即试跑","查看例行","导出文档","多余一条"]'), ['立即试跑', '查看例行', '导出文档'])
  assert.deepEqual(parseSuggestions('not-json'), [])
  assert.deepEqual(parseSuggestions(''), [])
})

test('formatRoutineSchedule renders human-readable labels for all schedule kinds', () => {
  assert.equal(formatRoutineSchedule({ scheduleKind: 'DAILY', scheduleTime: '09:30', scheduleWeekday: 1, cron: '' }), '每天 09:30')
  assert.equal(formatRoutineSchedule({ scheduleKind: 'WEEKDAYS', scheduleTime: '08:45', scheduleWeekday: 1, cron: '' }), '工作日 08:45')
  assert.equal(formatRoutineSchedule({ scheduleKind: 'WEEKLY', scheduleTime: '10:00', scheduleWeekday: 5, cron: '' }), '每周五 10:00')
  assert.equal(formatRoutineSchedule({ scheduleKind: 'HOURLY', scheduleTime: '00:15', scheduleWeekday: 0, cron: '' }), '每小时第 15 分')
  assert.equal(formatRoutineSchedule({ scheduleKind: 'CUSTOM', scheduleTime: '', scheduleWeekday: 0, cron: '0 9 * * 1-5' }), 'Cron: 0 9 * * 1-5')
  assert.equal(formatRoutineSchedule({ scheduleKind: 'MANUAL', scheduleTime: '', scheduleWeekday: 0, cron: '' }), '仅手动触发')
})

test('extractBotApps parses unique mini app references and renders readable label', () => {
  const text = '已生成 [收入汇总](app:income-board) 与 [收入汇总](app:income-board) 以及 [待办清单](app:todo_1)。'
  assert.deepEqual(extractBotApps(text), [
    { name: '收入汇总', appId: 'income-board' },
    { name: '待办清单', appId: 'todo_1' },
  ])
  assert.equal(renderBotMarkdownContent(text), '已生成 **📱 收入汇总** 与 **📱 收入汇总** 以及 **📱 待办清单**。')
})

test('deskIdleActivity rotates deterministically every 10 minutes per bot', () => {
  const t0 = 600_000 * 10
  const a0 = deskIdleActivity('bot-alpha', t0)
  const a1 = deskIdleActivity('bot-alpha', t0 + 600_000)
  assert.notEqual(a0, a1)
  assert.equal(deskIdleActivity('bot-alpha', t0 + 5_000), a0)
})

test('encodeQr and qrSvgData produce valid ISO finder patterns and SVG path for LAN URLs', () => {
  const qr = encodeQr('http://192.168.1.42:4317')
  assert.equal(qr.version, 2)
  assert.equal(qr.size, 25)
  // Top-left 7x7 finder corners and 3x3 center must be dark, 5x5 inner ring must be light.
  assert.equal(qr.modules[0][0], true)
  assert.equal(qr.modules[0][6], true)
  assert.equal(qr.modules[6][0], true)
  assert.equal(qr.modules[1][1], false)
  assert.equal(qr.modules[3][3], true)
  // Dark module at (size - 8, 8) is always dark.
  assert.equal(qr.modules[qr.size - 8][8], true)

  const svg = qrSvgData('http://192.168.1.42:4317')
  assert.equal(svg.viewBoxSize, 25 + 8)
  assert.ok(svg.path.startsWith('M'))
  assert.ok(svg.path.includes('h1v1h-1z'))
})

test('Deskfolk 30 avatar catalog provides 30 unique categorized characters and safe SVG output', () => {
  assert.equal(BOT_AVATARS.length, 30)
  assert.equal(new Set(BOT_SHAPES).size, 30)
  assert.equal(BOT_AVATAR_CATEGORIES.length, 5)
  assert.ok(BOT_COLORS.length >= 12)
  // Legacy 8 shapes remain valid for existing bots.
  for (const legacy of ['orb', 'cube', 'hex', 'prism', 'leaf', 'star', 'clover', 'pill']) {
    assert.equal(getBotAvatar(legacy).id, legacy)
  }
  for (const spec of BOT_AVATARS) {
    const svg = buildBotAvatarSvg(spec.id, '#2d5d9c')
    assert.ok(svg.startsWith('<svg '))
    assert.ok(svg.endsWith('</svg>'))
    assert.ok(svg.length > 180, `${spec.id} SVG should have layered details`)
  }
  // Invalid colors are safely sanitized to default hex.
  const sanitized = buildBotAvatarSvg('fox', 'javascript:alert(1)')
  assert.ok(!sanitized.includes('javascript:'))
  assert.ok(sanitized.includes('#276649'))
  assert.equal(getBotAvatar('nonexistent').id, 'orb')
})

import type { RunStep, ToolExecution } from './api'

export type TimelineItem =
  | { kind: 'reasoning'; key: string; text: string; seconds: number; live: boolean }
  | { kind: 'text'; key: string; text: string }
  | { kind: 'tool'; key: string; tool: ToolExecution }
  | { kind: 'explore'; key: string; tools: ToolExecution[] }
export interface Timeline { items: TimelineItem[]; answer: string }

const exploreTools = new Set(['read_file', 'list_directory', 'search_files', 'git_status', 'git_diff'])
export const isBusy = (tool: ToolExecution) => ['PENDING', 'RUNNING'].includes(tool.status)
const seconds = (from: string | null, to: string | null) => from && to ? Math.max(1, Math.round((Date.parse(to) - Date.parse(from)) / 1000)) : 0

/** 连续的只读探索合并为一组，其余工具单独成行。 */
function pushTool(items: TimelineItem[], tool: ToolExecution) {
  const last = items.at(-1)
  if (!exploreTools.has(tool.name)) { items.push({ kind: 'tool', key: tool.callId, tool }); return }
  if (last?.kind === 'explore') last.tools.push(tool)
  else if (last?.kind === 'tool' && exploreTools.has(last.tool.name)) items[items.length - 1] = { kind: 'explore', key: last.key, tools: [last.tool, tool] }
  else items.push({ kind: 'tool', key: tool.callId, tool })
}

/**
 * 按步骤把思考、文本和工具交错排列。结束后最后一步若没有工具，其文本作为回答放在过程之外；
 * 旧记录没有步骤边界，只能把工具放在过程里、整段输出作为回答。
 */
export function buildTimeline(output: string, steps: RunStep[], tools: ToolExecution[], active: boolean): Timeline {
  const items: TimelineItem[] = []
  if (!steps.length) {
    for (const tool of tools) pushTool(items, tool)
    return { items, answer: output }
  }
  let answer = ''
  steps.forEach((step, index) => {
    const last = index === steps.length - 1
    const text = output.slice(step.outputStart, last ? output.length : steps[index + 1].outputStart)
    const stepTools = tools.filter(tool => tool.step === step.step)
    if (step.reasoning.trim()) items.push({ kind: 'reasoning', key: `reasoning-${step.step}`, text: step.reasoning.trim(),
      seconds: seconds(step.startedAt, step.reasoningAt), live: last && active && !text && !stepTools.length })
    if (last && !active && !stepTools.length) answer = text
    else if (text.trim()) items.push({ kind: 'text', key: `text-${step.step}`, text })
    for (const tool of stepTools) pushTool(items, tool)
  })
  // 步骤记录之前发生的工具（理论上不存在）仍需展示，避免丢失。
  for (const tool of tools) if (!steps.some(step => step.step === tool.step)) pushTool(items, tool)
  return { items, answer }
}

/** raw 表示未知工具（如插件 MCP 工具），展开时需展示原始参数。 */
export interface ToolLabel { icon: string; verb: string; target: string; raw?: boolean }
function parse(value: string): Record<string, unknown> {
  try { const parsed = JSON.parse(value); return parsed && typeof parsed === 'object' ? parsed : {} } catch { return {} }
}
const text = (value: unknown) => typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)

/** 把工具名和参数转换为一行可读的动作描述。 */
export function toolLabel(tool: ToolExecution): ToolLabel {
  const args = parse(tool.arguments)
  const path = text(args.path)
  switch (tool.name) {
    case 'read_file': {
      const range = args.start_line ? `:${text(args.start_line)}${args.end_line ? `-${text(args.end_line)}` : ''}` : ''
      return { icon: 'file', verb: '读取', target: path + range }
    }
    case 'list_directory': return { icon: 'folder', verb: '浏览', target: path || '.' }
    case 'search_files': return { icon: 'search', verb: '搜索', target: `"${text(args.query)}"${path && path !== '.' ? ` · ${path}` : ''}` }
    case 'git_status': return { icon: 'branch', verb: '查看 Git 状态', target: '' }
    case 'git_diff': return { icon: 'branch', verb: '查看 Git 差异', target: path }
    case 'propose_command': return { icon: 'terminal', verb: '请求运行', target: text(args.command) }
    case 'propose_file_change': return { icon: 'edit', verb: args.operation === 'create' ? '提议新建' : '提议修改', target: path }
    case 'load_skill': return { icon: 'puzzle', verb: '加载技能', target: text(args.id) }
    case 'read_plugin_resource': return { icon: 'puzzle', verb: '读取插件资源', target: text(args.id) }
    default: return { icon: 'puzzle', verb: '调用', target: tool.name, raw: true }
  }
}

/** 探索组摘要，例如"已探索 3 个文件，1 次搜索"。 */
export function exploreSummary(tools: ToolExecution[]) {
  const files = new Set(tools.filter(tool => tool.name === 'read_file').map(tool => text(parse(tool.arguments).path))).size
  const count = (name: string) => tools.filter(tool => tool.name === name).length
  const parts = [files && `${files} 个文件`, count('search_files') && `${count('search_files')} 次搜索`,
    count('list_directory') && `${count('list_directory')} 个目录`, count('git_status') + count('git_diff') && `${count('git_status') + count('git_diff')} 次 Git 查看`]
  const failed = tools.filter(tool => tool.status === 'FAILED').length
  return `${tools.some(isBusy) ? '正在探索' : '已探索'} ${parts.filter(Boolean).join('，')}${failed ? `（${failed} 次失败）` : ''}`
}

/** 秒数转为简短中文时长。 */
export function formatDuration(total: number) {
  if (total < 60) return `${Math.max(1, total)} 秒`
  const minutes = Math.floor(total / 60)
  return total % 60 ? `${minutes} 分 ${total % 60} 秒` : `${minutes} 分钟`
}

/** 运行时长：第一步开始到最后一步结束，运行中以当前时间为终点。 */
export function elapsedSeconds(steps: RunStep[], now: number) {
  if (!steps.length) return 0
  const end = steps.at(-1)!.finishedAt
  return Math.max(1, Math.round(((end ? Date.parse(end) : now) - Date.parse(steps[0].startedAt)) / 1000))
}

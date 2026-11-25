import type { Run, Snapshot } from './api'

const STATUS_LABELS: Record<Run['status'], string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '执行失败',
  CANCELLING: '停止中',
  CANCELLED: '已停止',
  TIMED_OUT: '已超时',
  INTERRUPTED: '已中断',
}

/** 将当前会话快照格式化为可读、可直接归档的 Markdown 文档。 */
export function formatSessionMarkdown(snapshot: Snapshot, workspaceName = '', exportedAt = new Date()): string {
  const title = snapshot.session.title.trim() || '未命名会话'
  const lines: string[] = [`# ${title}`, '']
  const meta: string[] = []
  if (workspaceName) meta.push(`- 工作区：${workspaceName}`)
  else if (snapshot.session.workspace) meta.push(`- 工作区：${snapshot.session.workspace}`)
  meta.push(`- 轮次：${snapshot.runs.length}`)
  meta.push(`- 导出时间：${exportedAt.toISOString()}`)
  lines.push(...meta, '')

  snapshot.runs.forEach((run, index) => {
    lines.push('---', '', `## 第 ${index + 1} 轮`, '')
    lines.push('### 提问', '', run.prompt.trim() || '（空）', '')
    const modelMeta = [run.provider, run.model].filter(Boolean).join(' / ')
    const statusSuffix = run.status === 'COMPLETED' ? '' : ` · ${STATUS_LABELS[run.status] || run.status}`
    const heading = modelMeta ? `### 回复（${modelMeta}${statusSuffix}）` : `### 回复${statusSuffix ? `（${STATUS_LABELS[run.status] || run.status}）` : ''}`
    lines.push(heading, '')
    const body = run.output.trim() || run.detail.trim() || '（无输出）'
    lines.push(body, '')
  })

  return `${lines.join('\n').trimEnd()}\n`
}

/** 清理文件名中的非法字符并补齐 .md 后缀。 */
export function exportFilename(title: string): string {
  const cleaned = title
    .replace(/[<>:"/\\|?*\u0000-\u001f]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 64)
    .trim()
  return `${cleaned || 'conversation'}.md`
}

/** 触发浏览器下载当前会话的 Markdown 文件。 */
export function downloadSessionMarkdown(snapshot: Snapshot, workspaceName = ''): void {
  const content = formatSessionMarkdown(snapshot, workspaceName)
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = exportFilename(snapshot.session.title)
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

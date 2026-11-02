/** 运行中已写入工作区的文件；state 为 FILE、MISSING 或 UNAVAILABLE。 */
export interface Artifact {
  changeId: string; path: string; operation: 'create' | 'replace'; state: 'FILE' | 'MISSING' | 'UNAVAILABLE'
  size: number; modifiedAt: string | null; openable: boolean
}
export interface ArtifactContent { path: string; content: string }
export interface ArtifactKind { icon: string; tone: 'code' | 'doc' | 'data' | 'web' | 'script' | 'plain' }

const kinds: [ArtifactKind, string[]][] = [
  [{ icon: 'code', tone: 'code' }, ['java', 'kt', 'ts', 'tsx', 'js', 'jsx', 'mjs', 'cjs', 'vue', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs', 'py', 'rb', 'php', 'swift', 'scala', 'proto', 'graphql']],
  [{ icon: 'file', tone: 'doc' }, ['md', 'markdown', 'txt', 'log', 'rst', 'adoc']],
  [{ icon: 'chart', tone: 'data' }, ['json', 'yaml', 'yml', 'xml', 'toml', 'csv', 'sql', 'properties', 'ini']],
  [{ icon: 'globe', tone: 'web' }, ['html', 'htm', 'css', 'scss', 'less', 'svg']],
  [{ icon: 'terminal', tone: 'script' }, ['sh', 'bash', 'zsh', 'cmd', 'bat', 'ps1', 'psm1']],
]

/** 多目录工作区的路径带 @N/ 前缀，文件名取最后一段。 */
export const fileName = (path: string) => path.slice(path.lastIndexOf('/') + 1)
export const folderOf = (path: string) => path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : ''

/** 按扩展名分类，决定图标与颜色。 */
export function artifactKind(path: string): ArtifactKind {
  const name = fileName(path).toLowerCase()
  const dot = name.lastIndexOf('.')
  const extension = dot > 0 ? name.slice(dot + 1) : ''
  return kinds.find(([, extensions]) => extensions.includes(extension))?.[0] ?? { icon: 'file', tone: 'plain' }
}

export function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export const stateLabel = (state: Artifact['state']) => state === 'MISSING' ? '文件已不存在' : state === 'UNAVAILABLE' ? '无法访问' : ''

/** 把运行的工作区快照与相对路径拼成本机绝对路径；无法解析时返回相对路径。 */
export function absolutePath(workspace: string, path: string) {
  let roots: string[] = [workspace]
  if (workspace.startsWith('[')) {
    try { roots = JSON.parse(workspace) as string[] } catch { return path }
  }
  let root = roots[0] ?? '', relative = path
  const match = /^@([1-9][0-9]*)\/(.+)$/.exec(path)
  if (roots.length > 1) {
    if (!match || !roots[Number(match[1]) - 1]) return path
    root = roots[Number(match[1]) - 1]!; relative = match[2]!
  }
  if (!root) return path
  const separator = root.includes('\\') ? '\\' : '/'
  return root.replace(/[\\/]+$/, '') + separator + relative.split('/').join(separator)
}

/** 按行拆分用于带行号展示；末尾换行不产生额外空行。 */
export function numberedLines(text: string) {
  const lines = text.split(/\r?\n/)
  if (lines.length > 1 && lines.at(-1) === '') lines.pop()
  return lines
}

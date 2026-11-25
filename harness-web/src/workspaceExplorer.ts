export interface WorkspaceTreeNode {
  root: number
  path: string
  name: string
  directory: boolean
  size: number
}

export interface WorkspaceDirectoryView {
  root: number
  path: string
  entries: WorkspaceTreeNode[]
  truncated: boolean
}

export interface WorkspaceFilePreview {
  root: number
  path: string
  name: string
  size: number
  binary: boolean
  truncated: boolean
  content: string
  totalLines: number
}

export interface LanguageInfo {
  id: string
  label: string
}

const LANGUAGES: Record<string, LanguageInfo> = {
  java: { id: 'java', label: 'Java' },
  kt: { id: 'java', label: 'Kotlin' },
  ts: { id: 'ts', label: 'TypeScript' },
  tsx: { id: 'ts', label: 'TSX' },
  js: { id: 'js', label: 'JavaScript' },
  mjs: { id: 'js', label: 'JS' },
  cjs: { id: 'js', label: 'JS' },
  jsx: { id: 'js', label: 'JSX' },
  vue: { id: 'vue', label: 'Vue' },
  html: { id: 'markup', label: 'HTML' },
  xml: { id: 'markup', label: 'XML' },
  svg: { id: 'markup', label: 'SVG' },
  css: { id: 'css', label: 'CSS' },
  scss: { id: 'css', label: 'SCSS' },
  json: { id: 'json', label: 'JSON' },
  yml: { id: 'yaml', label: 'YAML' },
  yaml: { id: 'yaml', label: 'YAML' },
  sql: { id: 'sql', label: 'SQL' },
  md: { id: 'markdown', label: 'Markdown' },
  sh: { id: 'shell', label: 'Shell' },
  bash: { id: 'shell', label: 'Shell' },
  zsh: { id: 'shell', label: 'Shell' },
  ps1: { id: 'shell', label: 'PowerShell' },
  py: { id: 'python', label: 'Python' },
  go: { id: 'go', label: 'Go' },
  rs: { id: 'rust', label: 'Rust' },
  toml: { id: 'yaml', label: 'TOML' },
  properties: { id: 'yaml', label: 'Properties' },
}

const KEYWORD_SETS: Record<string, Set<string>> = {
  java: new Set(['abstract', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'false', 'final', 'finally', 'float', 'for', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'new', 'null', 'package', 'private', 'protected', 'public', 'record', 'return', 'sealed', 'short', 'static', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws', 'true', 'try', 'var', 'void', 'while', 'yield']),
  ts: new Set(['as', 'async', 'await', 'boolean', 'break', 'case', 'catch', 'class', 'const', 'continue', 'default', 'delete', 'else', 'enum', 'export', 'extends', 'false', 'finally', 'for', 'from', 'function', 'if', 'implements', 'import', 'in', 'interface', 'keyof', 'let', 'new', 'null', 'number', 'of', 'private', 'protected', 'public', 'readonly', 'return', 'static', 'string', 'super', 'switch', 'this', 'throw', 'true', 'try', 'type', 'typeof', 'undefined', 'unknown', 'void', 'while']),
  js: new Set(['async', 'await', 'break', 'case', 'catch', 'class', 'const', 'continue', 'default', 'delete', 'else', 'export', 'extends', 'false', 'finally', 'for', 'from', 'function', 'if', 'import', 'in', 'let', 'new', 'null', 'of', 'return', 'static', 'super', 'switch', 'this', 'throw', 'true', 'try', 'typeof', 'undefined', 'var', 'void', 'while']),
  vue: new Set(['async', 'await', 'const', 'export', 'false', 'for', 'from', 'function', 'if', 'import', 'interface', 'let', 'null', 'return', 'true', 'type', 'undefined', 'else', 'try', 'catch', 'finally', 'new', 'of', 'in']),
  sql: new Set(['SELECT', 'FROM', 'WHERE', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'ON', 'AND', 'OR', 'NOT', 'IN', 'IS', 'NULL', 'ORDER', 'BY', 'GROUP', 'HAVING', 'LIMIT', 'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'CREATE', 'TABLE', 'ALTER', 'DROP', 'INDEX', 'PRIMARY', 'KEY', 'AS', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'DISTINCT', 'LIKE', 'ESCAPE', 'DESC', 'ASC', 'MERGE']),
  python: new Set(['and', 'as', 'assert', 'async', 'await', 'break', 'class', 'continue', 'def', 'del', 'elif', 'else', 'except', 'False', 'finally', 'for', 'from', 'if', 'import', 'in', 'is', 'lambda', 'None', 'nonlocal', 'not', 'or', 'pass', 'raise', 'return', 'True', 'try', 'while', 'with', 'yield']),
  go: new Set(['break', 'case', 'chan', 'const', 'continue', 'default', 'defer', 'else', 'false', 'for', 'func', 'go', 'if', 'import', 'interface', 'map', 'nil', 'package', 'range', 'return', 'select', 'struct', 'switch', 'true', 'type', 'var']),
  rust: new Set(['as', 'async', 'await', 'break', 'const', 'continue', 'crate', 'else', 'enum', 'false', 'fn', 'for', 'if', 'impl', 'in', 'let', 'loop', 'match', 'mod', 'move', 'mut', 'pub', 'ref', 'return', 'self', 'Self', 'static', 'struct', 'super', 'trait', 'true', 'type', 'unsafe', 'use', 'where', 'while']),
  shell: new Set(['if', 'then', 'else', 'elif', 'fi', 'for', 'in', 'do', 'done', 'while', 'case', 'esac', 'function', 'return', 'export', 'unset', 'local', 'true', 'false', 'echo', 'exit']),
  json: new Set(['true', 'false', 'null']),
  yaml: new Set(['true', 'false', 'null', 'yes', 'no']),
}

/** 按文件扩展名或特殊文件名识别展示语言。 */
export function detectLanguage(filePath: string): LanguageInfo {
  const name = filePath.slice(filePath.lastIndexOf('/') + 1).toLowerCase()
  if (name === 'dockerfile' || name.startsWith('dockerfile.')) return { id: 'shell', label: 'Dockerfile' }
  if (name === 'makefile') return { id: 'shell', label: 'Makefile' }
  const dot = name.lastIndexOf('.')
  if (dot > 0 && dot < name.length - 1) {
    const ext = name.slice(dot + 1)
    if (LANGUAGES[ext]) return LANGUAGES[ext]
  }
  return { id: 'text', label: 'Text' }
}

/** 将字节数格式化为紧凑可读文本。 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 生成工作区根目录显示标签；多根目录时附带 @1 / @2 前缀。 */
export function rootLabel(directory: string, index: number, totalRoots: number): string {
  const normalized = directory.replace(/[\\/]+$/, '')
  const parts = normalized.split(/[\\/]/)
  const base = parts[parts.length - 1] || directory
  return totalRoots > 1 ? `@${index + 1} ${base}` : base
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 轻量单行语法高亮：始终先对原始片段做 HTML 转义，保证零 XSS 风险与毫秒级渲染。 */
export function highlightCodeLine(line: string, langId: string): string {
  if (!line || langId === 'text') return escapeHtml(line)
  if (langId === 'markdown') {
    if (/^\s*#{1,6}\s/.test(line)) return `<span class="tok-keyword">${escapeHtml(line)}</span>`
    if (/^\s*```/.test(line)) return `<span class="tok-comment">${escapeHtml(line)}</span>`
    return escapeHtml(line)
  }

  const hashComment = langId === 'python' || langId === 'shell' || langId === 'yaml'
  const dashComment = langId === 'sql'
  const slashComment = !hashComment && !dashComment && langId !== 'json' && langId !== 'markup'
  const keywords = KEYWORD_SETS[langId]
  const sqlCase = langId === 'sql'

  let out = ''
  let i = 0
  const n = line.length
  while (i < n) {
    const ch = line[i]
    if (slashComment && ch === '/' && line[i + 1] === '/') {
      out += `<span class="tok-comment">${escapeHtml(line.slice(i))}</span>`
      break
    }
    if (hashComment && ch === '#') {
      out += `<span class="tok-comment">${escapeHtml(line.slice(i))}</span>`
      break
    }
    if (dashComment && ch === '-' && line[i + 1] === '-') {
      out += `<span class="tok-comment">${escapeHtml(line.slice(i))}</span>`
      break
    }
    if (ch === '"' || ch === '\'' || (ch === '`' && (langId === 'ts' || langId === 'js' || langId === 'vue' || langId === 'go'))) {
      const quote = ch
      let end = i + 1
      while (end < n) {
        if (line[end] === '\\' && end + 1 < n) { end += 2; continue }
        if (line[end] === quote) { end++; break }
        end++
      }
      out += `<span class="tok-string">${escapeHtml(line.slice(i, end))}</span>`
      i = end
      continue
    }
    if (ch === '@' && (langId === 'java' || langId === 'ts' || langId === 'vue') && /[A-Za-z_]/.test(line[i + 1] || '')) {
      let end = i + 2
      while (end < n && /[\w-]/.test(line[end])) end++
      out += `<span class="tok-annotation">${escapeHtml(line.slice(i, end))}</span>`
      i = end
      continue
    }
    if ((langId === 'markup' || langId === 'vue') && ch === '<' && /[A-Za-z/!]/.test(line[i + 1] || '')) {
      let end = i + 1
      if (line[end] === '/') end++
      while (end < n && /[\w:-]/.test(line[end])) end++
      out += `<span class="tok-keyword">${escapeHtml(line.slice(i, end))}</span>`
      i = end
      continue
    }
    if (/\d/.test(ch) && (i === 0 || !/[A-Za-z0-9_$]/.test(line[i - 1]))) {
      let end = i + 1
      while (end < n && /[\d._xXa-fA-FL]/.test(line[end])) end++
      out += `<span class="tok-number">${escapeHtml(line.slice(i, end))}</span>`
      i = end
      continue
    }
    if (/[A-Za-z_$]/.test(ch)) {
      let end = i + 1
      while (end < n && /[A-Za-z0-9_$]/.test(line[end])) end++
      const word = line.slice(i, end)
      const hit = keywords ? (sqlCase ? keywords.has(word.toUpperCase()) : keywords.has(word)) : false
      out += hit ? `<span class="tok-keyword">${escapeHtml(word)}</span>` : escapeHtml(word)
      i = end
      continue
    }
    out += escapeHtml(ch)
    i++
  }
  return out
}

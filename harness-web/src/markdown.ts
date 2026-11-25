import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({ html: false, linkify: false, breaks: true })
markdown.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
  tokens[index]!.attrSet('target', '_blank')
  tokens[index]!.attrSet('rel', 'noopener noreferrer')
  return renderer.renderToken(tokens, index, options)
}
// External image URLs in model output are shown as text; rendering never fetches them.
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(`[图片：${tokens[index]?.content || '未命名'}]`)
export const renderMarkdown = (content: string) => markdown.render(content)

const RELEASE_NOTE_TAGS = new Set([
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'p', 'div', 'span',
  'ul', 'ol', 'li',
  'strong', 'b', 'em', 'i', 'u', 's', 'del', 'ins', 'mark', 'sub', 'sup',
  'code', 'pre', 'blockquote',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
  'details', 'summary',
  'a',
])
const VOID_TAGS = new Set(['br', 'hr'])
const DROP_CONTENT_TAGS = new Set([
  'script', 'style', 'iframe', 'object', 'embed', 'svg', 'math',
  'noscript', 'template', 'textarea', 'title', 'xmp',
])
const NAMED_ENTITIES: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0',
  mdash: '—', ndash: '–', hellip: '…', middot: '·', bull: '•',
  rarr: '→', larr: '←', uarr: '↑', darr: '↓',
  lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”',
  times: '×', copy: '©', reg: '®', trade: '™',
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function decodeHtmlEntities(text: string): string {
  return text.replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);/gi, (match, body: string) => {
    if (body[0] === '#') {
      const codePoint = body[1] === 'x' || body[1] === 'X'
        ? Number.parseInt(body.slice(2), 16)
        : Number.parseInt(body.slice(1), 10)
      if (!Number.isFinite(codePoint) || codePoint <= 0 || codePoint > 0x10ffff || (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
        return ''
      }
      return String.fromCodePoint(codePoint)
    }
    return NAMED_ENTITIES[body.toLowerCase()] ?? match
  })
}

function sanitizeHref(raw: string | undefined): string {
  if (!raw) return ''
  const cleaned = decodeHtmlEntities(raw).replace(/[\u0000-\u001f\u007f]/g, '').trim()
  if (!cleaned) return ''
  try {
    const url = new URL(cleaned)
    if (url.protocol !== 'http:' && url.protocol !== 'https:' && url.protocol !== 'mailto:') return ''
    return url.href
  } catch {
    return ''
  }
}

function readAttributes(html: string, start: number): { attrs: Record<string, string>; selfClosing: boolean; end: number } | null {
  const attrs: Record<string, string> = {}
  let i = start
  let selfClosing = false
  while (i < html.length) {
    while (i < html.length && /[\s/]/.test(html[i]!)) {
      if (html[i] === '/' && html[i + 1] === '>') selfClosing = true
      i += 1
    }
    if (i >= html.length) return null
    if (html[i] === '>') return { attrs, selfClosing, end: i + 1 }
    const nameStart = i
    while (i < html.length && !/[\s=/>]/.test(html[i]!)) i += 1
    const name = html.slice(nameStart, i).toLowerCase()
    while (i < html.length && /\s/.test(html[i]!)) i += 1
    let value = ''
    if (html[i] === '=') {
      i += 1
      while (i < html.length && /\s/.test(html[i]!)) i += 1
      const quote = html[i]
      if (quote === '"' || quote === "'") {
        i += 1
        const valStart = i
        const closeQuote = html.indexOf(quote, i)
        if (closeQuote === -1) return null
        value = html.slice(valStart, closeQuote)
        i = closeQuote + 1
      } else {
        const valStart = i
        while (i < html.length && !/[\s>]/.test(html[i]!)) i += 1
        value = html.slice(valStart, i)
      }
    }
    if (name) attrs[name] = value
  }
  return null
}

/** 仅保留白名单结构标签与安全链接，剥离脚本、样式、内联事件和外链图片。 */
export function sanitizeReleaseNotesHtml(html: string): string {
  const out: string[] = []
  const stack: Array<{ tag: string; emit: boolean }> = []
  const openTagRe = /<([a-z][a-z0-9:-]*)(?=[\s/>])/iy
  const closeTagRe = /<\/([a-z][a-z0-9:-]*)(?=[\s/>])/iy
  let i = 0

  const pushText = (chunk: string) => {
    if (chunk) out.push(escapeHtml(decodeHtmlEntities(chunk)))
  }

  const closeTop = () => {
    const entry = stack.pop()
    if (entry?.emit) out.push(`</${entry.tag}>`)
  }

  while (i < html.length) {
    const lt = html.indexOf('<', i)
    if (lt === -1) {
      pushText(html.slice(i))
      break
    }
    if (lt > i) pushText(html.slice(i, lt))

    if (html.startsWith('<!--', lt)) {
      const end = html.indexOf('-->', lt + 4)
      i = end === -1 ? html.length : end + 3
      continue
    }

    if (html[lt + 1] === '/') {
      closeTagRe.lastIndex = lt
      const match = closeTagRe.exec(html)
      if (!match) {
        pushText('<')
        i = lt + 1
        continue
      }
      const gt = html.indexOf('>', lt + match[0].length)
      i = gt === -1 ? html.length : gt + 1
      const tag = match[1]!.toLowerCase()
      const idx = stack.map(entry => entry.tag).lastIndexOf(tag)
      if (idx !== -1) while (stack.length > idx) closeTop()
      continue
    }

    if (html[lt + 1] === '!' || html[lt + 1] === '?') {
      const gt = html.indexOf('>', lt + 2)
      i = gt === -1 ? html.length : gt + 1
      continue
    }

    openTagRe.lastIndex = lt
    const openMatch = openTagRe.exec(html)
    if (!openMatch) {
      pushText('<')
      i = lt + 1
      continue
    }
    const tag = openMatch[1]!.toLowerCase()
    const parsed = readAttributes(html, lt + openMatch[0].length)
    if (!parsed) break
    i = parsed.end

    if (DROP_CONTENT_TAGS.has(tag)) {
      if (!parsed.selfClosing) {
        const dropCloseRe = new RegExp(`</${tag}(?:\\s[^>]*)?>`, 'ig')
        dropCloseRe.lastIndex = i
        const closed = dropCloseRe.exec(html)
        i = closed ? dropCloseRe.lastIndex : html.length
      }
      continue
    }

    if (VOID_TAGS.has(tag)) {
      out.push(`<${tag}>`)
      continue
    }

    if (tag === 'img') {
      const alt = decodeHtmlEntities(parsed.attrs.alt || '').trim()
      if (alt) out.push(escapeHtml(`[图片：${alt}]`))
      continue
    }

    if (!RELEASE_NOTE_TAGS.has(tag)) continue

    if ((tag === 'li' || tag === 'p') && stack[stack.length - 1]?.tag === tag) {
      closeTop()
    }

    if (tag === 'a') {
      const safeHref = sanitizeHref(parsed.attrs.href)
      const insideAnchor = stack.some(entry => entry.tag === 'a' && entry.emit)
      if (safeHref && !insideAnchor) {
        out.push(`<a href="${escapeHtml(safeHref)}" target="_blank" rel="noopener noreferrer">`)
        if (parsed.selfClosing) out.push('</a>')
        else stack.push({ tag: 'a', emit: true })
      } else if (!parsed.selfClosing) {
        stack.push({ tag: 'a', emit: false })
      }
      continue
    }

    out.push(`<${tag}>`)
    if (parsed.selfClosing) out.push(`</${tag}>`)
    else stack.push({ tag, emit: true })
  }

  while (stack.length) closeTop()
  return out.join('').trim()
}

function isHtmlReleaseNotes(source: string): boolean {
  const withoutCode = source
    .replace(/```[\s\S]*?```/g, '')
    .replace(/`[^`\n]+`/g, '')
  if (/^<(?:h[1-6]|p|ul|ol|li|div|blockquote|pre|table|details|strong|em|b|i|a)(?=[\s/>])/i.test(withoutCode.trimStart())) {
    return true
  }
  return /<(h[1-6]|p|ul|ol|li|div|blockquote|pre|table|details)(?=[\s/>])[^>]*>[\s\S]*?<\/\1\s*>/i.test(withoutCode)
}

/** 渲染桌面端更新说明：兼容 GitHub Release 返回的 HTML 以及 Markdown / 纯文本。 */
export function renderReleaseNotes(raw: string): string {
  const trimmed = raw.trim()
  if (!trimmed) return ''
  return isHtmlReleaseNotes(trimmed) ? sanitizeReleaseNotesHtml(trimmed) : renderMarkdown(trimmed)
}

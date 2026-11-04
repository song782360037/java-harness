/** 工作区中的一个文件或目录；root 为目录序号（从 1 开始）。 */
export interface FileEntry { root: number; path: string; directory: boolean }
/** 输入框中待发送的附件；id 为空表示仍在上传，error 非空表示需要移除后才能发送。 */
export interface DraftAttachment { key: string; name: string; size: number; mediaType: string; preview: string; id: string; error: string }
export interface Attachment { id: string; runId: string | null; name: string; mediaType: string; size: number; createdAt: string }

export const MAX_ATTACHMENTS = 8
export const MAX_MENTIONS = 10
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024
export const MAX_TEXT_BYTES = 16 * 1024 * 1024
/** 粘贴超过这个长度的文本时转成文件卡片；输入本身不限长度，只受模型上下文约束。 */
export const LONG_PASTE_CHARS = 5000
export const isLongPaste = (text: string) => text.length > LONG_PASTE_CHARS
/** 同一条消息里第 n 段粘贴文本的文件名。 */
export const pastedFileName = (index: number) => index > 1 ? `粘贴的文本 ${index}.txt` : '粘贴的文本.txt'
/** 取第一个未被占用的粘贴文件名，删掉卡片后再粘贴也不会重名。 */
export function nextPastedName(names: string[]): string {
  for (let index = 1; ; index++) if (!names.includes(pastedFileName(index))) return pastedFileName(index)
}
/** 只有附件、没有正文和引用时的正文，说明本条消息的内容在附件里。 */
export const attachmentsOnlyPrompt = (names: string[]) => `见附件：${names.join('、')}`
export const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif']
export const isImage = (mediaType: string) => IMAGE_TYPES.includes(mediaType)

// 光标处正在输入的 @ 引用，规则与群聊 @ 成员一致；有选区时不弹出候选。
export function fileMentionQuery(text: string, start: number, end: number): { start: number; query: string } | null {
  if (start !== end) return null
  const match = /(^|[\s(（，,。：:])@([^\s@]*)$/.exec(text.slice(0, start))
  return match ? { start: start - match[2]!.length - 1, query: match[2]! } : null
}

// 选中引用后删掉整个 @ 词（含光标后的部分），避免留下多余空白，返回新正文和光标位置。
export function removeFileMentionQuery(text: string, caret: number): { text: string; caret: number } {
  const mention = fileMentionQuery(text, caret, caret)
  if (!mention) return { text, caret }
  const word = caret + /^[^\s@]*/.exec(text.slice(caret))![0].length
  const end = (mention.start === 0 || /\s/.test(text[mention.start - 1]!)) && /\s/.test(text[word] || '') ? word + 1 : word
  return { text: text.slice(0, mention.start) + text.slice(end), caret: mention.start }
}

// 多目录工作区带目录序号，目录以斜杠结尾，便于和同名文件区分。
export function mentionLabel(entry: FileEntry, multiRoot: boolean): string {
  return `${multiRoot ? `${entry.root}/` : ''}${entry.path}${entry.directory ? '/' : ''}`
}

// 卡片标题用文件或目录名，副标题用所在目录。
export function mentionParts(entry: FileEntry, multiRoot: boolean): { name: string; location: string } {
  const slash = entry.path.lastIndexOf('/')
  const parent = slash < 0 ? '' : entry.path.slice(0, slash + 1)
  return { name: entry.path.slice(slash + 1) + (entry.directory ? '/' : ''), location: `${multiRoot ? `${entry.root}/` : ''}${parent}` || '工作区根目录' }
}

// 同一文件或目录只保留一张卡片。
export function addMention(mentions: FileEntry[], entry: FileEntry): FileEntry[] {
  return mentions.some(value => value.root === entry.root && value.path === entry.path) ? mentions : [...mentions, entry]
}

// 上传前的本地检查，最终类型以服务端识别的文件头为准。
export function attachmentProblem(file: { name: string; size: number; type: string }): string | null {
  if (file.size === 0) return `${file.name} 是空文件`
  if (isImage(file.type)) return file.size > MAX_IMAGE_BYTES ? `${file.name} 超过 5 MB 图片上限` : null
  if (file.type.startsWith('image/')) return `${file.name} 不是支持的图片格式（PNG、JPEG、WebP、GIF）`
  return file.size > MAX_TEXT_BYTES ? `${file.name} 超过 16 MB 文本上限` : null
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 102.4) / 10} KB`
  return `${Math.round(bytes / 104857.6) / 10} MB`
}

// 粘贴或拖入的条目中取出文件，剪贴板截图没有文件名时补一个。
export function droppedFiles(items: ArrayLike<{ kind: string; getAsFile(): File | null }> | null | undefined): File[] {
  const files: File[] = []
  for (const item of Array.from(items || [])) {
    const file = item.kind === 'file' ? item.getAsFile() : null
    if (!file) continue
    files.push(file.name ? file : new File([file], `粘贴的图片.${file.type.split('/')[1] || 'png'}`, { type: file.type }))
  }
  return files
}

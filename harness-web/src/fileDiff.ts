import { structuredPatch } from 'diff'

export interface DiffRow { kind: 'context' | 'add' | 'remove' | 'note'; text: string; before?: number; after?: number }
export function fileDiff(before: string | null, after: string): DiffRow[] | null {
  const patch = structuredPatch('before', 'after', before ?? '', after, undefined, undefined,
    { context: 3, timeout: 80, maxEditLength: 20000 })
  if (!patch) return null
  const rows: DiffRow[] = []
  for (const hunk of patch.hunks) {
    let oldLine = hunk.oldStart, newLine = hunk.newStart
    rows.push({ kind: 'note', text: `@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@` })
    for (const line of hunk.lines) {
      if (line.startsWith('\\')) { rows.push({ kind: 'note', text: '文件末尾无换行' }); continue }
      const kind = line[0] === '+' ? 'add' : line[0] === '-' ? 'remove' : 'context'
      rows.push({ kind, text: line.slice(1).replace(/\r$/, ''),
        before: kind === 'add' ? undefined : oldLine++, after: kind === 'remove' ? undefined : newLine++ })
      if (rows.length > 2500) return null
    }
  }
  return rows
}

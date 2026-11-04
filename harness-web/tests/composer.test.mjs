import { test } from 'node:test'
import assert from 'node:assert/strict'
import { addMention, attachmentProblem, droppedFiles, fileMentionQuery, formatSize, mentionLabel, mentionParts, removeFileMentionQuery } from '../src/composer.ts'

const entry = (path, directory = false, root = 1) => ({ root, path, directory })

test('file mention query follows the caret and ignores selections and e-mail addresses', () => {
  assert.deepEqual(fileMentionQuery('看看 @src/Ap', 10, 10), { start: 3, query: 'src/Ap' })
  assert.deepEqual(fileMentionQuery('@', 1, 1), { start: 0, query: '' })
  assert.equal(fileMentionQuery('mail@host', 9, 9), null)
  assert.equal(fileMentionQuery('@src 后面', 8, 8), null)
  assert.equal(fileMentionQuery('@src', 0, 4), null)
})

test('picking a file mention removes the typed query without leaving double spaces', () => {
  assert.deepEqual(removeFileMentionQuery('看 @ap 的问题', 5), { text: '看 的问题', caret: 2 })
  assert.deepEqual(removeFileMentionQuery('@ap 开头', 3), { text: '开头', caret: 0 })
  assert.deepEqual(removeFileMentionQuery('结尾 @ap', 6), { text: '结尾 ', caret: 3 })
  assert.deepEqual(removeFileMentionQuery('无引用', 3), { text: '无引用', caret: 3 })
  assert.deepEqual(removeFileMentionQuery('@src/Ap.vue 看看', 7), { text: '看看', caret: 0 })
})

test('labels mark directories and roots of multi-directory workspaces', () => {
  assert.equal(mentionLabel(entry('src/App.java'), false), 'src/App.java')
  assert.equal(mentionLabel(entry('src', true, 2), true), '2/src/')
})

test('mention cards show the name and its folder, once per file', () => {
  assert.deepEqual(mentionParts(entry('src/App.vue'), false), { name: 'App.vue', location: 'src/' })
  assert.deepEqual(mentionParts(entry('docs', true, 2), true), { name: 'docs/', location: '2/' })
  assert.deepEqual(mentionParts(entry('pom.xml'), false), { name: 'pom.xml', location: '工作区根目录' })
  const once = addMention([entry('a.ts')], entry('a.ts'))
  assert.deepEqual(once, [entry('a.ts')])
  assert.deepEqual(addMention(once, entry('a.ts', false, 2)), [entry('a.ts'), entry('a.ts', false, 2)])
})

test('attachment checks reject empty files, unsupported images and oversized files', () => {
  assert.equal(attachmentProblem({ name: 'a.png', size: 10, type: 'image/png' }), null)
  assert.equal(attachmentProblem({ name: 'log.txt', size: 10, type: '' }), null)
  assert.match(attachmentProblem({ name: 'e.txt', size: 0, type: 'text/plain' }), /空文件/)
  assert.match(attachmentProblem({ name: 'a.bmp', size: 10, type: 'image/bmp' }), /不是支持的图片格式/)
  assert.match(attachmentProblem({ name: 'b.png', size: 6 * 1024 * 1024, type: 'image/png' }), /5 MB/)
  assert.match(attachmentProblem({ name: 'b.log', size: 300 * 1024, type: 'text/plain' }), /256 KB/)
})

test('sizes are shown in readable units', () => {
  assert.equal(formatSize(512), '512 B')
  assert.equal(formatSize(1536), '1.5 KB')
  assert.equal(formatSize(5 * 1024 * 1024), '5 MB')
})

test('pasted files keep their names and nameless screenshots get one', () => {
  const named = new File(['x'], 'a.txt', { type: 'text/plain' })
  const shot = new File(['x'], '', { type: 'image/png' })
  const files = droppedFiles([
    { kind: 'file', getAsFile: () => named },
    { kind: 'string', getAsFile: () => null },
    { kind: 'file', getAsFile: () => shot },
  ])
  assert.deepEqual(files.map(file => file.name), ['a.txt', '粘贴的图片.png'])
  assert.equal(files[1].type, 'image/png')
})

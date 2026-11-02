import { test } from 'node:test'
import assert from 'node:assert/strict'
import { absolutePath, artifactKind, fileName, folderOf, formatSize, numberedLines, stateLabel } from '../src/artifacts.ts'

test('extensions map to icon tones, unknown files stay plain', () => {
  assert.equal(artifactKind('src/App.java').tone, 'code')
  assert.equal(artifactKind('docs/README.MD').tone, 'doc')
  assert.equal(artifactKind('@2/config/app.yml').tone, 'data')
  assert.equal(artifactKind('index.html').tone, 'web')
  assert.equal(artifactKind('build.ps1').tone, 'script')
  assert.equal(artifactKind('Makefile').tone, 'plain')
  assert.equal(artifactKind('.gitignore').tone, 'plain')
  assert.equal(artifactKind('@1/a.SH').tone, 'script')
})
test('names, folders and sizes are compact', () => {
  assert.equal(fileName('@1/src/a b.md'), 'a b.md')
  assert.equal(folderOf('@1/src/a.md'), '@1/src')
  assert.equal(folderOf('a.md'), '')
  assert.equal(formatSize(512), '512 B')
  assert.equal(formatSize(1536), '1.5 KB')
  assert.equal(formatSize(3 * 1024 * 1024), '3.0 MB')
  assert.equal(stateLabel('MISSING'), '文件已不存在')
  assert.equal(stateLabel('FILE'), '')
})
test('absolute paths follow the workspace root and separator', () => {
  assert.equal(absolutePath('E:\\work\\app', 'src/App.java'), 'E:\\work\\app\\src\\App.java')
  assert.equal(absolutePath('/home/me/app/', 'src/App.java'), '/home/me/app/src/App.java')
  const multi = JSON.stringify(['/one', 'D:\\two'])
  assert.equal(absolutePath(multi, '@2/docs/a.md'), 'D:\\two\\docs\\a.md')
  assert.equal(absolutePath(multi, '@3/a.md'), '@3/a.md')
  assert.equal(absolutePath(multi, 'docs/a.md'), 'docs/a.md')
  assert.equal(absolutePath('[broken', 'a.md'), 'a.md')
})
test('line splitting keeps blank lines but drops the final newline', () => {
  assert.deepEqual(numberedLines('a\r\n\r\nb\n'), ['a', '', 'b'])
  assert.deepEqual(numberedLines(''), [''])
  assert.deepEqual(numberedLines('x'), ['x'])
})

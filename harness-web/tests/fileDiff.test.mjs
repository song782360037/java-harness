import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileDiff } from '../src/fileDiff.ts'

test('replacement shows original and new line numbers', () => {
  const rows = fileDiff('one\ntwo\nthree\n', 'one\nchanged\nthree\n')
  assert.deepEqual(rows.filter(r => r.kind === 'remove'), [{ kind: 'remove', text: 'two', before: 2, after: undefined }])
  assert.deepEqual(rows.filter(r => r.kind === 'add'), [{ kind: 'add', text: 'changed', before: undefined, after: 2 }])
})
test('new and empty files are reviewable', () => {
  assert.equal(fileDiff(null, 'new\n').filter(r => r.kind === 'add').length, 1)
  assert.deepEqual(fileDiff(null, ''), [])
})
test('missing final newline is explicit and HTML remains plain text', () => {
  const rows = fileDiff('', '<script>alert(1)</script>')
  assert.equal(rows.find(r => r.kind === 'add').text, '<script>alert(1)</script>')
  assert.ok(rows.some(r => r.kind === 'note' && r.text.includes('无换行')))
})
test('CRLF changes retain correct line numbers and avoid stray carriage returns', () => {
  const rows = fileDiff('one\r\ntwo\r\n', 'one\r\nnew\r\n')
  assert.equal(rows.find(r => r.kind === 'remove').text, 'two')
  assert.equal(rows.find(r => r.kind === 'add').after, 2)
})
test('large diffs fall back to full content instead of rendering excessive rows', () => {
  assert.equal(fileDiff('', 'line\n'.repeat(2600)), null)
})

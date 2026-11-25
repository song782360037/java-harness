import test from 'node:test'
import assert from 'node:assert/strict'
import { detectLanguage, formatFileSize, highlightCodeLine, rootLabel } from '../src/workspaceExplorer.ts'

test('detectLanguage maps common extensions and special filenames', () => {
  assert.deepEqual(detectLanguage('src/Main.java'), { id: 'java', label: 'Java' })
  assert.deepEqual(detectLanguage('harness-web/src/App.vue'), { id: 'vue', label: 'Vue' })
  assert.deepEqual(detectLanguage('Dockerfile'), { id: 'shell', label: 'Dockerfile' })
  assert.deepEqual(detectLanguage('README.unknown'), { id: 'text', label: 'Text' })
})

test('formatFileSize and rootLabel format compact sizes and multi-root names', () => {
  assert.equal(formatFileSize(512), '512 B')
  assert.equal(formatFileSize(1536), '1.5 KB')
  assert.equal(formatFileSize(2 * 1024 * 1024), '2.0 MB')
  assert.equal(rootLabel('E:\\projects\\java-harness', 0, 1), 'java-harness')
  assert.equal(rootLabel('/repos/backend/', 1, 2), '@2 backend')
})

test('highlightCodeLine tokenizes code safely and escapes raw HTML', () => {
  const java = highlightCodeLine('@Override public String name = "ok"; // note', 'java')
  assert.match(java, /class="tok-annotation">@Override<\/span>/)
  assert.match(java, /class="tok-keyword">public<\/span>/)
  assert.match(java, /class="tok-string">"ok"<\/span>/)
  assert.match(java, /class="tok-comment">\/\/ note<\/span>/)

  const sql = highlightCodeLine('select * from harness_session where id = 1 -- check', 'sql')
  assert.match(sql, /class="tok-keyword">select<\/span>/)
  assert.match(sql, /class="tok-number">1<\/span>/)
  assert.match(sql, /class="tok-comment">-- check<\/span>/)

  const xss = highlightCodeLine('const s = "<img src=x onerror=alert(1)>";', 'ts')
  assert.equal(xss.includes('<img'), false)
  assert.ok(xss.includes('&lt;img'))
})

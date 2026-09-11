import { test } from 'node:test'
import assert from 'node:assert/strict'
import { renderMarkdown } from '../src/markdown.ts'

test('renders headings, lists, emphasis and tables', () => {
  const html = renderMarkdown('# 分析结果\n\n- **入口**\n- 调用链\n\n| 类 | 作用 |\n| --- | --- |\n| App | 启动 |')
  for (const tag of ['<h1>', '<ul>', '<strong>', '<table>', '<th>']) assert.ok(html.includes(tag))
})
test('raw HTML and event handlers are displayed as text', () => {
  const html = renderMarkdown('<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n<svg onload=alert(1)>')
  assert.ok(!/<script|<img|<svg/.test(html))
  assert.ok(html.includes('&lt;script&gt;'))
})
test('executable URL schemes never become hyperlinks', () => {
  for (const url of ['javascript:alert(1)', 'data:text/html,test', 'vbscript:msgbox(1)', 'javascript&#58;alert(1)']) {
    assert.ok(!renderMarkdown(`[打开](${url})`).includes('<a '))
  }
})
test('valid hyperlinks open separately with isolation attributes', () => {
  const html = renderMarkdown('[文档](https://example.com/docs)')
  assert.ok(html.includes('href="https://example.com/docs"'))
  assert.ok(html.includes('target="_blank"'))
  assert.ok(html.includes('rel="noopener noreferrer"'))
})
test('model image URLs are not loaded or emitted as image elements', () => {
  const html = renderMarkdown('![<b>示例</b>](https://example.com/tracking.png)')
  assert.ok(!html.includes('<img'))
  assert.ok(!html.includes('https://example.com'))
  assert.ok(html.includes('&lt;b&gt;'))
})
test('completed and streaming code fences escape markup', () => {
  for (const content of ['```html\n<script>alert(1)</script>\n```', '```html\n<script>alert(1)</script>']) {
    const html = renderMarkdown(content)
    assert.ok(html.includes('<pre><code'))
    assert.ok(!html.includes('<script>'))
    assert.ok(html.includes('&lt;script&gt;'))
  }
})

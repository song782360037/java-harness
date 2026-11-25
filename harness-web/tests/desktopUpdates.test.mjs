import { test } from 'node:test'
import assert from 'node:assert/strict'
import { renderReleaseNotes } from '../src/markdown.ts'

let bootCount = 0
const state = (revision, phase = 'idle') => ({ revision, phase, version: '1.0.0', targetVersion: '1.1.0', supported: true, disabledReason: '', releaseNotes: '', percent: 0, lastChecked: '', error: '', restartRequired: false })

test('browser mode has no desktop operations', async () => {
  globalThis.window = {}
  const app = await import(`../src/desktopUpdates.ts?boot=${++bootCount}`)
  assert.equal(app.desktopAvailable, false)
  app.connectDesktopUpdates()()
  await app.runUpdateAction('check')
  assert.equal(app.updateState.value, undefined)
})

test('live update state wins over delayed initial snapshot and disconnect cleans up', async () => {
  let initial, listener, unsubscribed = false
  globalThis.window = { harnessDesktop: {
    getUpdateState: () => new Promise(resolve => { initial = resolve }),
    onUpdateState: callback => { listener = callback; return () => { unsubscribed = true } },
    installUpdate: async () => state(3, 'installing'),
    checkForUpdates: async () => { throw new Error('IPC unavailable') },
    restartCurrentVersion: async () => {},
  } }
  const app = await import(`../src/desktopUpdates.ts?boot=${++bootCount}`)
  const disconnect = app.connectDesktopUpdates()
  listener(state(2, 'ready'))
  initial(state(1))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(app.updateState.value.phase, 'ready')
  await app.runUpdateAction('check')
  assert.equal(app.updateError.value, 'IPC unavailable')
  await app.runUpdateAction('install')
  assert.equal(app.updateState.value.phase, 'installing')
  assert.equal(app.updateBusy.value, true)
  disconnect()
  listener(state(4, 'ready'))
  assert.equal(app.updateState.value.phase, 'installing')
  assert.equal(unsubscribed, true)
})

test('renderReleaseNotes renders GitHub HTML release notes as structured safe HTML', () => {
  const html = renderReleaseNotes(`
    <h2><a id="user-content-files" class="anchor" href="#files"><svg viewBox="0 0 16 16"></svg></a>工作区文件树与代码预览右侧栏</h2>
    <ul>
      <li><strong>多根目录</strong>：支持 <code>@1</code> / <code>&lt;tag&gt;</code>，详情见 <a href="https://github.com/song782360037/java-harness/releases?a=1&amp;b=2" onclick="alert(1)">发布页</a>。</li>
    </ul>
  `)
  assert.ok(html.includes('<h2>工作区文件树与代码预览右侧栏</h2>'))
  assert.ok(html.includes('<ul>'))
  assert.ok(html.includes('<strong>多根目录</strong>'))
  assert.ok(html.includes('<code>@1</code>'))
  assert.ok(html.includes('<code>&lt;tag&gt;</code>'))
  assert.ok(html.includes('<a href="https://github.com/song782360037/java-harness/releases?a=1&amp;b=2" target="_blank" rel="noopener noreferrer">发布页</a>'))
  assert.ok(!/<svg|onclick=|id=|class=/i.test(html))
})

test('renderReleaseNotes strips scripts, styles, unsafe links, inline handlers and external images', () => {
  const html = renderReleaseNotes(`
    <h2 onclick="alert(1)" style="color:red">更新内容</h2>
    <script>alert("xss")</script>
    <style>body { display: none }</style>
    <p><a href="javascript:alert(1)">恶意链接</a> <a href="javascript&#58;alert(2)">编码链接</a> <a href="data:text/html,test">数据链接</a></p>
    <img src="https://example.com/track.png" alt="截图" onerror="alert(1)">
    <iframe src="https://evil.example"></iframe>
    <p>保留正文</p>
  `)
  assert.ok(html.includes('<h2>更新内容</h2>'))
  assert.ok(html.includes('<p>保留正文</p>'))
  assert.ok(html.includes('[图片：截图]'))
  assert.ok(!/<script|<style|<iframe|<img|<a |onclick=|onerror=|style=|javascript:|alert\("xss"\)/i.test(html))
})

test('renderReleaseNotes handles markdown, plain text and standalone untrusted markup safely', () => {
  const markdownHtml = renderReleaseNotes('## 新功能\n\n- 修复 `<div>` 显示问题\n- 支持 **自动更新**')
  assert.ok(markdownHtml.includes('<h2>新功能</h2>'))
  assert.ok(markdownHtml.includes('<code>&lt;div&gt;</code>'))
  assert.ok(markdownHtml.includes('<strong>自动更新</strong>'))

  const untrusted = renderReleaseNotes('<script>untrusted</script>')
  assert.ok(!/<script/i.test(untrusted))
  assert.equal(renderReleaseNotes('   '), '')
})

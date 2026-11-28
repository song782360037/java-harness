<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { BotCard, BotMiniApp, BotPlaybook } from './api'

const props = defineProps<{
  content: string
  botId: string
  bots?: BotCard[]
  playbooks?: BotPlaybook[]
  apps?: BotMiniApp[]
  rawFileUrl: (botId: string, relativePath: string) => string
}>()

const emit = defineEmits<{
  openBot: [botId: string]
  openFlow: [flowId: string]
  openSkill: [target: string]
  openApp: [appId: string]
  openFile: [relativePath: string]
  openImage: [url: string, name: string]
}>()

const container = ref<HTMLElement>()
const copyTimers = new Set<ReturnType<typeof setTimeout>>()

function esc(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function safeLocalLibraryPath(raw: string): string | null {
  if (!raw) return null
  let rel: string | null = null
  if (raw.startsWith('file://')) {
    try {
      rel = decodeURIComponent(raw.slice('file://'.length))
    } catch {
      return null
    }
  } else if (raw.startsWith('library:')) {
    try {
      rel = decodeURIComponent(raw.slice('library:'.length))
    } catch {
      return null
    }
  } else {
    return null
  }
  rel = rel.replace(/\\/g, '/').replace(/^\/+/, '').trim()
  if (!rel || rel.includes('..') || rel.includes(':') || /[\u0000-\u001f]/.test(rel)) return null
  return rel
}

const rendered = computed(() => {
  const md = new MarkdownIt({ html: false, linkify: false, breaks: true })
  const defaultValidate = md.validateLink.bind(md)
  md.validateLink = (url: string) =>
    /^(bot:|flow:|skill:|app:|file:\/\/|library:)/i.test(url) || defaultValidate(url)

  const botMap = new Map((props.bots ?? []).map(c => [c.bot.id, c.bot]))
  const flowMap = new Map((props.playbooks ?? []).map(p => [p.id, p]))
  const appMap = new Map((props.apps ?? []).map(a => [a.id, a]))

  // Safe local library images only; external image URLs stay as text to avoid leakage.
  md.renderer.rules.image = (tokens, idx) => {
    const token = tokens[idx]!
    const src = String(token.attrGet('src') ?? '')
    const alt = token.content || '图片'
    const localRel = safeLocalLibraryPath(src)
    if (localRel && props.botId && /\.(png|jpe?g|webp|gif)$/i.test(localRel)) {
      const url = props.rawFileUrl(props.botId, localRel)
      return `<button type="button" class="bot-inline-img-btn" data-inline-image="${esc(url)}" data-inline-image-name="${esc(alt)}"><img src="${esc(url)}" alt="${esc(alt)}" loading="lazy" /></button>`
    }
    return esc(`[图片：${alt}]`)
  }

  // Transform custom inline mentions & local file links cleanly without losing markdown parsing.
  const openStack: string[] = []
  md.renderer.rules.link_open = (tokens, idx, options, _env, renderer) => {
    const token = tokens[idx]!
    const href = String(token.attrGet('href') ?? '')
    if (href.startsWith('bot:')) {
      const id = href.slice(4)
      const bot = botMap.get(id)
      const color = bot?.color || 'var(--accent)'
      openStack.push('button')
      return `<button type="button" class="bot-inline-mention is-bot" data-inline-bot="${esc(id)}" style="--mention-color:${esc(color)}"><span class="bot-inline-dot"></span>@`
    }
    if (href.startsWith('flow:')) {
      const id = href.slice(5)
      const flow = flowMap.get(id)
      openStack.push('button')
      return `<button type="button" class="bot-inline-mention is-flow" data-inline-flow="${esc(flow?.id || id)}">📋 `
    }
    if (href.startsWith('skill:')) {
      const target = href.slice(6)
      openStack.push('button')
      return `<button type="button" class="bot-inline-mention is-skill" data-inline-skill="${esc(target)}">🧩 `
    }
    if (href.startsWith('app:')) {
      const id = href.slice(4)
      const app = appMap.get(id)
      const color = app?.color || '#2d5d9c'
      openStack.push('button')
      return `<button type="button" class="bot-inline-mention is-app" data-inline-app="${esc(id)}" style="--mention-color:${esc(color)}"><span class="bot-inline-dot"></span>`
    }
    const localRel = safeLocalLibraryPath(href)
    if (localRel) {
      openStack.push('button')
      return `<button type="button" class="bot-inline-mention is-file" data-inline-file="${esc(localRel)}">📄 `
    }
    openStack.push('a')
    token.attrSet('target', '_blank')
    token.attrSet('rel', 'noopener noreferrer')
    return renderer.renderToken(tokens, idx, options)
  }

  md.renderer.rules.link_close = (tokens, idx, options, _env, renderer) => {
    const tag = openStack.pop()
    if (tag === 'button') return '</button>'
    return renderer.renderToken(tokens, idx, options)
  }

  return md.render(props.content || '')
})

function onClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  if (!target) return
  const btn = target.closest<HTMLElement>('[data-inline-bot],[data-inline-flow],[data-inline-skill],[data-inline-app],[data-inline-file],[data-inline-image]')
  if (!btn) return
  event.stopPropagation()
  if (btn.dataset.inlineBot) emit('openBot', btn.dataset.inlineBot)
  else if (btn.dataset.inlineFlow) emit('openFlow', btn.dataset.inlineFlow)
  else if (btn.dataset.inlineSkill) emit('openSkill', btn.dataset.inlineSkill)
  else if (btn.dataset.inlineApp) emit('openApp', btn.dataset.inlineApp)
  else if (btn.dataset.inlineFile) emit('openFile', btn.dataset.inlineFile)
  else if (btn.dataset.inlineImage) emit('openImage', btn.dataset.inlineImage, btn.dataset.inlineImageName || '图片')
}

function enhanceCodeBlocks() {
  const root = container.value
  if (!root) return
  for (const pre of root.querySelectorAll<HTMLPreElement>('pre')) {
    if (pre.parentElement?.classList.contains('md-code-block')) continue
    const code = pre.querySelector('code')
    const langMatch = code?.className.match(/(?:^|\s)language-([a-z0-9_+#.-]+)/i)
    const lang = langMatch?.[1] || ''
    const wrapper = document.createElement('div')
    wrapper.className = 'md-code-block'
    const bar = document.createElement('div')
    bar.className = 'md-code-bar'
    const label = document.createElement('span')
    label.className = 'md-code-lang'
    label.textContent = lang || 'code'
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'md-code-copy'
    button.textContent = '复制'
    button.addEventListener('click', async () => {
      const text = code?.textContent ?? pre.textContent ?? ''
      try {
        await navigator.clipboard.writeText(text)
        button.textContent = '已复制'
        button.classList.add('copied')
        const timer = setTimeout(() => {
          copyTimers.delete(timer)
          button.textContent = '复制'
          button.classList.remove('copied')
        }, 1800)
        copyTimers.add(timer)
      } catch {
        button.textContent = '复制失败'
      }
    })
    bar.append(label, button)
    pre.replaceWith(wrapper)
    wrapper.append(bar, pre)
  }
}

onMounted(enhanceCodeBlocks)
watch(rendered, () => { void nextTick(enhanceCodeBlocks) })
onUnmounted(() => {
  for (const timer of copyTimers) clearTimeout(timer)
  copyTimers.clear()
})
</script>

<template>
  <div ref="container" class="markdown-content bot-markdown" @click="onClick" v-html="rendered"></div>
</template>

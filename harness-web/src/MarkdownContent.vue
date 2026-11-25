<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { renderMarkdown } from './markdown'

const props = defineProps<{ content: string }>()
const rendered = computed(() => renderMarkdown(props.content))
const container = ref<HTMLElement>()
const copyTimers = new Set<ReturnType<typeof setTimeout>>()

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
    button.setAttribute('aria-label', '复制代码')
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
        const timer = setTimeout(() => {
          copyTimers.delete(timer)
          button.textContent = '复制'
        }, 1800)
        copyTimers.add(timer)
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
<template><div ref="container" class="markdown-content" v-html="rendered"></div></template>

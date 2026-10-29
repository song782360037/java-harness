<script setup lang="ts">
import { ref } from 'vue'
import type { ExtensionItem } from './extensions'
import UiIcon from './UiIcon.vue'
defineProps<{ item: ExtensionItem; locked: boolean; pending?: boolean }>()
defineEmits<{ preview: [item: ExtensionItem]; toggle: [item: ExtensionItem]; edit: [item: ExtensionItem]; remove: [item: ExtensionItem] }>()
const menu = ref<HTMLDetailsElement>()
/** Close a row menu when focus moves outside its actions. */
function blur(event: FocusEvent) { if (!menu.value?.contains(event.relatedTarget as Node)) menu.value?.removeAttribute('open') }
</script>
<template>
  <div class="extension-row" :class="{ 'extension-disabled': item.installationId && !item.enabled }">
    <button class="extension-row-main" :disabled="locked" @click="$emit('preview', item)">
      <span class="extension-item-icon" :class="item.icon"><UiIcon :name="item.icon" :size="26" /></span>
      <span class="extension-item-copy"><strong><span class="extension-item-title">{{ item.title }}</span><span v-if="item.official" class="extension-verified" title="Java Harness 官方提供"><UiIcon name="check" :size="10" /></span><small v-if="item.parentName" class="extension-bundled">随插件</small><small v-else-if="item.installationId && !item.enabled" class="extension-bundled">已停用</small></strong><span>{{ item.issue || item.description }}</span></span>
      <span v-if="!item.installationId" class="extension-row-action"><UiIcon name="plus" :size="17" /></span>
    </button>
    <div v-if="item.installationId" class="extension-row-controls">
      <details ref="menu" class="extension-row-menu" @focusout="blur" @keydown.esc.prevent="menu?.removeAttribute('open')"><summary :aria-label="`${item.title} 更多操作`"><UiIcon name="more" :size="16" /></summary><div>
        <button v-if="item.editable" :disabled="locked || pending" @click="menu?.removeAttribute('open'); $emit('edit', item)">编辑</button>
        <button :disabled="locked || pending" @click="menu?.removeAttribute('open'); $emit('remove', item)">{{ item.parentName ? '卸载所属插件' : '卸载' }}</button>
      </div></details>
      <span class="extension-status"><span v-if="item.enabled" class="extension-enabled-mark" aria-hidden="true"><UiIcon :name="item.issue ? 'warning' : 'check'" :size="17" /></span><button class="extension-switch" role="switch" :aria-checked="item.enabled" :aria-label="`${item.enabled ? '停用' : '启用'}${item.parentName || item.title}`" :disabled="locked || pending || !!item.issue && !item.enabled" :aria-busy="pending || undefined" :title="item.parentName ? '切换整个所属插件' : '对所有会话的新任务生效'" @click="$emit('toggle', item)"><span /></button></span>
    </div>
  </div>
</template>

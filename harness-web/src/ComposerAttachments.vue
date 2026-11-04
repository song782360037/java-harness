<script setup lang="ts">
import { formatSize, isImage, mentionLabel, mentionParts, type DraftAttachment, type FileEntry } from './composer'
import UiIcon from './UiIcon.vue'
defineProps<{ items: DraftAttachment[]; mentions: FileEntry[]; multiRoot: boolean; disabled: boolean }>()
defineEmits<{ remove: [key: string]; removeMention: [entry: FileEntry] }>()
</script>
<template>
  <ul v-if="items.length || mentions.length" class="composer-attachments" aria-label="待发送的引用与附件" aria-live="polite">
    <li v-for="entry in mentions" :key="`mention:${entry.root}:${entry.path}`" class="mention" :title="`@${mentionLabel(entry, multiRoot)}`">
      <UiIcon :name="entry.directory ? 'folder' : 'file'" :size="16" />
      <span><strong>{{ mentionParts(entry, multiRoot).name }}</strong><small>{{ mentionParts(entry, multiRoot).location }}</small></span>
      <button type="button" class="icon-button" :disabled="disabled" :aria-label="`移除引用 ${mentionParts(entry, multiRoot).name}`" @click="$emit('removeMention', entry)"><UiIcon name="close" :size="13" /></button>
    </li>
    <li v-for="item in items" :key="item.key" :class="{ 'has-error': item.error, uploading: !item.id && !item.error }" :title="item.error || `${item.name} · ${formatSize(item.size)}`">
      <img v-if="item.preview && isImage(item.mediaType)" :src="item.preview" alt="" />
      <UiIcon v-else :name="item.error ? 'warning' : 'file'" :size="16" />
      <span><strong>{{ item.name }}</strong><small>{{ item.error || (item.id ? formatSize(item.size) : '正在上传…') }}</small></span>
      <button type="button" class="icon-button" :disabled="disabled" :aria-label="`移除 ${item.name}`" @click="$emit('remove', item.key)"><UiIcon name="close" :size="13" /></button>
    </li>
  </ul>
</template>
<style scoped>
.composer-attachments { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 10px; padding: 0; list-style: none; }
li { display: flex; align-items: center; gap: 8px; max-width: 240px; min-width: 0; padding: 5px 4px 5px 6px; border: 0.5px solid var(--border); border-radius: var(--radius-md); background: var(--subtle); color: var(--muted); }
li.mention { color: var(--accent); }
li.has-error { border-color: var(--danger); color: var(--danger); }
li.uploading { opacity: .7; }
img { width: 32px; height: 32px; object-fit: cover; border-radius: 6px; flex-shrink: 0; }
span { min-width: 0; }
strong, small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
strong { font-size: var(--font-caption); font-weight: 550; color: var(--text); }
small { font-size: var(--font-caption); }
li.has-error small { color: inherit; white-space: normal; }
.icon-button { width: 24px; height: 24px; flex-shrink: 0; }
@media (max-width: 780px) { .icon-button { width: 36px; height: 36px; } }
</style>

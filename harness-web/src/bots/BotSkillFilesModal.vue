<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import type { BotSkillFileContent, BotSkillFileView } from './api'

const props = defineProps<{
  title: string
  subtitle?: string
  loadFiles: () => Promise<BotSkillFileView[]>
  loadFile: (path: string) => Promise<BotSkillFileContent>
}>()

const emit = defineEmits<{ close: [] }>()

const files = ref<BotSkillFileView[]>([])
const loadingList = ref(true)
const selectedPath = ref('')
const preview = ref<BotSkillFileContent | null>(null)
const loadingFile = ref(false)
const error = ref('')

async function init() {
  loadingList.value = true
  error.value = ''
  files.value = []
  preview.value = null
  selectedPath.value = ''
  try {
    files.value = await props.loadFiles()
    if (files.value.length) {
      await openFile(files.value[0].path)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取技能文件列表失败'
  } finally {
    loadingList.value = false
  }
}

async function openFile(path: string) {
  selectedPath.value = path
  loadingFile.value = true
  error.value = ''
  try {
    preview.value = await props.loadFile(path)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '读取文件内容失败'
  } finally {
    loadingFile.value = false
  }
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

onMounted(() => { void init() })
watch(() => props.title, () => { void init() })
</script>

<template>
  <div class="bot-modal-scrim" @click.self="emit('close')">
    <div class="bot-modal is-wide skill-files-modal" role="dialog" :aria-label="`${title} 文件预览`">
      <header>
        <div>
          <h3>{{ title }}</h3>
          <small v-if="subtitle">{{ subtitle }}</small>
        </div>
        <button type="button" class="icon-button" aria-label="关闭" @click="emit('close')">
          <UiIcon name="close" :size="15" />
        </button>
      </header>

      <p v-if="error" class="dialog-error">{{ error }}</p>

      <div class="skill-files-layout">
        <aside class="skill-files-sidebar">
          <div v-if="loadingList" class="skill-files-empty">加载目录中…</div>
          <div v-else-if="!files.length" class="skill-files-empty">暂无文件</div>
          <button
            v-for="f in files"
            :key="f.path"
            type="button"
            class="skill-file-item"
            :class="{ active: selectedPath === f.path }"
            @click="openFile(f.path)"
          >
            <UiIcon :name="f.script ? 'terminal' : 'file'" :size="13" />
            <span class="skill-file-path">{{ f.path }}</span>
            <span v-if="f.script" class="skill-script-badge is-mini">脚本</span>
            <small>{{ formatBytes(f.bytes) }}</small>
          </button>
        </aside>

        <section class="skill-files-viewer">
          <div v-if="loadingFile" class="skill-files-empty">读取文件中…</div>
          <template v-else-if="preview">
            <div class="skill-viewer-head">
              <strong>{{ preview.path }}</strong>
              <small>{{ formatBytes(preview.bytes) }}</small>
            </div>
            <div v-if="preview.binary" class="skill-files-empty">二进制文件，不支持文本预览</div>
            <div v-else-if="preview.tooLarge" class="skill-files-empty">文件超过 400 KB，已跳过预览</div>
            <pre v-else class="skill-viewer-code">{{ preview.content }}</pre>
          </template>
          <div v-else class="skill-files-empty">选择左侧文件查看内容</div>
        </section>
      </div>
    </div>
  </div>
</template>

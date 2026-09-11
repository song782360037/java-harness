<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, type DirectoryListing, type Session } from './api'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ sessionId?: string; initialPath: string; disabled?: boolean }>()
const emit = defineEmits<{ saved: [session: Session]; busy: [value: boolean] }>()
const title = ref('新会话')
const path = ref(props.initialPath)
const listing = ref<DirectoryListing | null>(null)
const browsing = ref(false)
const saving = ref(false)
const error = ref('')
let version = 0
async function browse(value: string) {
  const current = ++version
  browsing.value = true; error.value = ''
  try {
    const result = await api<DirectoryListing>(`/workspaces/directories?path=${encodeURIComponent(value)}`)
    if (current !== version) return
    listing.value = result; path.value = result.path
  } catch (cause) { if (current === version) error.value = cause instanceof Error ? cause.message : '读取目录失败' }
  finally { if (current === version) browsing.value = false }
}
async function save(chatOnly = false) {
  if (saving.value || browsing.value || props.disabled) return
  saving.value = true; error.value = ''
  emit('busy', true)
  try {
    const workspace = chatOnly ? '' : path.value
    const session = props.sessionId
      ? await api<Session>(`/sessions/${props.sessionId}/workspace`, { workspace })
      : await api<Session>('/sessions', { title: title.value, workspace })
    emit('saved', session)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存工作区失败' }
  finally { saving.value = false; emit('busy', false) }
}
onMounted(() => { void browse(props.initialPath) })
onUnmounted(() => { version++ })
</script>
<template>
  <section class="workspace-settings" aria-labelledby="workspace-title">
    <form @submit.prevent="save()">
      <h3 id="workspace-title">{{ sessionId ? '会话工作区' : '新建会话' }}</h3>
      <p class="dialog-note">选择这次会话要分析的本机代码目录。文件内容会在工具调用时发送给模型服务。</p>
      <p v-if="sessionId" class="field-note">更换后，绑定其他工作区的历史不会带入新任务。</p>
      <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
      <p v-if="disabled" class="field-note">当前任务尚未结束或提交结果待确认，结束后可以更换工作区。</p>
      <fieldset :disabled="saving || disabled">
        <template v-if="!sessionId"><label for="session-title">会话名称</label><input id="session-title" v-model="title" maxlength="100" required /></template>
        <label for="workspace-path">工作区绝对路径</label>
        <div class="path-input"><input id="workspace-path" v-model="path" maxlength="4096" placeholder="例如 E:\projects\my-project" /><button type="button" :disabled="browsing" @click="browse(path)">浏览</button></div>
        <div class="directory-toolbar"><button type="button" :disabled="browsing" @click="browse('')">磁盘列表</button><button type="button" :disabled="browsing || !listing?.path" @click="browse(listing?.parent || '')">上一级</button></div>
        <p v-if="browsing">正在读取目录…</p>
        <div v-else class="directory-list" aria-label="本机目录">
          <p v-if="listing && !listing.directories.length">没有可浏览的子目录，可以直接选择当前目录。</p>
          <button v-for="entry in listing?.directories" :key="entry.path" type="button" @click="browse(entry.path)"><UiIcon name="folder" :size="16" /> {{ entry.name }}</button>
        </div>
        <p v-if="listing?.truncated" class="field-note">目录条目较多，当前仅展示一部分，可直接输入完整路径。</p>
      </fieldset>
      <div class="dialog-actions"><button type="button" :disabled="saving || browsing || disabled" @click="save(true)">仅聊天，不使用工作区</button><button class="primary" :disabled="saving || browsing || disabled || !path.trim()">{{ saving ? '保存中…' : sessionId ? '保存工作区' : '创建会话' }}</button></div>
    </form>
  </section>
</template>

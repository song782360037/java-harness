<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { api, type DirectoryListing, type Session, type Workspace } from './api'
import { directoryName, workspaceColors, workspaceDirectories, workspaceIcons } from './workspaces'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ sessionId?: string; initialPath: string; initialWorkspace?: Workspace; disabled?: boolean }>()
const emit = defineEmits<{ saved: [session: Session]; busy: [value: boolean]; cancel: [] }>()
const savedWorkspace = ref(props.initialWorkspace)
const name = ref(props.initialWorkspace?.name || '')
const directories = ref([...(props.initialWorkspace?.directories || workspaceDirectories(props.initialPath))])
const icon = ref(props.initialWorkspace?.icon || 'folder')
const color = ref(props.initialWorkspace?.color || workspaceColors[0]!)
const path = ref(directories.value[0] || '')
const listing = ref<DirectoryListing | null>(null)
const pickerOpen = ref(false)
const browsing = ref(false)
const saving = ref(false)
const error = ref('')
let version = 0
// 使用已有本机目录浏览接口定位要添加的目录。
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
// 打开目录选择器，输入路径或逐级浏览。
function openPicker() { pickerOpen.value = true; void browse(path.value) }
// 添加服务端已确认可访问的当前目录，避免重复路径。
async function addDirectory() {
  if (browsing.value || saving.value || props.disabled) return
  await browse(path.value)
  if (!listing.value?.path || error.value) return
  if (!directories.value.includes(listing.value.path)) directories.value.push(listing.value.path)
  if (!name.value.trim()) name.value = directoryName(listing.value.path).slice(0, 100)
  pickerOpen.value = false
}
// 保存共享工作区后绑定会话；绑定失败时保留 ID，重试不会重复创建。
async function save() {
  if (saving.value || browsing.value || props.disabled || !name.value.trim() || !directories.value.length) return
  saving.value = true; error.value = ''
  emit('busy', true)
  try {
    const current = savedWorkspace.value
    const workspace = await api<Workspace>(current ? `/workspaces/${current.id}` : '/workspaces', {
      name: name.value, directories: directories.value, icon: icon.value, color: color.value, revision: current?.revision || 0,
    })
    savedWorkspace.value = workspace
    const session = props.sessionId
      ? await api<Session>(`/sessions/${props.sessionId}/workspace`, { workspaceId: workspace.id })
      : await api<Session>('/sessions', { title: '新会话', workspaceId: workspace.id })
    emit('saved', session)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存工作区失败' }
  finally { saving.value = false; emit('busy', false) }
}
onUnmounted(() => { version++ })
</script>
<template>
  <section class="workspace-settings" aria-label="工作区设置">
    <form @submit.prevent="save()">
      <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
      <p v-if="disabled" class="field-note">当前任务或提交尚未结束，结束后可以调整工作区。</p>
      <fieldset :disabled="saving || disabled">
        <div class="workspace-fields">
          <label>源文件夹</label>
          <div class="workspace-sources" :class="{ empty: !directories.length }">
            <div v-for="(directory, index) in directories" :key="directory" class="workspace-source-row">
              <UiIcon name="folder" :size="20" :style="{ color }" />
              <div><strong>{{ directoryName(directory) }}</strong><small :title="directory">{{ directory }}</small></div>
              <button type="button" class="icon-button" :aria-label="`移除目录：${directory}`" @click="directories.splice(index, 1)"><UiIcon name="close" :size="16" /></button>
            </div>
            <button type="button" class="workspace-add-source" :disabled="directories.length >= 16 || browsing" @click="openPicker"><UiIcon name="plus" :size="20" /><span>{{ directories.length ? '添加目录' : '点击添加可读写文件夹' }}</span></button>
          </div>
          <div v-if="pickerOpen" class="workspace-directory-picker">
            <label for="workspace-path">目录绝对路径</label>
            <div class="path-input"><input id="workspace-path" v-model="path" maxlength="4096" placeholder="例如 E:\projects\my-project" @keydown.enter.prevent="browse(path)" /><button type="button" :disabled="browsing" @click="browse(path)">浏览</button></div>
            <div class="directory-toolbar"><button type="button" :disabled="browsing" @click="browse('')">磁盘列表</button><button type="button" :disabled="browsing || !listing?.path" @click="browse(listing?.parent || '')">上一级</button></div>
            <p v-if="browsing" class="field-note">正在读取目录…</p>
            <div v-else class="directory-list" aria-label="本机目录">
              <p v-if="listing && !listing.directories.length">没有可浏览的子目录，可以添加当前目录。</p>
              <button v-for="entry in listing?.directories" :key="entry.path" type="button" @click="browse(entry.path)"><UiIcon name="folder" :size="16" /> {{ entry.name }}</button>
            </div>
            <p v-if="listing?.truncated" class="field-note">当前仅展示部分目录，可直接输入完整路径。</p>
            <div class="workspace-picker-actions"><button type="button" :disabled="browsing" @click="pickerOpen = false">收起</button><button type="button" :disabled="browsing || !path.trim() || directories.length >= 16" @click="addDirectory">添加此目录</button></div>
          </div>
          <label for="workspace-name">工作区名称</label><input id="workspace-name" v-model="name" maxlength="100" placeholder="输入名称…" required />
          <label id="workspace-icon-label">工作区图标</label>
          <div class="workspace-presets" role="group" aria-labelledby="workspace-icon-label">
            <button v-for="item in workspaceIcons" :key="item.value" type="button" :aria-label="item.label" :title="item.label" :aria-pressed="icon === item.value" :style="{ '--workspace-color': color }" @click="icon = item.value"><UiIcon :name="item.value" :size="21" /></button>
          </div>
          <label id="workspace-color-label">工作区颜色</label>
          <div class="workspace-presets" role="group" aria-labelledby="workspace-color-label">
            <button v-for="item in workspaceColors" :key="item" type="button" :aria-label="`颜色 ${item}`" :title="item" :aria-pressed="color === item" :style="{ '--workspace-color': item }" @click="color = item"><span class="workspace-color-dot" :style="{ background: item }"></span></button>
          </div>
        </div>
      </fieldset>
      <div class="dialog-actions"><button type="button" :disabled="saving" @click="emit('cancel')">取消</button><button class="primary" :disabled="saving || browsing || disabled || !name.trim() || !directories.length">{{ saving ? '保存中…' : savedWorkspace ? '保存' : '创建' }}</button></div>
    </form>
  </section>
</template>

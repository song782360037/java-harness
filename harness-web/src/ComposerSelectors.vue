<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { effortLabel, type ModelProfile, type Workspace } from './api'
import ReasoningSlider from './ReasoningSlider.vue'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ profiles: ModelProfile[]; modelId: string; effort: string; disabled: boolean; workspaceId: string; workspaces: Workspace[]; workspaceDisabled: boolean }>()
const emit = defineEmits<{ model: [id: string]; effort: [value: string]; settings: []; workspace: [id: string]; browse: []; edit: [] }>()
const root = ref<HTMLElement>()
const modelButton = ref<HTMLButtonElement>()
const effortButton = ref<HTMLButtonElement>()
const searchInput = ref<HTMLInputElement>()
const workspaceButton = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const open = ref<'model' | 'effort' | 'workspace' | null>(null)
const menuPosition = ref<Record<string, string>>({})
const search = ref('')
const selected = computed(() => props.profiles.find(model => model.id === props.modelId))
const selectedWorkspace = computed(() => props.workspaces.find(workspace => workspace.id === props.workspaceId))
const matchingWorkspaces = computed(() => props.workspaces.filter(workspace => `${workspace.name} ${workspace.directories.join(' ')}`.toLowerCase().includes(search.value.toLowerCase().trim())))
const groups = computed(() => {
  const result = new Map<string, { name: string; models: ModelProfile[] }>()
  for (const model of props.profiles) {
    if (!`${model.providerName} ${model.displayName} ${model.modelName}`.toLowerCase().includes(search.value.toLowerCase().trim())) continue
    if (!result.has(model.providerId)) result.set(model.providerId, { name: model.providerName, models: [] })
    result.get(model.providerId)!.models.push(model)
  }
  return [...result.entries()]
})
// 打开菜单并聚焦搜索框或当前选项。
async function toggle(value: NonNullable<typeof open.value>) {
  if (props.disabled || (value === 'workspace' && props.workspaceDisabled)) return
  open.value = open.value === value ? null : value
  search.value = ''
  if (open.value) {
    const button = value === 'model' ? modelButton.value : value === 'workspace' ? workspaceButton.value : effortButton.value
    const bounds = button?.getBoundingClientRect()
    if (bounds) {
      const width = Math.min(value === 'workspace' ? 340 : value === 'model' ? 310 : 320, window.innerWidth - 24)
      menuPosition.value = { left: `${Math.max(12, Math.min(bounds.left, window.innerWidth - width - 12))}px`,
        bottom: `${window.innerHeight - bounds.top + 8}px`, width: `${width}px`, maxHeight: `${Math.max(100, Math.min(480, bounds.top - 20))}px` }
    }
  }
  await nextTick()
  if (open.value === 'model' || open.value === 'workspace') searchInput.value?.focus()
  if (open.value === 'effort') {
    panel.value?.querySelector<HTMLInputElement>('input[type="range"]')?.focus()
  }
}
// 关闭菜单，可恢复到对应入口。
function close(restoreFocus = false) {
  const previous = open.value
  open.value = null
  if (restoreFocus) (previous === 'model' ? modelButton.value : previous === 'workspace' ? workspaceButton.value : effortButton.value)?.focus()
}
// 点击菜单外部时关闭。
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) close() }
// Escape 关闭菜单并恢复焦点。
function escape(event: KeyboardEvent) { if (event.key === 'Escape' && open.value) { event.preventDefault(); close(true) } }
// Tab 离开选择区域时关闭菜单。
function focusOut(event: FocusEvent) { if (!root.value?.contains(event.relatedTarget as Node)) close() }
// 使用方向键在可用菜单选项之间移动。
function navigate(event: KeyboardEvent) {
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  if (event.target instanceof HTMLInputElement && ['Home', 'End'].includes(event.key)) return
  const options = [...(panel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') || [])]
  if (!options.length) return
  event.preventDefault()
  const index = options.indexOf(event.target as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
    : event.key === 'ArrowDown' ? (index + 1) % options.length : (index <= 0 ? options.length : index) - 1
  options[next]?.focus()
}
watch(() => props.workspaceDisabled, value => { if (value && open.value === 'workspace') close() })
watch(() => props.disabled, value => { if (value) close() })
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape); window.addEventListener('resize', outsideResize) })
onUnmounted(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); window.removeEventListener('resize', outsideResize) })
// 视口改变时关闭菜单，避免位置继续依赖旧尺寸。
function outsideResize() { close() }
</script>
<template>
  <div ref="root" class="composer-selectors" @focusout="focusOut">
    <div class="selector-anchor workspace-anchor">
      <button id="composer-workspace" ref="workspaceButton" type="button" class="composer-chip workspace-chip" :disabled="disabled || workspaceDisabled" :aria-expanded="open === 'workspace'" aria-controls="workspace-options" aria-label="选择工作区" :title="selectedWorkspace?.directories.join('\n') || '仅聊天'" @click="toggle('workspace')"><UiIcon :name="selectedWorkspace?.icon || 'folder'" :size="15" :style="{ color: selectedWorkspace?.color }" /><span>{{ selectedWorkspace?.name || '仅聊天' }}</span><UiIcon class="chevron" name="down" :size="11" /></button>
      <div v-if="open === 'workspace'" id="workspace-options" ref="panel" :style="menuPosition" class="selector-popover workspace-popover" aria-label="工作区选择" @keydown="navigate">
        <div class="popover-heading">工作区</div>
        <input ref="searchInput" v-model="search" class="model-search" placeholder="搜索项目或路径" aria-label="搜索工作区" @keydown.enter.prevent />
        <div class="model-options-scroll">
          <button type="button" class="selector-option" :aria-pressed="!workspaceId" @click="emit('workspace', ''); close(true)"><UiIcon name="chat" :size="16" /><span><strong>仅聊天</strong><small>不使用项目文件</small></span><UiIcon v-if="!workspaceId" name="check" :size="15" /></button>
          <h3 v-if="matchingWorkspaces.length" class="option-group-label">已保存的工作区</h3>
          <button v-for="workspace in matchingWorkspaces" :key="workspace.id" type="button" class="selector-option" :aria-pressed="workspace.id === workspaceId" :title="workspace.directories.join('\n')" @click="emit('workspace', workspace.id); close(true)"><UiIcon :name="workspace.icon" :size="16" :style="{ color: workspace.color }" /><span><strong>{{ workspace.name }}</strong><small>{{ workspace.directories.length }} 个目录 · {{ workspace.directories.join(' · ') }}</small></span><UiIcon v-if="workspace.id === workspaceId" name="check" :size="15" /></button>
          <p v-if="search && !matchingWorkspaces.length" class="no-options">没有匹配的工作区</p>
        </div>
        <button v-if="selectedWorkspace" type="button" class="manage-models" @click="close(true); emit('edit')"><span><UiIcon name="edit" :size="15" /> 编辑当前工作区</span></button>
        <button type="button" class="manage-models" @click="close(true); emit('browse')"><span><UiIcon name="plus" :size="15" /> 新建工作区</span><UiIcon name="chevron" :size="13" /></button>
      </div>
    </div>
    <span class="selector-divider" aria-hidden="true"></span>
    <div class="selector-anchor">
      <button id="composer-model" ref="modelButton" type="button" class="composer-chip" :disabled="disabled" :aria-expanded="open === 'model'" aria-controls="model-options" aria-label="选择模型" :title="selected ? `${selected.providerName} / ${selected.modelName}` : '请选择模型'" @click="toggle('model')"><span>{{ selected?.displayName || selected?.modelName || '选择模型' }}</span><UiIcon class="chevron" name="down" :size="12" /></button>
      <div v-if="open === 'model'" id="model-options" :style="menuPosition" ref="panel" @keydown="navigate" class="selector-popover model-popover" aria-label="可用模型">
        <div class="popover-heading">模型</div>
        <input ref="searchInput" v-model="search" class="model-search" placeholder="搜索模型或提供商" aria-label="搜索模型或提供商" @keydown.enter.prevent />
        <div class="model-options-scroll">
          <section v-for="[id, group] in groups" :key="id" class="model-option-group">
            <h3>{{ group.name }}</h3>
            <button v-for="profile in group.models" :key="profile.id" type="button" class="selector-option" :aria-pressed="profile.id === modelId" @click="emit('model', profile.id); close(true)"><span><strong>{{ profile.displayName }}</strong><small>{{ profile.modelName || '未设置模型 ID' }}{{ profile.model.configured ? '' : ' · 待配置' }}</small></span><UiIcon v-if="profile.id === modelId" name="check" :size="15" /></button>
          </section>
          <p v-if="!groups.length" class="no-options">没有匹配的模型</p>
        </div>
        <button type="button" class="manage-models" @click="close(true); emit('settings')">管理模型 <UiIcon name="settings" :size="14" /></button>
      </div>
    </div>
    <span class="selector-divider" aria-hidden="true"></span>
    <div class="selector-anchor effort-anchor">
      <button id="composer-effort" ref="effortButton" type="button" class="composer-chip effort-chip" :disabled="disabled" :aria-expanded="open === 'effort'" aria-controls="effort-options" aria-label="选择思考强度" @click="toggle('effort')"><UiIcon name="reasoning" :size="15" /><span>{{ effortLabel(effort).split(' · ')[0] }}</span><UiIcon class="chevron" name="down" :size="12" /></button>
      <div v-if="open === 'effort'" id="effort-options" :style="menuPosition" ref="panel" class="selector-popover effort-popover" aria-label="思考强度">
        <ReasoningSlider :model-value="effort" :disabled="disabled" @update:model-value="emit('effort', $event)" />
      </div>
    </div>
  </div>
</template>

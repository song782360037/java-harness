<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { effortLabel, reasoningOptions, type ModelProfile } from './api'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ profiles: ModelProfile[]; modelId: string; effort: string; disabled: boolean }>()
const emit = defineEmits<{ model: [id: string]; effort: [value: string]; settings: [] }>()
const root = ref<HTMLElement>()
const modelButton = ref<HTMLButtonElement>()
const effortButton = ref<HTMLButtonElement>()
const searchInput = ref<HTMLInputElement>()
const effortPanel = ref<HTMLElement>()
const open = ref<'model' | 'effort' | null>(null)
const search = ref('')
const selected = computed(() => props.profiles.find(model => model.id === props.modelId))
const groups = computed(() => {
  const result = new Map<string, { name: string; models: ModelProfile[] }>()
  for (const model of props.profiles) {
    if (!`${model.providerName} ${model.displayName} ${model.modelName}`.toLowerCase().includes(search.value.toLowerCase().trim())) continue
    if (!result.has(model.providerId)) result.set(model.providerId, { name: model.providerName, models: [] })
    result.get(model.providerId)!.models.push(model)
  }
  return [...result.entries()]
})
async function toggle(value: 'model' | 'effort') {
  if (props.disabled) return
  open.value = open.value === value ? null : value
  search.value = ''
  await nextTick()
  if (open.value === 'model') searchInput.value?.focus()
  if (open.value === 'effort') effortPanel.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus()
}
function close(restoreFocus = false) {
  const previous = open.value
  open.value = null
  if (restoreFocus) (previous === 'model' ? modelButton.value : effortButton.value)?.focus()
}
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) close() }
function escape(event: KeyboardEvent) { if (event.key === 'Escape' && open.value) { event.preventDefault(); close(true) } }
function focusOut(event: FocusEvent) { if (!root.value?.contains(event.relatedTarget as Node)) close() }
watch(() => props.disabled, value => { if (value) close() })
onMounted(() => { document.addEventListener('pointerdown', outside); document.addEventListener('keydown', escape) })
onUnmounted(() => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape) })
</script>
<template>
  <div ref="root" class="composer-selectors" @focusout="focusOut">
    <div class="selector-anchor">
      <button id="composer-model" ref="modelButton" type="button" class="composer-chip" :disabled="disabled" :aria-expanded="open === 'model'" aria-controls="model-options" aria-label="选择模型" :title="selected ? `${selected.providerName} / ${selected.modelName}` : '请选择模型'" @click="toggle('model')"><span>{{ selected?.modelName || selected?.displayName || '选择模型' }}</span><UiIcon class="chevron" name="down" :size="12" /></button>
      <div v-if="open === 'model'" id="model-options" class="selector-popover model-popover" aria-label="可用模型">
        <div class="popover-heading">选择模型</div>
        <input ref="searchInput" v-model="search" class="model-search" placeholder="搜索模型或提供商" aria-label="搜索模型或提供商" @keydown.enter.prevent />
        <div class="model-options-scroll">
          <section v-for="[id, group] in groups" :key="id" class="model-option-group">
            <h3>{{ group.name }}</h3>
            <button v-for="profile in group.models" :key="profile.id" type="button" class="selector-option" :aria-pressed="profile.id === modelId" @click="emit('model', profile.id); close(true)"><span><strong>{{ profile.displayName }}</strong><small>{{ profile.modelName || '未设置模型 ID' }}{{ profile.model.configured ? '' : ' · 待配置' }}</small></span><UiIcon v-if="profile.id === modelId" name="check" :size="15" /></button>
          </section>
          <p v-if="!groups.length" class="no-options">没有匹配的模型</p>
        </div>
        <button type="button" class="manage-models" @click="close(); emit('settings')">管理模型 <UiIcon name="settings" :size="14" /></button>
      </div>
    </div>
    <span class="selector-divider" aria-hidden="true"></span>
    <div class="selector-anchor effort-anchor">
      <button id="composer-effort" ref="effortButton" type="button" class="composer-chip effort-chip" :disabled="disabled" :aria-expanded="open === 'effort'" aria-controls="effort-options" aria-label="选择思考强度" @click="toggle('effort')"><span>{{ effortLabel(effort).split(' · ')[0] }}</span><UiIcon class="chevron" name="down" :size="12" /></button>
      <div v-if="open === 'effort'" id="effort-options" ref="effortPanel" class="selector-popover effort-popover" aria-label="思考强度">
        <div class="popover-heading">思考强度</div><p class="popover-description">用于下一条消息，支持范围取决于模型。</p>
        <button v-for="option in reasoningOptions" :key="option.value" type="button" class="selector-option" :aria-pressed="option.value === effort" @click="emit('effort', option.value); close(true)"><span>{{ option.label }}</span><UiIcon v-if="option.value === effort" name="check" :size="15" /></button>
      </div>
    </div>
  </div>
</template>

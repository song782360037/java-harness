<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { connectDesktopUpdates, runUpdateAction, updateError, updateState, updateStatus } from './desktopUpdates'
const emit = defineEmits<{ details: [] }>()
const dismissed = ref('')
const visible = computed(() => updateState.value?.restartRequired || updateState.value?.phase === 'installing' || updateState.value?.phase === 'ready' && dismissed.value !== updateState.value.targetVersion)
let disconnect = () => {}
onMounted(() => { disconnect = connectDesktopUpdates() })
onUnmounted(() => disconnect())
</script>
<template>
  <aside v-if="visible" class="desktop-update-notice" aria-label="客户端更新">
    <strong role="status" aria-live="polite">{{ updateStatus }}</strong>
    <p v-if="updateError || updateState?.error" role="alert">{{ updateError || updateState?.error }}</p>
    <div class="update-notice-actions">
      <button v-if="updateState?.restartRequired" type="button" @click="runUpdateAction('restart')">重启当前版本</button>
      <button v-else-if="updateState?.phase === 'ready'" type="button" @click="runUpdateAction('install')">重启更新</button>
      <template v-if="updateState?.phase !== 'installing'"><button type="button" @click="emit('details')">查看详情</button><button v-if="!updateState?.restartRequired" type="button" @click="dismissed = updateState?.targetVersion || ''">稍后</button></template>
    </div>
  </aside>
</template>

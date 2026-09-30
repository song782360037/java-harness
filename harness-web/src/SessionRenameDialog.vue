<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type Session } from './api'
const props = defineProps<{ session: Session }>()
const emit = defineEmits<{ saved: [session: Session]; close: [] }>()
const dialog = ref<HTMLDialogElement>()
const title = ref(props.session.title)
const saving = ref(false)
const error = ref('')
// 保存成功后通知列表；失败时保留输入以便重试。
async function save() {
  if (saving.value) return
  const normalized = title.value.trim()
  if (!normalized || normalized.length > 100) { error.value = '会话名称需为 1–100 个字符'; return }
  saving.value = true; error.value = ''
  try {
    emit('saved', await api<Session>(`/sessions/${props.session.id}/title`, { title: normalized }))
    dialog.value?.close()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重命名失败' }
  finally { saving.value = false }
}
onMounted(() => dialog.value?.showModal())
</script>
<template>
  <dialog ref="dialog" class="rename-dialog" aria-labelledby="rename-heading" @close="emit('close')" @keydown.esc.stop @cancel="saving && $event.preventDefault()">
    <form @submit.prevent="save">
      <h2 id="rename-heading">重命名会话</h2>
      <label for="rename-title">会话名称</label>
      <input id="rename-title" v-model="title" :disabled="saving" aria-describedby="rename-note" required autofocus />
      <p id="rename-note" class="field-note">名称需为 1–100 个字符。</p>
      <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
      <div class="dialog-actions"><button type="button" :disabled="saving" @click="dialog?.close()">取消</button><button class="primary" :disabled="saving || !title.trim()">{{ saving ? '保存中…' : '保存' }}</button></div>
    </form>
  </dialog>
</template>

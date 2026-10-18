<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, type Session } from './api'
const props = defineProps<{ session: Session }>()
const emit = defineEmits<{ deleted: [session: Session]; close: [] }>()
const dialog = ref<HTMLDialogElement>()
const deleting = ref(false)
const error = ref('')
// 删除成功后通知列表；失败时保留弹窗以便重试。
async function confirm() {
  if (deleting.value) return
  deleting.value = true; error.value = ''
  try {
    await api<void>(`/sessions/${props.session.id}/delete`, {})
    emit('deleted', props.session)
    dialog.value?.close()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除失败' }
  finally { deleting.value = false }
}
onMounted(() => dialog.value?.showModal())
</script>
<template>
  <dialog ref="dialog" class="rename-dialog" aria-labelledby="delete-heading" @close="emit('close')" @keydown.esc.stop @cancel="deleting && $event.preventDefault()">
    <form @submit.prevent="confirm">
      <h2 id="delete-heading">删除会话</h2>
      <p class="dialog-note">确定删除会话「{{ session.title }}」吗？会话中的对话记录将一并删除，且无法恢复。</p>
      <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
      <div class="dialog-actions"><button type="button" :disabled="deleting" @click="dialog?.close()">取消</button><button class="primary danger" :disabled="deleting">{{ deleting ? '删除中…' : '删除' }}</button></div>
    </form>
  </dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, approvalModeOptions, effortLabel, reasoningOptions, type ModelProfile, type Workspace } from './api'
import { avatarText, emptyGroupDraft, emptyMemberDraft, groupDraft, memberDraft, modeOptions,
  type GroupDraft, type GroupMember, type GroupSnapshot, type GroupTemplate, type MemberDraft } from './groups'
import UiIcon from './UiIcon.vue'
const props = defineProps<{ snapshot?: GroupSnapshot; workspaces: Workspace[]; profiles: ModelProfile[]; templates: GroupTemplate[]; defaultProfileId: string }>()
const emit = defineEmits<{ saved: [value: GroupSnapshot]; deleted: [id: string]; close: [] }>()
const dialog = ref<HTMLDialogElement>()
const form = ref<GroupDraft>(props.snapshot ? groupDraft(props.snapshot.group) : emptyGroupDraft(props.defaultProfileId))
const members = ref<GroupMember[]>(props.snapshot?.members ?? [])
const editingMember = ref<{ id: string; draft: MemberDraft } | null>(null)
const confirmingDelete = ref('')
const busy = ref(false)
const error = ref('')
const editing = computed(() => !!props.snapshot)
const groupBusy = computed(() => !!props.snapshot?.busy)
// 编辑时只能补充群里还没有的模板成员。
const availableTemplates = computed(() => props.templates.filter(template => !members.value.some(member => member.template === template.key)))
const profileName = (id: string) => props.profiles.find(item => item.id === id)?.displayName || id
const message = (cause: unknown, fallback: string) => cause instanceof Error ? cause.message : fallback

function toggleTemplate(key: string) {
  const next = new Set(form.value.templates)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  // 按模板定义顺序加入，群里的成员顺序与模板列表一致。
  form.value.templates = props.templates.map(template => template.key).filter(value => next.has(value))
}
async function saveGroup() {
  busy.value = true; error.value = ''
  try {
    const saved = await api<GroupSnapshot>(editing.value ? `/groups/${props.snapshot!.group.id}` : '/groups', form.value)
    emit('saved', saved)
    if (editing.value) { form.value = groupDraft(saved.group); members.value = saved.members }
    else close(true)
  } catch (cause) { error.value = message(cause, '保存失败') }
  finally { busy.value = false }
}
function editMember(member?: GroupMember, template?: GroupTemplate) {
  confirmingDelete.value = ''
  const draft = member ? memberDraft(member) : emptyMemberDraft(props.defaultProfileId)
  if (template) Object.assign(draft, { name: template.name, template: template.key, color: template.color, focus: template.focus })
  editingMember.value = { id: member?.id ?? '', draft }
  void nextTick(() => dialog.value?.querySelector<HTMLInputElement>('#group-member-name')?.focus())
}
async function reload() {
  const value = await api<GroupSnapshot>(`/groups/${props.snapshot!.group.id}`)
  members.value = value.members
  form.value.revision = value.group.revision
  emit('saved', value)
}
async function saveMember() {
  if (!editingMember.value || !props.snapshot) return
  busy.value = true; error.value = ''
  const id = props.snapshot.group.id
  try {
    const { id: memberId, draft } = editingMember.value
    await api<GroupMember>(memberId ? `/groups/${id}/members/${memberId}` : `/groups/${id}/members`, draft)
    editingMember.value = null
    await reload()
  } catch (cause) { error.value = message(cause, '保存成员失败') }
  finally { busy.value = false }
}
async function toggleMember(member: GroupMember) {
  busy.value = true; error.value = ''
  try {
    await api<GroupMember>(`/groups/${member.groupId}/members/${member.id}`, { ...memberDraft(member), enabled: !member.enabled })
    await reload()
  } catch (cause) { error.value = message(cause, '更新成员失败') }
  finally { busy.value = false }
}
async function removeMember(member: GroupMember) {
  busy.value = true; error.value = ''
  try {
    await api(`/groups/${member.groupId}/members/${member.id}/delete`, {})
    confirmingDelete.value = ''
    await reload()
  } catch (cause) { error.value = message(cause, '移除成员失败') }
  finally { busy.value = false }
}
async function removeGroup() {
  if (!props.snapshot) return
  busy.value = true; error.value = ''
  try {
    await api(`/groups/${props.snapshot.group.id}/delete`, {})
    emit('deleted', props.snapshot.group.id)
    close(true)
  } catch (cause) { error.value = message(cause, '删除群聊失败') }
  finally { busy.value = false }
}
function close(force = false) {
  if (busy.value && !force) return
  dialog.value?.close()
  emit('close')
}
onMounted(() => {
  dialog.value?.showModal()
  dialog.value?.querySelector<HTMLInputElement>('input')?.focus({ preventScroll: true })
})
</script>

<template>
  <dialog ref="dialog" class="model-editor-dialog automation-editor-dialog group-settings-dialog" aria-labelledby="group-settings-title" @keydown.esc.stop @cancel.prevent="close()">
    <div class="model-editor-panel automation-editor">
      <header class="model-editor-heading"><h2 id="group-settings-title">{{ editing ? '群聊设置' : '新建群聊' }}</h2><button type="button" class="icon-button" :disabled="busy" aria-label="关闭群聊设置" @click="close()"><UiIcon name="close" :size="20" /></button></header>
      <div class="model-editor-content">
        <p v-if="error" role="alert" class="dialog-error">{{ error }}</p>
        <form v-if="!editingMember" id="group-settings-form" @submit.prevent="saveGroup">
          <fieldset :disabled="busy">
            <label for="group-name">群聊名称</label><input id="group-name" v-model="form.name" required maxlength="100" placeholder="例如 登录改版小组" />
            <label for="group-workspace">工作区</label>
            <select id="group-workspace" v-model="form.workspaceId" :disabled="groupBusy" aria-describedby="group-workspace-note"><option value="">不绑定（只讨论）</option><option v-if="form.workspaceId && !workspaces.some(item => item.id === form.workspaceId)" :value="form.workspaceId">工作区已不存在，请重新选择</option><option v-for="item in workspaces" :key="item.id" :value="item.id">{{ item.name }}</option></select>
            <p id="group-workspace-note" class="field-note">{{ groupBusy ? '停止协作后才能切换工作区' : '绑定后成员可读写项目文件并执行命令' }}</p>
            <label for="group-mode">协作模式</label>
            <select id="group-mode" v-model="form.mode" aria-describedby="group-mode-note"><option v-for="option in modeOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
            <p id="group-mode-note" class="field-note">{{ modeOptions.find(option => option.value === form.mode)?.description }}{{ groupBusy ? ' 修改后从下一条消息开始生效。' : '' }}</p>
            <div class="automation-grid">
              <div><label for="group-host">主持人模型</label><select id="group-host" v-model="form.hostProfileId"><option v-if="!profiles.some(item => item.id === form.hostProfileId)" :value="form.hostProfileId">模型已不存在，请重新选择</option><option v-for="item in profiles" :key="item.id" :value="item.id">{{ item.displayName }}</option></select></div>
              <div><label for="group-host-effort">主持人思考强度</label><select id="group-host-effort" v-model="form.hostReasoningEffort"><option :value="null">模型默认</option><option v-for="option in reasoningOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
              <div><label for="group-turns">每轮发言上限</label><input id="group-turns" v-model.number="form.maxTurns" type="number" min="1" max="50" required /></div>
            </div>
            <template v-if="!editing">
              <label id="group-templates-label">默认成员</label>
              <div class="group-template-grid" role="group" aria-labelledby="group-templates-label">
                <label v-for="template in templates" :key="template.key" class="group-template" :title="template.focus"><input type="checkbox" :checked="form.templates.includes(template.key)" @change="toggleTemplate(template.key)" /><span class="group-avatar" :style="{ background: template.color }" aria-hidden="true">{{ avatarText(template.name) }}</span><span>{{ template.name }}</span></label>
              </div>
            </template>
          </fieldset>
        </form>
        <section v-if="editing && !editingMember" class="group-members" aria-labelledby="group-members-title">
          <h3 id="group-members-title">成员 <span>{{ members.length }}</span></h3>
          <ul>
            <li v-for="member in members" :key="member.id" :class="{ disabled: !member.enabled }">
              <span class="group-avatar" :style="{ background: member.color }" aria-hidden="true">{{ avatarText(member.name) }}</span>
              <span class="group-member-copy"><strong>{{ member.name }}<small v-if="!member.enabled">已停用</small></strong><small>{{ profileName(member.modelProfileId) }} · {{ member.reasoningEffort === null ? '模型默认强度' : effortLabel(member.reasoningEffort) }}</small></span>
              <span class="group-member-actions">
                <button type="button" :disabled="busy" @click="toggleMember(member)">{{ member.enabled ? '停用' : '启用' }}</button>
                <button type="button" class="icon-button" :disabled="busy" :aria-label="`编辑成员 ${member.name}`" title="编辑" @click="editMember(member)"><UiIcon name="edit" :size="14" /></button>
                <button type="button" class="icon-button" :disabled="busy" :aria-label="`移除成员 ${member.name}`" title="移除" @click="confirmingDelete = member.id"><UiIcon name="trash" :size="14" /></button>
              </span>
              <p v-if="confirmingDelete === member.id" role="alert" class="dialog-error inline-confirm"><span>移除「{{ member.name }}」？其历史发言会保留。</span><button type="button" :disabled="busy" @click="confirmingDelete = ''">取消</button><button type="button" class="primary danger" :disabled="busy" @click="removeMember(member)">确认移除</button></p>
            </li>
          </ul>
          <div class="group-add-members">
            <button v-for="template in availableTemplates" :key="template.key" type="button" :disabled="busy" :title="template.focus" @click="editMember(undefined, template)"><UiIcon name="plus" :size="13" />{{ template.name }}</button>
            <button type="button" :disabled="busy" @click="editMember()"><UiIcon name="plus" :size="13" />自定义成员</button>
          </div>
          <p v-if="confirmingDelete === 'group'" role="alert" class="dialog-error inline-confirm"><span>删除群聊「{{ form.name }}」及全部聊天记录？</span><button type="button" :disabled="busy" @click="confirmingDelete = ''">取消</button><button type="button" class="primary danger" :disabled="busy" @click="removeGroup">确认删除</button></p>
          <button v-else type="button" class="group-delete" :disabled="busy || groupBusy" :title="groupBusy ? '协作进行中，停止后才能删除' : undefined" @click="confirmingDelete = 'group'"><UiIcon name="trash" :size="14" />删除群聊</button>
        </section>
        <form v-if="editingMember" id="group-member-form" @submit.prevent="saveMember">
          <fieldset :disabled="busy">
            <div class="automation-grid">
              <div><label for="group-member-name">昵称</label><input id="group-member-name" v-model="editingMember.draft.name" required maxlength="20" pattern="[^\s@]+" title="不含空格和 @" /></div>
              <div><label for="group-member-color">颜色</label><input id="group-member-color" v-model="editingMember.draft.color" type="color" /></div>
            </div>
            <label for="group-member-focus">职责与工作侧重点</label><textarea id="group-member-focus" v-model="editingMember.draft.focus" required maxlength="2000" rows="5" placeholder="这个成员负责什么、怎么工作、什么不做"></textarea>
            <div class="automation-grid">
              <div><label for="group-member-model">模型</label><select id="group-member-model" v-model="editingMember.draft.modelProfileId"><option v-if="!profiles.some(item => item.id === editingMember!.draft.modelProfileId)" :value="editingMember.draft.modelProfileId">模型已不存在，请重新选择</option><option v-for="item in profiles" :key="item.id" :value="item.id">{{ item.displayName }}</option></select></div>
              <div><label for="group-member-effort">思考强度</label><select id="group-member-effort" v-model="editingMember.draft.reasoningEffort"><option :value="null">模型默认</option><option v-for="option in reasoningOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
            </div>
            <label for="group-member-approval">审批模式</label><select id="group-member-approval" v-model="editingMember.draft.approvalMode" aria-describedby="group-member-approval-note"><option v-for="option in approvalModeOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
            <p id="group-member-approval-note" class="field-note">{{ approvalModeOptions.find(option => option.value === editingMember!.draft.approvalMode)?.description }}</p>
            <label class="check-label"><input v-model="editingMember.draft.enabled" type="checkbox" /> 参与群聊</label>
          </fieldset>
        </form>
      </div>
      <footer class="model-editor-footer">
        <template v-if="editingMember"><span>{{ editingMember.id ? '编辑成员' : '添加成员' }}</span><button type="button" :disabled="busy" @click="editingMember = null">返回</button><button type="submit" form="group-member-form" class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存成员' }}</button></template>
        <template v-else><span>{{ editing ? '修改设置后点击保存' : '创建后即可在群里提问' }}</span><button type="button" :disabled="busy" @click="close()">{{ editing ? '关闭' : '取消' }}</button><button type="submit" form="group-settings-form" class="primary" :disabled="busy">{{ busy ? '保存中…' : editing ? '保存设置' : '创建群聊' }}</button></template>
      </footer>
    </div>
  </dialog>
</template>

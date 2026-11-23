<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api, type ExternalAgent } from './api'
import SettingsLoading from './SettingsLoading.vue'
import UiIcon from './UiIcon.vue'

const emit = defineEmits<{ busy: [value: boolean] }>()
const agents = ref<ExternalAgent[]>([]), loaded = ref(false)
const toggling = ref(new Set<string>())
const busyAction = ref<'' | 'probe' | 'save' | 'remove'>('')
const form = ref<ExternalAgent>(), args = ref(''), modal = ref<HTMLDialogElement>()
const error = ref(''), notice = ref(''), pageError = ref(''), removing = ref(false)
let trigger: HTMLElement | null = null
let pressedOutside = false
const presets = {
  codex: { alias: 'codex', name: 'Codex', executable: 'codex-acp', summary: '默认 read-only 模式，勾选写入后切换为 workspace-write' },
  claude: { alias: 'claude', name: 'Claude Code', executable: 'claude-agent-acp', summary: '默认 plan 模式，勾选写入后切换为 default' },
  custom: { alias: '', name: '', executable: '', summary: '直接启动自定义本地 ACP v1 stdio 程序' },
} as const
const presetLabels: Record<ExternalAgent['preset'], string> = { codex: 'Codex', claude: 'Claude Code', custom: '自定义 ACP' }
const locked = computed(() => !!busyAction.value || toggling.value.size > 0)
watch(locked, value => emit('busy', value))

function modeLabel(agent: ExternalAgent) {
  if (agent.preset === 'codex') return agent.workspaceWrite ? 'workspace-write（允许写入）' : 'read-only（只读）'
  if (agent.preset === 'claude') return agent.workspaceWrite ? 'default（允许写入）' : 'plan（计划模式）'
  return '自定义权限'
}

/** Reload settings without starting any executable. */
async function refresh() {
  pageError.value = ''
  try { agents.value = await api('/external-agents'); loaded.value = true }
  catch (cause) { pageError.value = message(cause) }
}

/** Apply a launch preset without enabling or installing it. */
function choosePreset(preset: ExternalAgent['preset']) {
  const { alias, name, executable } = presets[preset]
  if (form.value) form.value = { ...form.value, preset, alias, name, executable, arguments: [], workspaceWrite: false }
  args.value = ''; notice.value = ''; error.value = ''
}

/** Open the native settings dialog and retain its originating control. */
async function edit(agent?: ExternalAgent, preset: ExternalAgent['preset'] = 'codex') {
  if (locked.value) return
  trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const defaults = presets[preset]
  form.value = agent
    ? { ...agent, arguments: [...agent.arguments] }
    : { id: '', alias: defaults.alias, name: defaults.name, preset, executable: defaults.executable, arguments: [], workspaceWrite: false, enabled: false, revision: 0 }
  args.value = form.value.arguments.join('\n')
  error.value = ''; notice.value = ''; removing.value = false
  await nextTick()
  modal.value?.showModal()
}

/** Leave existing enabled profiles unchanged when editing is cancelled. */
function close() {
  if (busyAction.value) return
  modal.value?.close(); form.value = undefined; removing.value = false
  void nextTick(() => trigger?.isConnected ? trigger.focus({ preventScroll: true }) : undefined)
}

function closed() {
  if (!form.value) return
  if (busyAction.value) { setTimeout(() => { if (form.value && !modal.value?.open) modal.value?.showModal() }); return }
  close()
}

function onBackdrop(event: MouseEvent) {
  const bounds = modal.value?.getBoundingClientRect()
  if (event.target !== modal.value || !bounds) return false
  return event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom
}
function pressStart(event: MouseEvent) { pressedOutside = onBackdrop(event) }
function outsideModal(event: MouseEvent) {
  if (pressedOutside && onBackdrop(event)) close()
  pressedOutside = false
}

/** Toggle enabled status directly from the list card using optimistic revision checks. */
async function toggle(agent: ExternalAgent) {
  if (locked.value) return
  pageError.value = ''
  const next = new Set(toggling.value); next.add(agent.id); toggling.value = next
  try {
    const saved = await api<ExternalAgent>('/external-agents', { ...agent, enabled: !agent.enabled })
    agents.value = agents.value.map(item => item.id === saved.id ? saved : item)
  } catch (cause) {
    pageError.value = message(cause)
    void refresh()
  } finally {
    const done = new Set(toggling.value); done.delete(agent.id); toggling.value = done
  }
}

/** Test only the negotiated connection; authentication can be deferred until a task. */
async function probe() {
  if (!form.value || busyAction.value) return
  busyAction.value = 'probe'; error.value = ''; notice.value = ''
  try {
    const result = await api<{ name: string; version: string; resumable: boolean }>('/external-agents/test', payload())
    const meta = [result.name, result.version].filter(Boolean).join(' ')
    notice.value = `ACP v1 握手成功${meta ? `（${meta}）` : ''}${result.resumable ? '，支持会话续接' : ''}；未验证账户认证。`
  } catch (cause) { error.value = message(cause) }
  finally { busyAction.value = '' }
}

/** Persist one revision and refresh the rendered list. */
async function save() {
  if (!form.value || busyAction.value) return
  busyAction.value = 'save'; error.value = ''
  try { await api('/external-agents', payload()); await refresh() }
  catch (cause) { error.value = message(cause); return }
  finally { busyAction.value = '' }
  close()
}

/** Remove the profile after explicit confirmation. */
async function remove() {
  const value = form.value
  if (!value?.id || busyAction.value) return
  busyAction.value = 'remove'; error.value = ''
  try { await api(`/external-agents/${value.id}/remove`, { revision: value.revision }); await refresh() }
  catch (cause) { error.value = message(cause); return }
  finally { busyAction.value = '' }
  close()
}

function payload() { return { ...form.value!, arguments: args.value.split(/\r?\n/).filter(Boolean) } }
function message(cause: unknown) { return cause instanceof Error ? cause.message : '操作失败' }
onMounted(refresh)
</script>

<template>
  <div class="extension-discovery agent-settings" aria-labelledby="external-agent-title">
    <div class="extension-hero agent-hero">
      <div>
        <h1 id="external-agent-title">外部智能体 <span>External Agents</span></h1>
        <p>配置可信的本地 ACP v1 stdio 适配器，在对话开头输入 <code>/别名 任务内容</code> 直接委派任务。</p>
      </div>
      <div class="agent-hero-actions">
        <button class="icon-button" type="button" aria-label="刷新外部智能体" title="刷新外部智能体" :disabled="locked" @click="refresh"><UiIcon name="refresh" :size="17" /></button>
        <button class="primary" type="button" :disabled="locked" @click="edit()"><UiIcon name="plus" :size="15" /> 添加智能体</button>
      </div>
    </div>

    <p v-if="pageError" role="alert" class="extension-feedback">{{ pageError }} <button type="button" :disabled="locked" @click="refresh">重试</button></p>
    <SettingsLoading v-if="!loaded && !pageError" label="正在读取外部智能体" />

    <template v-else-if="loaded">
      <div v-if="!agents.length" class="extension-empty agent-empty">
        <UiIcon name="puzzle" :size="32" />
        <h3>还没有配置外部智能体</h3>
        <p>先按上游说明安装并认证对应适配器，再添加预设或自定义 ACP v1 程序。</p>
        <div class="agent-quick-adds">
          <button class="primary" type="button" :disabled="locked" @click="edit(undefined, 'codex')">添加 Codex</button>
          <button type="button" :disabled="locked" @click="edit(undefined, 'claude')">添加 Claude Code</button>
          <button type="button" :disabled="locked" @click="edit(undefined, 'custom')">自定义 ACP</button>
        </div>
      </div>

      <section v-else class="extension-list" aria-label="已配置的外部智能体">
        <h2>已配置智能体 <small>{{ agents.length }}</small></h2>
        <div class="extension-grid agent-grid">
          <article v-for="agent in agents" :key="agent.id" class="extension-row" :class="{ 'extension-disabled': !agent.enabled }">
            <button class="extension-row-main" type="button" :disabled="locked" @click="edit(agent)">
              <span class="extension-item-icon"><UiIcon :name="agent.preset === 'custom' ? 'terminal' : 'puzzle'" :size="20" /></span>
              <span class="extension-item-copy">
                <strong><span class="extension-item-title">{{ agent.name }}</span><span class="extension-bundled">/{{ agent.alias }}</span></strong>
                <span class="extension-item-description agent-exec" :title="agent.executable">{{ agent.executable }}</span>
                <span class="extension-item-state" :class="{ enabled: agent.enabled }">
                  <span>{{ agent.enabled ? '已启用' : '已停用' }}</span>
                  <span>· {{ presetLabels[agent.preset] }}</span>
                  <span>· {{ modeLabel(agent) }}</span>
                </span>
              </span>
            </button>
            <div class="extension-row-controls">
              <div class="extension-status">
                <button class="extension-switch" type="button" role="switch" :aria-checked="agent.enabled" :aria-busy="toggling.has(agent.id)" :aria-label="`${agent.enabled ? '停用' : '启用'} ${agent.name}`" :disabled="locked" @click="toggle(agent)"><span></span></button>
              </div>
              <button class="icon-button" type="button" :aria-label="`编辑 ${agent.name}`" :title="`编辑 ${agent.name}`" :disabled="locked" @click="edit(agent)"><UiIcon name="edit" :size="16" /></button>
            </div>
          </article>
        </div>
      </section>
    </template>
  </div>

  <Teleport to="body">
    <dialog v-if="form" ref="modal" class="extension-modal" aria-labelledby="external-agent-form-title" @keydown.esc.stop @cancel.prevent="close" @close="closed" @mousedown="pressStart" @click="outsideModal">
      <header class="extension-modal-heading">
        <div>
          <span class="extension-modal-eyebrow">ACP v1 · stdio</span>
          <h2 id="external-agent-form-title">{{ form.id ? '编辑外部智能体' : '添加外部智能体' }}</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" :disabled="!!busyAction" @click="close"><UiIcon name="close" :size="18" /></button>
      </header>
      <form id="external-agent-form" class="extension-modal-body extension-add-form" @submit.prevent="save">
        <fieldset :disabled="!!busyAction">
          <label>类型
            <select :value="form.preset" :disabled="!!busyAction || !!form.id" @change="choosePreset(($event.target as HTMLSelectElement).value as ExternalAgent['preset'])">
              <option value="codex">Codex</option>
              <option value="claude">Claude Code</option>
              <option value="custom">自定义 ACP</option>
            </select>
          </label>
          <div class="extension-form-columns">
            <label>名称<input v-model="form.name" required maxlength="80" placeholder="例如 Codex"></label>
            <label>命令别名<input v-model="form.alias" required maxlength="32" pattern="[a-z0-9][a-z0-9-]*" placeholder="例如 codex" spellcheck="false"></label>
          </div>
          <label>可执行程序<input v-model="form.executable" required maxlength="2048" placeholder="例如 codex-acp 或程序绝对路径" spellcheck="false"></label>
          <label>启动参数（每行一项）<textarea v-model="args" rows="2" placeholder="可选，每行填写一个参数" spellcheck="false"></textarea></label>
          <label v-if="form.preset !== 'custom'" class="extension-enable-option">
            <input v-model="form.workspaceWrite" type="checkbox">
            <span>允许请求工作区写入<small class="agent-option-hint">（{{ presets[form.preset].summary }}）</small></span>
          </label>
          <label class="extension-enable-option">
            <input v-model="form.enabled" type="checkbox">
            <span>启用（启用后可在对话中通过 <code>/{{ form.alias || '别名' }}</code> 调用）</span>
          </label>
          <p class="extension-callout agent-warning">仅配置受信任的本地程序。外部程序以当前系统账户运行，其直接文件操作不受 Java Harness 的审批和撤销规则约束。</p>
          <p v-if="error" class="extension-feedback" role="alert">{{ error }}</p>
          <p v-else-if="notice" class="agent-notice" role="status"><UiIcon name="check" :size="15" /><span>{{ notice }}</span></p>
          <div v-if="removing" class="dialog-error inline-confirm" role="status">
            <span>确认移除「{{ form.name || form.alias }}」？移除配置不会删除历史运行记录。</span>
            <button type="button" :disabled="!!busyAction" @click="removing = false">取消</button>
            <button type="button" class="primary danger" :disabled="!!busyAction" @click="remove">{{ busyAction === 'remove' ? '移除中…' : '确认移除' }}</button>
          </div>
        </fieldset>
      </form>
      <footer class="extension-modal-footer agent-footer">
        <button v-if="form.id && !removing" type="button" class="agent-remove-button" :disabled="!!busyAction" @click="removing = true">移除</button>
        <span v-else class="agent-footer-spacer"></span>
        <button type="button" :disabled="!!busyAction" @click="close">取消</button>
        <button type="button" :disabled="!!busyAction || !form.executable.trim()" @click="probe">{{ busyAction === 'probe' ? '测试中…' : '测试连接' }}</button>
        <button class="primary" form="external-agent-form" type="submit" :disabled="!!busyAction">{{ busyAction === 'save' ? '保存中…' : '保存' }}</button>
      </footer>
    </dialog>
  </Teleport>
</template>

<style scoped>
.agent-hero { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.agent-hero code { padding: 1px 5px; border-radius: var(--radius-sm); background: var(--subtle); font: var(--font-caption)/1.5 ui-monospace, 'Cascadia Code', Consolas, monospace; color: var(--text); }
.agent-hero-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.agent-hero-actions .primary { display: inline-flex; align-items: center; gap: 6px; }
.agent-empty .agent-quick-adds { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
.agent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.agent-exec { font-family: ui-monospace, 'Cascadia Code', Consolas, monospace; }
.agent-option-hint { color: var(--muted); }
.agent-warning { margin: 0; color: var(--muted); }
.agent-notice { display: flex; align-items: flex-start; gap: 8px; margin: 0; padding: 10px 12px; border: 0.5px solid var(--border); border-radius: var(--radius-md); background: var(--subtle); color: var(--success); font-size: var(--font-caption); line-height: 1.7; overflow-wrap: anywhere; }
.agent-notice > .ui-icon { margin-top: 3px; }
.agent-footer { flex-wrap: wrap; }
.agent-remove-button { margin-right: auto; color: var(--danger); }
.agent-footer-spacer { margin-right: auto; }
@media (max-width: 800px) {
  .agent-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>

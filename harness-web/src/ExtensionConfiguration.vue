<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from './api'

interface Schema { type: string; title?: string; description?: string; enum?: unknown[]; properties?: Record<string, Schema>; required?: string[]; additionalProperties?: boolean }
interface Binding { name: string; config: unknown; secretKeys: string[]; enabled: boolean; revision: number; launchError: string }
interface Settings { schema: Schema; binding: Binding; servers: Record<string, string>; credentialsUnavailable: boolean }
interface Credential { server: string; location: string; name: string; value: string; stored: boolean }
interface Diagnosis { success: boolean; message: string; tools: string[] }
const props = defineProps<{ installationId: string; editable: boolean }>()
const emit = defineEmits<{ busy: [value: boolean]; saved: []; edit: [] }>()
const settings = ref<Settings>(), values = ref<Record<string, unknown>>({}), credentials = ref<Credential[]>([])
const busy = ref(false), error = ref(''), notice = ref(''), enabled = ref(false), results = ref<Record<string, Diagnosis>>()
const jsonConfig = ref(''), resetCredentials = ref(false)
const fields = computed(() => Object.entries(settings.value?.schema.properties || {}))
const serverNames = computed(() => Object.keys(settings.value?.servers || {}))
const rawConfig = computed(() => !!settings.value && (settings.value.schema.type !== 'object' || !fields.value.length && settings.value.schema.additionalProperties !== false))
const configurable = computed(() => rawConfig.value || fields.value.length > 0 || serverNames.value.length > 0)
function message(cause: unknown) { return cause instanceof Error ? cause.message : '无法读取扩展配置' }
function working(value: boolean) { busy.value = value; emit('busy', value) }
/** Populate redacted credentials; retaining a key never requires displaying its original value. */
function populate(value: Settings) {
  settings.value = value; enabled.value = value.binding.enabled
  values.value = value.schema.type === 'object' ? { ...(value.binding.config as Record<string, unknown>) } : {}
  jsonConfig.value = JSON.stringify(value.binding.config, null, 2); resetCredentials.value = false
  for (const [key, field] of Object.entries(value.schema.properties || {})) {
    if (!(key in values.value) && value.schema.required?.includes(key) && !field.enum) {
      if (field.type === 'boolean') values.value[key] = false
      if (field.type === 'null') values.value[key] = null
    }
    if (['object', 'array'].includes(field.type) && !field.enum && key in values.value) values.value[key] = JSON.stringify(values.value[key], null, 2)
  }
  credentials.value = value.binding.secretKeys.map(key => {
    const [server, location, ...name] = key.split(':')
    return { server: server!, location: location!, name: name.join(':'), value: '', stored: true }
  })
}
async function load() {
  working(true); error.value = ''; results.value = undefined
  try { populate(await api<Settings>(`/extensions/installed/${props.installationId}/configuration`)) }
  catch (cause) { error.value = message(cause) }
  finally { working(false) }
}
function addCredential() {
  const server = serverNames.value[0]!
  credentials.value.push({ server, location: settings.value!.servers[server] === 'stdio' ? 'env' : 'header', name: '', value: '', stored: false })
}
/** Save package settings separately from its immutable content and refresh from the returned revision. */
async function save() {
  if (!settings.value || busy.value) return
  working(true); error.value = ''; notice.value = ''; results.value = undefined
  try {
    if (settings.value.credentialsUnavailable && !resetCredentials.value) throw new Error('请确认替换无法读取的旧凭据，并重新填写所需凭据。')
    const config: Record<string, unknown> = { ...values.value }
    for (const [key, field] of fields.value) {
      if (config[key] === undefined || config[key] === '' && field.type !== 'string') delete config[key]
      else if (['object', 'array'].includes(field.type) && !field.enum) config[key] = JSON.parse(String(config[key]))
    }
    const secrets: Record<string, string> = {}, retainedKeys: string[] = [], keys = new Set<string>()
    for (const credential of credentials.value) {
      const key = `${credential.server}:${credential.location}:${credential.name.trim()}`
      if (!credential.name.trim() || keys.has(key)) throw new Error('请填写不重复的凭据名称。')
      keys.add(key)
      if (credential.stored && !credential.value) retainedKeys.push(key)
      else { if (!credential.value) throw new Error('请填写新凭据的值，或移除该项。'); secrets[key] = credential.value }
    }
    const binding = await api<Binding>(`/extensions/installed/${props.installationId}/configuration`, { config: rawConfig.value ? JSON.parse(jsonConfig.value) : config, secrets, retainedKeys, enabled: enabled.value, revision: settings.value.binding.revision })
    populate({ ...settings.value, binding, credentialsUnavailable: false })
    notice.value = '配置已保存，对新任务生效。'; emit('saved')
  } catch (cause) { error.value = message(cause) }
  finally { working(false) }
}
/** Diagnose only the saved configuration; a successful test is not a permanent health guarantee. */
async function diagnose() {
  if (busy.value) return
  working(true); error.value = ''; notice.value = ''; results.value = undefined
  try { results.value = await api<Record<string, Diagnosis>>(`/extensions/installed/${props.installationId}/diagnose`, {}) }
  catch (cause) { error.value = message(cause) }
  finally { working(false) }
}
onMounted(load)
</script>

<template>
  <section v-if="configurable || error || busy" class="extension-configuration" aria-label="配置与连接">
    <h3>配置与连接</h3>
    <p v-if="error" role="alert" class="extension-feedback">{{ error }} <button :disabled="busy" @click="load">重新读取配置</button></p>
    <p v-if="busy && !settings" role="status">正在读取配置…</p>
    <template v-if="settings && configurable">
      <p v-if="settings.credentialsUnavailable" class="extension-feedback">原凭据无法读取。请重新填写全部凭据或明确删除旧凭据；保留空值无法恢复连接。</p>
      <p v-if="editable" class="field-note">连接参数使用添加时的同一表单维护。<button :disabled="busy" @click="emit('edit')">编辑连接器</button></p>
      <form v-else class="extension-add-form" @submit.prevent="save">
        <fieldset :disabled="busy">
          <label v-if="rawConfig">配置（JSON）<textarea v-model="jsonConfig" rows="5" spellcheck="false" /></label>
          <label v-if="settings.credentialsUnavailable" class="extension-enable-option"><input v-model="resetCredentials" type="checkbox" /> 替换无法读取的旧凭据，仅保存本次填写的凭据</label>
          <label v-for="[key, field] in fields" :key="key">
            <span>{{ field.title || key }} <small v-if="settings.schema.required?.includes(key)">必填</small></span>
            <select v-if="field.enum" v-model="values[key]"><option v-for="(option, index) in field.enum" :key="index" :value="option">{{ option }}</option></select>
            <input v-else-if="field.type === 'boolean'" v-model="values[key]" type="checkbox" />
            <select v-else-if="field.type === 'null'" v-model="values[key]"><option :value="undefined">未设置</option><option :value="null">null</option></select>
            <textarea v-else-if="['object', 'array'].includes(field.type)" :value="String(values[key] ?? '')" rows="4" spellcheck="false" placeholder="JSON" @input="values[key] = ($event.target as HTMLTextAreaElement).value" />
            <input v-else-if="field.type === 'number' || field.type === 'integer'" v-model.number="values[key]" type="number" :step="field.type === 'integer' ? 1 : 'any'" :required="settings.schema.required?.includes(key)" />
            <input v-else v-model="values[key]" :required="settings.schema.required?.includes(key)" />
            <small v-if="field.description" class="field-note">{{ field.description }}</small>
          </label>
          <template v-if="serverNames.length">
            <h4>连接凭据</h4><p class="field-note">密钥加密保存。已保存的凭据留空保留，移除后保存即删除。普通配置项请勿填写密钥。</p>
            <div v-for="(credential, index) in credentials" :key="index" class="extension-credential">
              <select v-model="credential.server" aria-label="MCP 服务" :disabled="credential.stored" @change="credential.location = settings!.servers[credential.server] === 'stdio' ? 'env' : 'header'"><option v-for="server in serverNames" :key="server" :value="server">{{ server }}</option></select>
              <input v-model="credential.name" :readonly="credential.stored" :aria-label="credential.location === 'env' ? '环境变量名' : 'Header 名称'" :placeholder="credential.location === 'env' ? 'API_KEY' : 'Authorization'" />
              <input v-model="credential.value" type="password" autocomplete="new-password" aria-label="凭据值" :placeholder="credential.stored ? '已保存（留空保留）' : '填写凭据值'" />
              <button type="button" @click="credentials.splice(index, 1)">移除</button>
            </div>
            <button type="button" @click="addCredential">添加凭据</button>
          </template>
          <label class="extension-enable-option"><input v-model="enabled" type="checkbox" /> 保存后全局启用</label>
          <div><button type="submit" class="primary">保存配置</button></div>
        </fieldset>
      </form>
      <p v-if="notice" role="status">{{ notice }}</p>
      <div v-if="serverNames.length" class="extension-diagnose">
        <button :disabled="busy" @click="diagnose">{{ busy ? '处理中…' : '测试连接' }}</button>
        <p class="field-note">测试已保存的配置，只连接服务并读取工具目录，不调用工具。结果仅代表本次检查。</p>
        <div v-for="(result, server) in results" :key="server" :class="{ 'extension-feedback': !result.success }" role="status"><strong>{{ server }} · {{ result.success ? '连接成功' : '连接失败' }}</strong><p>{{ result.message }}</p><details v-if="result.success"><summary>可用工具（{{ result.tools.length }}）</summary><ul><li v-for="tool in result.tools" :key="tool">{{ tool }}</li></ul></details></div>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import type { BotCard, BotRoleTemplate } from './api'

const props = defineProps<{
  templates: BotRoleTemplate[]
  existingBots: BotCard[]
  initialMainBotName: string
  initialUserName: string
  hasConfiguredModel: boolean
  revisiting: boolean
  busy: boolean
}>()

const emit = defineEmits<{
  complete: [payload: { mainBotName: string; userDisplayName: string; templateKeys: string[]; skip: boolean }]
  recruitTemplates: [templates: BotRoleTemplate[]]
  close: []
  openModels: []
}>()

const step = ref<1 | 2>(1)
const form = reactive({
  mainBotName: props.initialMainBotName || '小满',
  userDisplayName: props.initialUserName || '',
  selectedKeys: ['code-helper', 'release-assistant', 'doc-writer'] as string[],
})

const existingNames = computed(() => new Set(props.existingBots.map(b => b.bot.name.trim())))

watch(
  () => [props.initialMainBotName, props.initialUserName, props.revisiting] as const,
  ([mainName, userName, revisiting]) => {
    if (mainName) form.mainBotName = mainName
    if (userName) form.userDisplayName = userName
    if (revisiting) {
      form.selectedKeys = props.templates
        .filter(t => !existingNames.value.has(t.name))
        .slice(0, 2)
        .map(t => t.key)
    }
  },
  { immediate: true },
)

const features = [
  {
    icon: 'chat',
    title: '聊天秒回，动手走后台',
    desc: '对话区专注沟通与调度；查代码、写文档、跑命令自动派给后台任务，干完主动来汇报。',
  },
  {
    icon: 'folder',
    title: '专属资料库 + 可选项目工作区',
    desc: '每个 Bot 自带资料库（自动落盘免审批）；需要改代码时再按需绑定工作区并控制审批。',
  },
  {
    icon: 'wand',
    title: '做法 SOP · 定时例行 · 小程序',
    desc: '满意的流程一键存成「做法」，也能按天/周自动跑例行，甚至对话生成互动小程序。',
  },
  {
    icon: 'globe',
    title: '工牌成长 & 局域网手机伴侣',
    desc: '对话与任务积累经验升级工牌；需要离开电脑时，可在设置里按需开启局域网手机配对。',
  },
]

function toggleKey(key: string) {
  const idx = form.selectedKeys.indexOf(key)
  if (idx >= 0) form.selectedKeys.splice(idx, 1)
  else form.selectedKeys.push(key)
}

function submitSetup() {
  if (props.revisiting) {
    const picked = props.templates.filter(t => form.selectedKeys.includes(t.key) && !existingNames.value.has(t.name))
    emit('recruitTemplates', picked)
    return
  }
  emit('complete', {
    mainBotName: form.mainBotName.trim() || '小满',
    userDisplayName: form.userDisplayName.trim(),
    templateKeys: [...form.selectedKeys],
    skip: false,
  })
}

function skipAll() {
  if (props.revisiting) {
    emit('close')
  } else {
    emit('complete', { mainBotName: '', userDisplayName: '', templateKeys: [], skip: true })
  }
}
</script>

<template>
  <div class="bot-onboard-scrim" role="dialog" aria-modal="true" aria-labelledby="bot-onboard-title" @click.self="revisiting && emit('close')">
    <div class="bot-onboard-card">
      <header class="bot-onboard-head">
        <div class="bot-onboard-steps" aria-label="引导步骤">
          <button type="button" :class="{ active: step === 1 }" @click="step = 1">1. 玩法速览</button>
          <span class="bot-onboard-sep" aria-hidden="true">/</span>
          <button type="button" :class="{ active: step === 2 }" @click="step = 2">
            {{ revisiting ? '2. 模板招新' : '2. 组建初始团队' }}
          </button>
        </div>
        <button type="button" class="bot-onboard-skip" :disabled="busy" @click="skipAll">
          {{ revisiting ? '关闭' : '跳过，直接进入' }}
        </button>
      </header>

      <div v-if="!hasConfiguredModel" class="bot-onboard-model-warn" role="alert">
        <div>
          <strong>尚未配置可用模型</strong>
          <p>请先在设置中连接至少一个模型，Bot 才能开始对话与执行后台任务。</p>
        </div>
        <button type="button" class="bot-primary-btn" @click="emit('openModels')">前往配置模型</button>
      </div>

      <section v-if="step === 1" class="bot-onboard-body">
        <div class="bot-onboard-hero">
          <BotLookAvatar shape="cube" color="#276649" :size="48" :progress="0.35" show-ring />
          <div>
            <h2 id="bot-onboard-title">欢迎来到桌面 Bot 工作台</h2>
            <p>这里不是普通单线聊天框，而是一支常驻桌面的数字小团队：有总管帮你派活，也有各司其职的专职助手。</p>
          </div>
        </div>

        <div class="bot-onboard-features">
          <article v-for="item in features" :key="item.title" class="bot-onboard-feature">
            <span class="bot-onboard-icon"><UiIcon :name="item.icon" :size="18" /></span>
            <div>
              <strong>{{ item.title }}</strong>
              <p>{{ item.desc }}</p>
            </div>
          </article>
        </div>

        <footer class="bot-onboard-foot">
          <span class="bot-onboard-hint">随时可从左下角「使用指南」重新打开本向导</span>
          <div class="bot-onboard-foot-actions">
            <button type="button" class="bot-ghost-btn" :disabled="busy" @click="skipAll">
              {{ revisiting ? '知道了' : '先用默认团队' }}
            </button>
            <button type="button" class="bot-primary-btn" :disabled="busy" @click="step = 2">
              {{ revisiting ? '去模板库招人 →' : '下一步：组建团队 →' }}
            </button>
          </div>
        </footer>
      </section>

      <section v-else class="bot-onboard-body">
        <div class="bot-onboard-subhead">
          <h2 id="bot-onboard-title">{{ revisiting ? '从预设模板快速招募新 Bot' : '给总管起个名，再挑几位首批助手' }}</h2>
          <p v-if="!revisiting">总管 Bot 负责和你对接并自动派活给其他成员；角色可全选也可留空，日后随时在聊天里招募。</p>
          <p v-else>勾选需要的岗位模板一键入职，已有同名角色会自动置灰跳过。</p>
        </div>

        <div v-if="!revisiting" class="bot-onboarding-inputs">
          <label>
            总管主 Bot 名称
            <input v-model="form.mainBotName" maxlength="30" placeholder="例如：小满 / 管家" />
          </label>
          <label>
            怎么称呼你（可选）
            <input v-model="form.userDisplayName" maxlength="40" placeholder="例如：阿明 / 老板" />
          </label>
        </div>

        <div class="bot-onboard-roles-head">
          <span>推荐专职角色（已选 {{ form.selectedKeys.length }} 个）</span>
          <button
            type="button"
            class="bot-link-btn"
            @click="form.selectedKeys = form.selectedKeys.length ? [] : templates.filter(t => !revisiting || !existingNames.has(t.name)).map(t => t.key)"
          >
            {{ form.selectedKeys.length ? '全部取消' : '全选可用角色' }}
          </button>
        </div>

        <div class="bot-onboarding-roles">
          <button
            v-for="tpl in templates"
            :key="tpl.key"
            type="button"
            class="bot-role-pick"
            :class="{
              selected: form.selectedKeys.includes(tpl.key),
              'is-recruited': revisiting && existingNames.has(tpl.name),
            }"
            :disabled="revisiting && existingNames.has(tpl.name)"
            @click="toggleKey(tpl.key)"
          >
            <BotLookAvatar :shape="tpl.shape" :color="tpl.color" :size="30" />
            <div class="bot-role-pick-copy">
              <strong>
                {{ tpl.name }}
                <em v-if="revisiting && existingNames.has(tpl.name)">已在队中</em>
              </strong>
              <small>{{ tpl.roleSummary }}</small>
            </div>
            <span class="bot-role-check" aria-hidden="true">
              <UiIcon v-if="form.selectedKeys.includes(tpl.key)" name="check" :size="14" />
            </span>
          </button>
        </div>

        <footer class="bot-onboard-foot">
          <button type="button" class="bot-ghost-btn" :disabled="busy" @click="step = 1">← 上一步</button>
          <div class="bot-onboard-foot-actions">
            <button
              type="button"
              class="bot-primary-btn"
              :disabled="busy || (revisiting && !form.selectedKeys.length)"
              @click="submitSetup"
            >
              {{ busy ? '处理中…' : revisiting ? `招募选中角色（${form.selectedKeys.length}）` : '完成入职，开始干活' }}
            </button>
          </div>
        </footer>
      </section>
    </div>
  </div>
</template>

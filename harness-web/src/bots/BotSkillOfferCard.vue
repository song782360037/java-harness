<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import type { BotSkillOffer } from './api'

const props = defineProps<{
  offer: BotSkillOffer
  currentBotId?: string
  readOnly?: boolean
  busy?: boolean
}>()

const emit = defineEmits<{
  answer: [offerId: string, names: string[]]
  viewFiles: [offerId: string, skillName: string]
}>()

const chosen = ref<string[]>(props.offer.skills.map(s => s.name))
const error = ref('')

watch(() => props.offer.offerId, () => {
  chosen.value = props.offer.skills.map(s => s.name)
  error.value = ''
})

const settledNames = computed(() => (props.offer.installed !== null && props.offer.installed !== undefined ? props.offer.installed : null))
const single = computed(() => (props.offer.skills.length === 1 ? props.offer.skills[0] : null))
const isAllChosen = computed(() => chosen.value.length === props.offer.skills.length)
const forOtherBot = computed(() =>
  props.currentBotId && props.offer.targetBotId && props.offer.targetBotId !== props.currentBotId,
)

function toggle(name: string, checked: boolean) {
  chosen.value = checked
    ? [...new Set([...chosen.value, name])]
    : chosen.value.filter(n => n !== name)
}

function toggleAll() {
  chosen.value = isAllChosen.value ? [] : props.offer.skills.map(s => s.name)
}

function submit(names: string[]) {
  error.value = ''
  emit('answer', props.offer.offerId, names)
}

function sourceLabel(kind: string, repo: string): string {
  if (kind === 'GITHUB' && repo) return `GitHub · ${repo}`
  if (kind === 'UPLOAD') return '上传压缩包'
  return '本地目录'
}
</script>

<template>
  <!-- Collapsed one-line state after confirmation or decline -->
  <div v-if="settledNames !== null" class="skill-offer-settled" role="status">
    <UiIcon :name="settledNames.length ? 'check' : 'close'" :size="14" />
    <span v-if="!settledNames.length">
      已跳过安装（{{ offer.skills.map(s => s.name).join('、') }}）
    </span>
    <span v-else>
      已为「{{ offer.targetBotName }}」安装技能：<strong>{{ settledNames.join('、') }}</strong>
    </span>
  </div>

  <!-- Interactive Offer Card -->
  <div v-else class="skill-offer-card">
    <div v-if="forOtherBot" class="skill-offer-for">
      <UiIcon name="skill" :size="13" />
      <span>将安装给 <strong>{{ offer.targetBotName }}</strong></span>
    </div>

    <div class="skill-offer-body">
      <!-- Single Skill Detail -->
      <template v-if="single">
        <div class="skill-offer-head">
          <span class="skill-offer-icon"><UiIcon name="skill" :size="17" /></span>
          <div class="skill-offer-meta">
            <div class="skill-offer-name-row">
              <strong>{{ single.name }}</strong>
              <span v-if="single.scripts.length" class="skill-script-badge">
                含 {{ single.scripts.length }} 个脚本
              </span>
            </div>
            <small>
              {{ sourceLabel(single.sourceKind, single.sourceRepo) }}
              <template v-if="single.version"> · v{{ single.version }}</template>
              · {{ single.fileCount }} 个文件
            </small>
          </div>
        </div>
        <p class="skill-offer-desc">{{ single.description }}</p>
        <ul v-if="single.scripts.length" class="skill-script-list">
          <li v-for="sc in single.scripts.slice(0, 5)" :key="sc">
            <UiIcon name="terminal" :size="11" />
            <span>{{ sc }}</span>
          </li>
          <li v-if="single.scripts.length > 5" class="is-more">
            还有 {{ single.scripts.length - 5 }} 个脚本，点下方「查看文件」审阅
          </li>
        </ul>
        <p v-if="single.replaces" class="skill-replaces-note">将覆盖已安装版本（{{ single.replaces }}）</p>
      </template>

      <!-- Multi-Skill Collection Checklist -->
      <template v-else>
        <div class="skill-offer-head">
          <span class="skill-offer-icon"><UiIcon name="skill" :size="17" /></span>
          <div class="skill-offer-meta">
            <strong>发现 {{ offer.skills.length }} 个技能</strong>
            <small v-if="offer.skills[0]">
              {{ sourceLabel(offer.skills[0].sourceKind, offer.skills[0].sourceRepo) }}
            </small>
          </div>
        </div>
        <ul class="skill-checklist">
          <li v-for="sk in offer.skills" :key="sk.name" class="skill-check-row">
            <label>
              <input
                type="checkbox"
                :checked="chosen.includes(sk.name)"
                :disabled="readOnly || busy"
                @change="toggle(sk.name, ($event.target as HTMLInputElement).checked)"
              />
              <span class="skill-check-copy">
                <span class="skill-offer-name-row">
                  <strong>{{ sk.name }}</strong>
                  <span v-if="sk.scripts.length" class="skill-script-badge">含 {{ sk.scripts.length }} 个脚本</span>
                </span>
                <small>{{ sk.description }}</small>
                <small v-if="sk.replaces" class="is-replace">覆盖已有版本（{{ sk.replaces }}）</small>
              </span>
            </label>
            <button
              v-if="!readOnly"
              type="button"
              class="icon-button"
              :title="`查看 ${sk.name} 的文件`"
              @click="emit('viewFiles', offer.offerId, sk.name)"
            >
              <UiIcon name="file" :size="13" />
            </button>
          </li>
        </ul>
      </template>

      <div v-if="offer.skipped.length" class="skill-skipped-box">
        <strong>已跳过 {{ offer.skipped.length }} 项：</strong>
        <ul>
          <li v-for="sk in offer.skipped" :key="`${sk.folder}:${sk.reason}`">{{ sk.folder }}：{{ sk.reason }}</li>
        </ul>
      </div>

      <p v-if="error" class="dialog-error">{{ error }}</p>

      <div class="skill-offer-actions">
        <button
          v-if="single && !readOnly"
          type="button"
          class="bot-link-btn"
          @click="emit('viewFiles', offer.offerId, single.name)"
        >
          <UiIcon name="file" :size="13" />
          <span>查看文件</span>
        </button>
        <button v-else-if="!single && !readOnly" type="button" class="bot-link-btn" @click="toggleAll">
          {{ isAllChosen ? '全不选' : '全选' }}
        </button>
        <span class="skill-offer-spacer"></span>
        <template v-if="!readOnly">
          <button type="button" :disabled="busy" @click="submit([])">不装</button>
          <button
            type="button"
            class="primary"
            :disabled="busy || !chosen.length"
            @click="submit(chosen)"
          >
            {{ busy ? '安装中…' : single ? '装上' : `装上 (${chosen.length})` }}
          </button>
        </template>
        <small v-else class="skill-readonly-hint">请在电脑端确认安装</small>
      </div>
    </div>
  </div>
</template>

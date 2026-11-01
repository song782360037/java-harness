<script setup lang="ts">
import { computed } from 'vue'
import { reasoningDepthOptions } from './api'
const props = defineProps<{ modelValue: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const levels = reasoningDepthOptions
const index = computed(() => Math.max(0, levels.findIndex(option => option.value === props.modelValue)))
const progress = computed(() => index.value / (levels.length - 1))
const depth = computed(() => levels[index.value]!.short)
const max = computed(() => index.value === levels.length - 1)
// 只改变本次强度选择，沿用已有的请求参数值。
function choose(value: string) { if (!props.disabled && value !== props.modelValue) emit('update:modelValue', value) }
// 松开滑块时也确认选择，兼容旧配置直接点选最左档。
function update(event: Event) { choose(levels[(event.target as HTMLInputElement).valueAsNumber]!.value) }
</script>
<template>
  <section class="reasoning-control">
    <div class="reasoning-range" :class="{ 'is-max': max && !disabled }" :style="{ '--reasoning-progress': progress }">
      <div class="reasoning-track" aria-hidden="true"><span class="reasoning-fill"></span><span class="reasoning-ticks"><i v-for="(level, i) in levels" :key="level.value" :class="{ passed: i <= index }"></i></span></div>
      <input id="reasoning-slider" class="reasoning-input" type="range" min="0" :max="levels.length - 1" step="1" :value="index" :disabled="disabled" :aria-valuetext="depth" @input="update" @pointerup="update" @keydown.enter.prevent />
      <!-- 原生滑块按钮无法过渡位置，用跟随进度的自绘按钮承担视觉。 -->
      <span class="reasoning-thumb" aria-hidden="true"></span>
    </div>
    <div class="reasoning-heading"><label for="reasoning-slider">思考深度</label>
      <Transition name="reasoning-depth" mode="out-in"><span :key="depth" :class="{ 'reasoning-shimmer-text': max }">{{ depth }}</span></Transition>
    </div>
  </section>
</template>
<style scoped>
.reasoning-control { --reasoning-ease: cubic-bezier(.22, 1, .36, 1); padding: 14px 12px 12px; }
.reasoning-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; color: var(--muted); font-size: var(--font-caption); }
.reasoning-heading > span { color: var(--text); font-weight: 550; }
.reasoning-range { position: relative; height: 44px; }
.reasoning-track { position: absolute; inset: 9px 0; overflow: hidden; border-radius: 20px; background: var(--subtle); border: 0.5px solid var(--border); }
.reasoning-fill { position: absolute; inset: 0 auto 0 0; overflow: hidden; width: calc(18px + (100% - 36px) * var(--reasoning-progress)); background: var(--accent-soft); transition: width .28s var(--reasoning-ease); }
.reasoning-fill::after { content: ''; position: absolute; inset: 0; opacity: 0; background: linear-gradient(100deg, transparent 25%, var(--accent) 50%, transparent 75%) 0 0 / 200% 100%; transition: opacity .3s ease; }
.reasoning-ticks { position: absolute; inset: 0 17px; display: flex; align-items: center; justify-content: space-between; }
.reasoning-ticks i { width: 4px; height: 4px; border-radius: 50%; background: var(--muted); opacity: .55; transition: background-color .2s ease, opacity .2s ease; }
.reasoning-ticks i.passed { background: var(--accent); opacity: .7; }
.reasoning-input { position: absolute; inset: 0; width: 100%; height: 44px; margin: 0; padding: 0; appearance: none; -webkit-appearance: none; background: transparent; border: 0; cursor: pointer; }
.reasoning-input:disabled { cursor: default; }
.reasoning-input::-webkit-slider-runnable-track { height: 30px; background: transparent; }
.reasoning-input::-moz-range-track { height: 30px; background: transparent; }
.reasoning-input::-webkit-slider-thumb { appearance: none; -webkit-appearance: none; width: 28px; height: 28px; margin-top: 1px; border: 0; background: transparent; }
.reasoning-input::-moz-range-thumb { width: 28px; height: 28px; border: 0; background: transparent; }
.reasoning-input:focus-visible { outline: none; }
.reasoning-thumb { position: absolute; top: 8px; left: calc((100% - 28px) * var(--reasoning-progress)); box-sizing: border-box; width: 28px; height: 28px; border-radius: 50%; pointer-events: none; background: var(--surface); border: 2px solid var(--accent); box-shadow: 0 1px 3px var(--focus-ring); transition: left .28s var(--reasoning-ease), transform .16s ease; }
.reasoning-input:active:not(:disabled) + .reasoning-thumb { transform: scale(1.08); }
.reasoning-input:disabled + .reasoning-thumb { opacity: .5; }
.reasoning-input:focus-visible + .reasoning-thumb { outline: 2px solid var(--accent); outline-offset: 2px; }
.reasoning-thumb::before { content: ''; position: absolute; inset: 5px; border-radius: 50%; background: var(--accent); opacity: 0; transform: scale(.4); transition: opacity .25s ease, transform .3s var(--reasoning-ease); }
.reasoning-thumb::after { content: ''; position: absolute; inset: -2px; border-radius: 50%; border: 2px solid var(--accent); opacity: 0; }
/* 最高档：填充流光、按钮点亮并持续向外扩散光环。 */
.is-max .reasoning-fill::after { opacity: .3; animation: reasoning-sweep 1.8s linear infinite; }
.is-max .reasoning-thumb { animation: reasoning-pop .42s var(--reasoning-ease); }
.is-max .reasoning-thumb::before { opacity: 1; transform: scale(1); }
.is-max .reasoning-thumb::after { animation: reasoning-ring 1.6s ease-out .2s infinite; }
.reasoning-depth-enter-active, .reasoning-depth-leave-active { transition: opacity .08s ease, transform .08s ease; }
.reasoning-depth-enter-from { opacity: 0; transform: translateY(4px); }
.reasoning-depth-leave-to { opacity: 0; transform: translateY(-4px); }
@keyframes reasoning-sweep { from { background-position: 150% 0; } to { background-position: -50% 0; } }
@keyframes reasoning-pop { 40% { transform: scale(1.22); } }
@keyframes reasoning-ring { from { transform: scale(1); opacity: .55; } to { transform: scale(1.75); opacity: 0; } }
</style>

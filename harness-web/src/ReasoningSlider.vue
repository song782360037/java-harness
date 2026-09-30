<script setup lang="ts">
import { computed } from 'vue'
import { reasoningDepthOptions } from './api'
const props = defineProps<{ modelValue: string; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const levels = reasoningDepthOptions
const index = computed(() => Math.max(0, levels.findIndex(option => option.value === props.modelValue)))
const progress = computed(() => index.value / (levels.length - 1))
const depth = computed(() => levels[index.value]!.label.split(' · ')[0])
// 只改变本次强度选择，沿用已有的请求参数值。
function choose(value: string) { if (!props.disabled && value !== props.modelValue) emit('update:modelValue', value) }
// 松开滑块时也确认选择，兼容旧配置直接点选最左档。
function update(event: Event) { choose(levels[(event.target as HTMLInputElement).valueAsNumber]!.value) }
</script>
<template>
  <section class="reasoning-control">
    <div class="reasoning-range" :style="{ '--reasoning-progress': progress }">
      <div class="reasoning-track" aria-hidden="true"><span class="reasoning-fill"></span><span v-if="modelValue && index === levels.length - 1" class="reasoning-particles"><i v-for="particle in 12" :key="particle" :style="{ '--particle': particle, left: `${particle * 8}%`, top: `${15 + particle % 4 * 20}%` }"></i></span><span class="reasoning-ticks"><i v-for="level in levels" :key="level.value"></i></span></div>
      <input id="reasoning-slider" class="reasoning-input" type="range" min="0" :max="levels.length - 1" step="1" :value="index" :disabled="disabled" :aria-valuetext="depth" @input="update" @pointerup="update" @keydown.enter.prevent />
    </div>
    <div class="reasoning-heading"><label for="reasoning-slider">思考深度</label><span>{{ depth }}</span></div>
  </section>
</template>
<style scoped>
.reasoning-control { padding: 16px 12px 14px; }
.reasoning-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; color: var(--muted); font-size: calc(12rem / 14); }
.reasoning-heading > span { color: var(--text); }
.reasoning-range { position: relative; height: 44px; }
.reasoning-track { position: absolute; inset: 7px 0; overflow: hidden; border-radius: 20px; background: var(--subtle); border: 1px solid var(--border); }
.reasoning-fill { position: absolute; inset: 0 auto 0 0; width: calc(18px + (100% - 36px) * var(--reasoning-progress)); background: linear-gradient(100deg, #3b82f6, #8b5cf6); }
.reasoning-particles { position: absolute; inset: 0; overflow: hidden; pointer-events: none; }
.reasoning-particles i { position: absolute; width: 3px; height: 3px; border-radius: 50%; background: #fff; box-shadow: 0 0 5px #ffffffb3; animation: reasoning-spark 2.4s linear infinite; animation-delay: calc(var(--particle) * -.23s); }
@keyframes reasoning-spark { 0% { opacity: 0; transform: translate(-14px, 6px) scale(.5); } 25%, 65% { opacity: .8; } 100% { opacity: 0; transform: translate(18px, -6px) scale(1.1); } }
@media (prefers-reduced-motion: reduce) { .reasoning-particles i { animation: none; opacity: .6; } }
.reasoning-ticks { position: absolute; inset: 0 17px; display: flex; align-items: center; justify-content: space-between; }
.reasoning-ticks i { width: 5px; height: 5px; border-radius: 50%; background: var(--muted); opacity: .4; }
.reasoning-input { position: absolute; inset: 0; width: 100%; height: 44px; margin: 0; padding: 0; appearance: none; -webkit-appearance: none; background: transparent; border: 0; cursor: pointer; }
.reasoning-input::-webkit-slider-runnable-track { height: 30px; background: transparent; }
.reasoning-input::-moz-range-track { height: 30px; background: transparent; }
.reasoning-input::-webkit-slider-thumb { appearance: none; -webkit-appearance: none; box-sizing: border-box; width: 36px; height: 36px; margin-top: -3px; border-radius: 50%; background: #fff; border: 1px solid #00000014; box-shadow: 0 1px 4px #0000001a; }
.reasoning-input::-moz-range-thumb { box-sizing: border-box; width: 36px; height: 36px; border-radius: 50%; background: #fff; border: 1px solid #00000014; box-shadow: 0 1px 4px #0000001a; }
.reasoning-input:focus-visible { outline: none; }
.reasoning-input:focus-visible::-webkit-slider-thumb { outline: 2px solid var(--accent); outline-offset: 2px; }
.reasoning-input:focus-visible::-moz-range-thumb { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>

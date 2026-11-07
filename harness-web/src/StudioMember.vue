<script setup lang="ts">
import { computed } from 'vue'
import { studioSprite, type StudioPose } from './studioOffice'
const props = defineProps<{ name: string; template: string; active: boolean; pose?: StudioPose }>()
const sprite = computed(() => studioSprite(props.template, props.name, props.pose ?? 'idle'))
</script>

<template>
  <span class="member-illustration" :class="{ 'is-active': active }" aria-hidden="true">
    <span class="member-shadow"></span>
    <span class="member-sprite" :style="{ backgroundPosition: `${sprite.x}% ${sprite.y}%` }"></span>
  </span>
</template>

<style scoped>
.member-illustration { position: relative; display: block; width: var(--sprite-size, 112px); height: var(--sprite-size, 112px); }
.member-shadow { position: absolute; bottom: 6%; left: 36%; width: 30%; height: 7%; border-radius: 50%; background: #45332730; }
.member-sprite { position: absolute; inset: 0; background-image: url('/studio/pixel-members.png'); background-size: 400% 400%; image-rendering: pixelated; }
.is-active .member-sprite { animation: member-working 1.5s steps(2, end) infinite; }
@keyframes member-working { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-2px); } }
@media (prefers-reduced-motion: reduce) { .is-active .member-sprite { animation: none; } }
</style>

<script setup lang="ts">
/** 设置分区首次读取时的统一占位，避免空白或把未加载显示成 0 条。 */
defineProps<{ label: string }>()
</script>
<template>
  <div class="usage-loading" role="status">
    <p><span class="loading-indicator" aria-hidden="true" />{{ label }}</p>
    <div class="usage-skeleton" aria-hidden="true" />
    <div class="usage-skeleton tall" aria-hidden="true" />
  </div>
</template>

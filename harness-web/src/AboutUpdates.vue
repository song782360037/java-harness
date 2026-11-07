<script setup lang="ts">
import { runUpdateAction, updateBusy, updateError, updateState, updateStatus } from './desktopUpdates'
</script>
<template>
  <section class="general-settings" aria-label="关于与更新">
    <div class="preference-row"><div class="preference-copy"><strong>Java Harness</strong><p>当前版本 {{ updateState?.version || '—' }} · Windows x64</p></div><span>正式版</span></div>
    <div class="preference-row">
      <div class="preference-copy"><strong role="status" aria-live="polite">{{ updateStatus }}</strong><p v-if="updateState?.lastChecked">上次检查：{{ new Date(updateState.lastChecked).toLocaleString() }}</p><p>下载不会打断任务，安装前会检查活跃工作并备份本地数据库。</p></div>
      <button v-if="updateState?.restartRequired" type="button" @click="runUpdateAction('restart')">重启当前版本</button>
      <button v-else-if="updateState?.phase === 'ready'" type="button" @click="runUpdateAction('install')">重启更新</button>
      <button v-else type="button" :disabled="!updateState?.supported || updateBusy" @click="runUpdateAction('check')">{{ updateBusy ? '更新中…' : '检查更新' }}</button>
    </div>
    <div v-if="updateState?.phase === 'downloading'" class="update-progress"><progress :value="updateState.percent" max="100" aria-label="更新下载进度"></progress></div>
    <p v-if="updateError || updateState?.error" class="dialog-error" role="alert">{{ updateError || updateState?.error }}</p>
    <details v-if="updateState?.releaseNotes" class="update-notes"><summary>版本 {{ updateState.targetVersion }} 更新说明</summary><pre>{{ updateState.releaseNotes }}</pre></details>
  </section>
</template>

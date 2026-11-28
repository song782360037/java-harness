<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import UiIcon from '../UiIcon.vue'
import BotAvatarPicker from './BotAvatarPicker.vue'
import BotLookAvatar from './BotLookAvatar.vue'
import {
  OUTFIT_PIECES,
  OUTFIT_SLOTS,
  isOutfitUnlocked,
  nextUnlockLevel,
  parseOutfitJson,
  takeOffSlot,
  toggleOutfitPiece,
  wornInSlot,
  type BotCard,
  type BotShape,
  type OutfitId,
  type OutfitSlot,
} from './api'

const props = defineProps<{
  card: BotCard
}>()

const emit = defineEmits<{
  updateLook: [shape: BotShape, color: string]
  updateOutfit: [outfit: OutfitId[]]
  uploadAvatar: [blob: Blob, filename: string]
  deleteAvatar: []
  openChange: [open: boolean]
}>()

const wrapRef = ref<HTMLElement>()
const fileInputRef = ref<HTMLInputElement>()
const open = ref(false)
const tab = ref<'look' | 'upload' | 'outfit'>('look')
const refusedPiece = ref<OutfitId | null>(null)
const shakingId = ref<OutfitId | null>(null)
const uploadError = ref('')
const uploading = ref(false)

// Circular cropper state
const cropImage = ref<HTMLImageElement | null>(null)
const cropPreviewUrl = ref('')
const cropZoom = ref(1)
const cropOffset = ref({ x: 0, y: 0 })
const cropDragging = ref(false)
let cropStart = { px: 0, py: 0, ox: 0, oy: 0 }

const level = computed(() => props.card.experience.level)
const isMain = computed(() => props.card.bot.main)
const currentOutfit = computed<OutfitId[]>(() => parseOutfitJson(props.card.bot.outfitJson))
const nextLv = computed(() => nextUnlockLevel(level.value, isMain.value))

const statusHint = computed(() => {
  if (refusedPiece.value) {
    const p = OUTFIT_PIECES.find(item => item.id === refusedPiece.value)
    if (p) return `「${p.label}」需达到 Lv.${p.from} 解锁（还差 ${Math.max(1, p.from - level.value)} 级）`
  }
  if (nextLv.value === null) return '全部 12 件装扮均已解锁！'
  const items = OUTFIT_PIECES
    .filter(p => p.from === nextLv.value && !isOutfitUnlocked(p.id, level.value, isMain.value))
    .map(p => p.label)
    .join('、')
  return `再升 ${Math.max(1, nextLv.value - level.value)} 级（Lv.${nextLv.value}）解锁：${items}`
})

watch(open, value => emit('openChange', value))
watch(() => props.card.bot.id, () => { closePopover() })

function toggleOpen() {
  if (open.value) {
    closePopover()
    return
  }
  tab.value = props.card.avatarUrl ? 'upload' : 'look'
  resetCrop()
  open.value = true
}

function closePopover() {
  open.value = false
  resetCrop()
}

function resetCrop() {
  if (cropPreviewUrl.value) URL.revokeObjectURL(cropPreviewUrl.value)
  cropPreviewUrl.value = ''
  cropImage.value = null
  cropZoom.value = 1
  cropOffset.value = { x: 0, y: 0 }
  uploadError.value = ''
}

function piecesIn(slot: OutfitSlot) {
  return OUTFIT_PIECES.filter(p => p.slot === slot)
}

function clickPiece(id: OutfitId) {
  const locked = !currentOutfit.value.includes(id) && !isOutfitUnlocked(id, level.value, isMain.value)
  if (locked) {
    refusedPiece.value = id
    shakingId.value = id
    window.setTimeout(() => { if (shakingId.value === id) shakingId.value = null }, 380)
    window.setTimeout(() => { if (refusedPiece.value === id) refusedPiece.value = null }, 3200)
    return
  }
  emit('updateOutfit', toggleOutfitPiece(currentOutfit.value, id))
}

function loadFile(file: File) {
  uploadError.value = ''
  if (!/^image\/(png|jpeg|webp|gif)$/i.test(file.type)) {
    uploadError.value = '仅支持 PNG / JPEG / WebP / GIF 格式图片'
    return
  }
  if (file.size > 4 * 1024 * 1024) {
    uploadError.value = '原图过大，请选择 4 MB 以内的图片'
    return
  }
  const url = URL.createObjectURL(file)
  const img = new Image()
  img.onload = () => {
    resetCrop()
    cropPreviewUrl.value = url
    cropImage.value = img
    cropZoom.value = 1
    cropOffset.value = { x: 0, y: 0 }
  }
  img.onerror = () => {
    URL.revokeObjectURL(url)
    uploadError.value = '无法解析图片，请更换一张试试'
  }
  img.src = url
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) loadFile(file)
  input.value = ''
}

function onDropFile(event: DragEvent) {
  const file = event.dataTransfer?.files?.[0]
  if (file) loadFile(file)
}

function onCropPointerDown(event: PointerEvent) {
  cropDragging.value = true
  cropStart = { px: event.clientX, py: event.clientY, ox: cropOffset.value.x, oy: cropOffset.value.y }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onCropPointerMove(event: PointerEvent) {
  if (!cropDragging.value) return
  cropOffset.value = {
    x: Math.max(-90, Math.min(90, cropStart.ox + (event.clientX - cropStart.px))),
    y: Math.max(-90, Math.min(90, cropStart.oy + (event.clientY - cropStart.py))),
  }
}

function onCropPointerUp(event: PointerEvent) {
  cropDragging.value = false
  try { (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId) } catch { /* ignore */ }
}

async function saveCroppedAvatar() {
  const img = cropImage.value
  if (!img) return
  uploading.value = true
  uploadError.value = ''
  try {
    const size = 256
    const stageSize = 160
    const canvas = document.createElement('canvas')
    canvas.width = size
    canvas.height = size
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('浏览器不支持画布裁切')
    const baseScale = Math.max(stageSize / img.naturalWidth, stageSize / img.naturalHeight)
    const drawW = img.naturalWidth * baseScale * cropZoom.value * (size / stageSize)
    const drawH = img.naturalHeight * baseScale * cropZoom.value * (size / stageSize)
    const dx = (size - drawW) / 2 + cropOffset.value.x * (size / stageSize)
    const dy = (size - drawH) / 2 + cropOffset.value.y * (size / stageSize)
    ctx.drawImage(img, dx, dy, drawW, drawH)
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(b => (b ? resolve(b) : reject(new Error('导出头像失败'))), 'image/png', 0.92)
    })
    emit('uploadAvatar', blob, 'avatar.png')
    closePopover()
  } catch (cause) {
    uploadError.value = cause instanceof Error ? cause.message : '裁切上传失败'
  } finally {
    uploading.value = false
  }
}

function onGlobalPointerDown(event: PointerEvent) {
  if (!open.value) return
  if (wrapRef.value && !wrapRef.value.contains(event.target as Node)) closePopover()
}

function onGlobalPaste(event: ClipboardEvent) {
  if (!open.value) return
  const file = Array.from(event.clipboardData?.files || []).find(f => f.type.startsWith('image/'))
  if (file) {
    event.preventDefault()
    tab.value = 'upload'
    loadFile(file)
  }
}

function onGlobalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) {
    event.stopPropagation()
    closePopover()
  }
}

onMounted(() => {
  window.addEventListener('pointerdown', onGlobalPointerDown)
  window.addEventListener('paste', onGlobalPaste)
  window.addEventListener('keydown', onGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('pointerdown', onGlobalPointerDown)
  window.removeEventListener('paste', onGlobalPaste)
  window.removeEventListener('keydown', onGlobalKeydown)
  resetCrop()
})
</script>

<template>
  <div ref="wrapRef" class="bot-avatar-editor">
    <button
      type="button"
      class="bot-avatar-trigger"
      title="点击自定义形象、上传头像或搭配装扮"
      :aria-expanded="open"
      @click="toggleOpen"
    >
      <BotLookAvatar
        :shape="card.bot.shape"
        :color="card.bot.color"
        :avatar-url="card.avatarUrl"
        :outfit="currentOutfit"
        :size="56"
        :status="card.status"
        :progress="card.experience.progress"
        show-ring
      />
      <span class="bot-avatar-edit-veil" aria-hidden="true">
        <UiIcon name="edit" :size="15" />
      </span>
    </button>

    <div v-if="open" class="bot-avatar-popover" role="dialog" aria-label="形象与装扮编辑器">
      <div class="bot-avatar-tabs" role="tablist">
        <button type="button" role="tab" :aria-selected="tab === 'look'" @click="tab = 'look'">外形</button>
        <button type="button" role="tab" :aria-selected="tab === 'upload'" @click="tab = 'upload'">上传</button>
        <button type="button" role="tab" :aria-selected="tab === 'outfit'" @click="tab = 'outfit'">装扮</button>
      </div>

      <!-- Look tab -->
      <div v-if="tab === 'look'" class="bot-avatar-tab-body">
        <BotAvatarPicker
          :shape="card.bot.shape"
          :color="card.bot.color"
          compact
          @update:shape="emit('updateLook', $event, card.bot.color)"
          @update:color="emit('updateLook', card.bot.shape, $event)"
        />
      </div>

      <!-- Upload & Circular Cropper tab -->
      <div v-else-if="tab === 'upload'" class="bot-avatar-tab-body">
        <input ref="fileInputRef" type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden @change="onFileChange" />
        <p v-if="uploadError" class="bot-upload-error">{{ uploadError }}</p>

        <template v-if="cropPreviewUrl">
          <div
            class="bot-crop-stage"
            @pointerdown="onCropPointerDown"
            @pointermove="onCropPointerMove"
            @pointerup="onCropPointerUp"
            @pointercancel="onCropPointerUp"
          >
            <img
              :src="cropPreviewUrl"
              alt="待裁切头像"
              class="bot-crop-img"
              :style="{ transform: `translate(${cropOffset.x}px, ${cropOffset.y}px) scale(${cropZoom})` }"
              draggable="false"
            />
            <span class="bot-crop-ring" aria-hidden="true"></span>
          </div>
          <label class="bot-crop-zoom">
            <span>缩放</span>
            <input v-model.number="cropZoom" type="range" min="1" max="2.6" step="0.05" />
          </label>
          <div class="bot-form-actions">
            <button type="button" @click="resetCrop">重选</button>
            <button type="button" class="is-primary" :disabled="uploading" @click="saveCroppedAvatar">
              {{ uploading ? '上传中…' : '保存头像' }}
            </button>
          </div>
        </template>

        <template v-else>
          <div
            class="bot-upload-dropzone"
            @click="fileInputRef?.click()"
            @dragover.prevent
            @drop.prevent="onDropFile"
          >
            <UiIcon name="plus" :size="18" />
            <strong>点击选择、拖入或粘贴图片</strong>
            <small>支持 PNG / JPG / WebP / GIF，自动圆形裁切</small>
          </div>
          <button
            v-if="card.avatarUrl"
            type="button"
            class="bot-ghost-btn is-danger"
            @click="emit('deleteAvatar'); closePopover()"
          >
            移除图片，恢复矢量形象
          </button>
        </template>
      </div>

      <!-- Outfit tab -->
      <div v-else class="bot-avatar-tab-body bot-outfit-panel">
        <p v-if="card.avatarUrl" class="bot-outfit-note">提示：当前使用上传头像，装扮会在切换回矢量形象时呈现。</p>
        <section v-for="slot in OUTFIT_SLOTS" :key="slot.id" class="bot-outfit-slot">
          <div class="bot-outfit-slot-head">
            <span>{{ slot.label }}</span>
            <button
              v-if="wornInSlot(currentOutfit, slot.id)"
              type="button"
              class="bot-outfit-takeoff"
              @click="emit('updateOutfit', takeOffSlot(currentOutfit, slot.id))"
            >
              摘下
            </button>
          </div>
          <div class="bot-outfit-grid">
            <button
              v-for="piece in piecesIn(slot.id)"
              :key="piece.id"
              type="button"
              class="bot-outfit-piece"
              :class="{
                'is-worn': currentOutfit.includes(piece.id),
                'is-locked': !currentOutfit.includes(piece.id) && !isOutfitUnlocked(piece.id, level, isMain),
                'is-shaking': shakingId === piece.id,
              }"
              :title="isOutfitUnlocked(piece.id, level, isMain) ? piece.label : `${piece.label}（Lv.${piece.from} 解锁）`"
              @click="clickPiece(piece.id)"
            >
              <BotLookAvatar
                :shape="card.bot.shape"
                :color="card.bot.color"
                :outfit="[piece.id]"
                :size="34"
              />
              <span
                v-if="!currentOutfit.includes(piece.id) && !isOutfitUnlocked(piece.id, level, isMain)"
                class="bot-outfit-lock-lv"
                :style="{ background: piece.tierColor }"
              >
                Lv.{{ piece.from }}
              </span>
            </button>
          </div>
        </section>
        <p class="bot-outfit-status" :class="{ 'is-refused': !!refusedPiece }">{{ statusHint }}</p>
      </div>
    </div>
  </div>
</template>

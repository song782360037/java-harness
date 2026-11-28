/**
 * Bot 3 槽位 12 件等级装扮目录与 Deskfolk 30 形象锚点叠加渲染器（统一 48×48 画板）。
 */

export type OutfitSlot = 'head' | 'face' | 'neck'

export type OutfitId =
  | 'partyHat'
  | 'bronzeCrown'
  | 'silverCrown'
  | 'goldCrown'
  | 'halo'
  | 'rainbowCrown'
  | 'roundGlasses'
  | 'sunglasses'
  | 'monocle'
  | 'bowTie'
  | 'tie'
  | 'medal'

export interface OutfitPiece {
  id: OutfitId
  slot: OutfitSlot
  from: number
  label: string
  tierColor: string
}

export const OUTFIT_SLOTS: { id: OutfitSlot; label: string }[] = [
  { id: 'head', label: '头饰' },
  { id: 'face', label: '面饰' },
  { id: 'neck', label: '颈饰' },
]

export const OUTFIT_PIECES: OutfitPiece[] = [
  { id: 'partyHat', slot: 'head', from: 1, label: '派对帽', tierColor: '#64748b' },
  { id: 'bronzeCrown', slot: 'head', from: 10, label: '青铜冠', tierColor: '#2563eb' },
  { id: 'silverCrown', slot: 'head', from: 30, label: '白银冠', tierColor: '#7c3aed' },
  { id: 'goldCrown', slot: 'head', from: 60, label: '黄金冠', tierColor: '#d97706' },
  { id: 'halo', slot: 'head', from: 90, label: '天使光环', tierColor: '#db2777' },
  { id: 'rainbowCrown', slot: 'head', from: 99, label: '彩虹王冠', tierColor: '#e11d48' },
  { id: 'roundGlasses', slot: 'face', from: 1, label: '圆框眼镜', tierColor: '#64748b' },
  { id: 'sunglasses', slot: 'face', from: 10, label: '酷酷墨镜', tierColor: '#2563eb' },
  { id: 'monocle', slot: 'face', from: 60, label: '单片眼镜', tierColor: '#d97706' },
  { id: 'bowTie', slot: 'neck', from: 1, label: '蝴蝶结', tierColor: '#64748b' },
  { id: 'tie', slot: 'neck', from: 30, label: '职场领带', tierColor: '#7c3aed' },
  { id: 'medal', slot: 'neck', from: 90, label: '荣誉勋章', tierColor: '#db2777' },
]

const PIECE_MAP = new Map<string, OutfitPiece>(OUTFIT_PIECES.map(p => [p.id, p]))

export function parseOutfitJson(raw: string | null | undefined): OutfitId[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((id): id is OutfitId => typeof id === 'string' && PIECE_MAP.has(id))
  } catch {
    return []
  }
}

export function isOutfitUnlocked(id: OutfitId, level: number, isMain: boolean): boolean {
  const piece = PIECE_MAP.get(id)
  if (!piece) return false
  if (isMain && id === 'goldCrown') return true
  return level >= piece.from
}

export function wornInSlot(outfit: OutfitId[], slot: OutfitSlot): OutfitId | undefined {
  return outfit.find(id => PIECE_MAP.get(id)?.slot === slot)
}

export function takeOffSlot(outfit: OutfitId[], slot: OutfitSlot): OutfitId[] {
  return outfit.filter(id => PIECE_MAP.get(id)?.slot !== slot)
}

export function toggleOutfitPiece(outfit: OutfitId[], id: OutfitId): OutfitId[] {
  const piece = PIECE_MAP.get(id)
  if (!piece) return outfit
  if (outfit.includes(id)) return outfit.filter(item => item !== id)
  return [...takeOffSlot(outfit, piece.slot), id]
}

export function unlockedAtLevel(level: number, isMain = false): OutfitPiece[] {
  return OUTFIT_PIECES.filter(p => p.from === level && !(isMain && p.id === 'goldCrown' && level > 1))
}

export function nextUnlockLevel(level: number, isMain: boolean): number | null {
  const locked = OUTFIT_PIECES.filter(p => !isOutfitUnlocked(p.id, level, isMain)).map(p => p.from)
  return locked.length ? Math.min(...locked) : null
}

interface ShapeAnchors {
  headY: number
  eyeY: number
  neckY: number
}

const DEFAULT_ANCHORS: ShapeAnchors = { headY: 9.5, eyeY: 24, neckY: 37.5 }

const SHAPE_ANCHORS: Record<string, Partial<ShapeAnchors>> = {
  orb: { headY: 9.5, eyeY: 23.2, neckY: 37.5 },
  cube: { headY: 8.5, eyeY: 22.5, neckY: 38 },
  radar: { headY: 11.5, eyeY: 24.7, neckY: 38 },
  visor: { headY: 9.5, eyeY: 25, neckY: 38 },
  astro: { headY: 9, eyeY: 24.2, neckY: 38.2 },
  clockwork: { headY: 12, eyeY: 24.5, neckY: 38 },
  fox: { headY: 12.5, eyeY: 24.2, neckY: 38 },
  cat: { headY: 13, eyeY: 24.8, neckY: 38 },
  owl: { headY: 11.5, eyeY: 24, neckY: 38.5 },
  bear: { headY: 11, eyeY: 23.5, neckY: 38.5 },
  bunny: { headY: 16, eyeY: 27.8, neckY: 39 },
  shiba: { headY: 13, eyeY: 25, neckY: 38 },
  leaf: { headY: 12.5, eyeY: 26.2, neckY: 38 },
  clover: { headY: 9.5, eyeY: 23.5, neckY: 38 },
  panda: { headY: 11.5, eyeY: 24, neckY: 37.5 },
  penguin: { headY: 10, eyeY: 23.8, neckY: 37.8 },
  frog: { headY: 12.5, eyeY: 15.5, neckY: 38 },
  octopus: { headY: 10, eyeY: 23.7, neckY: 37 },
  hex: { headY: 7.5, eyeY: 23.5, neckY: 39 },
  prism: { headY: 8.5, eyeY: 25, neckY: 38.5 },
  pill: { headY: 12, eyeY: 23.2, neckY: 38.5 },
  chef: { headY: 7, eyeY: 28, neckY: 39.5 },
  wizard: { headY: 6.5, eyeY: 30.5, neckY: 39 },
  coffee: { headY: 13.5, eyeY: 26.8, neckY: 38 },
  star: { headY: 8, eyeY: 23.5, neckY: 36.5 },
  cloud: { headY: 9.5, eyeY: 25, neckY: 35.5 },
  ghost: { headY: 10.5, eyeY: 23.8, neckY: 36.5 },
  dino: { headY: 10.5, eyeY: 22, neckY: 38.5 },
  duck: { headY: 10, eyeY: 22.5, neckY: 37.5 },
  rocket: { headY: 7, eyeY: 22, neckY: 36.5 },
}

export function anchorsForShape(shape: string): ShapeAnchors {
  return { ...DEFAULT_ANCHORS, ...(SHAPE_ANCHORS[shape] || {}) }
}

function renderCrown(y: number, fill: string, stroke: string, gems: [string, string, string]): string {
  return `<g transform="translate(24, ${y})">`
    + `<path d="M-8.5 1.5 L-10 -6.5 L-4.5 -2.5 L0 -8.5 L4.5 -2.5 L10 -6.5 L8.5 1.5 Z" fill="${fill}" stroke="${stroke}" stroke-width="1.2" stroke-linejoin="round"/>`
    + `<rect x="-8.5" y="0.6" width="17" height="2.4" rx="1" fill="${stroke}"/>`
    + `<circle cx="-5.5" cy="-1.2" r="1.15" fill="${gems[0]}"/>`
    + `<circle cx="0" cy="-2.2" r="1.45" fill="${gems[1]}"/>`
    + `<circle cx="5.5" cy="-1.2" r="1.15" fill="${gems[2]}"/>`
    + `</g>`
}

function renderPiece(id: OutfitId, a: ShapeAnchors): string {
  switch (id) {
    case 'partyHat':
      return `<g transform="translate(24, ${a.headY})">`
        + `<polygon points="0,-10 -7.5,2 7.5,2" fill="#f43f5e" stroke="#be123c" stroke-width="1" stroke-linejoin="round"/>`
        + `<path d="M-4.6 -2.5 L4.6 -2.5 M-2.6 -6 L2.6 -6" stroke="#fde047" stroke-width="1.4" stroke-linecap="round"/>`
        + `<circle cx="0" cy="-10.5" r="2.2" fill="#fde047"/>`
        + `</g>`
    case 'bronzeCrown':
      return renderCrown(a.headY, '#d97706', '#92400e', ['#fef3c7', '#fbbf24', '#fef3c7'])
    case 'silverCrown':
      return renderCrown(a.headY, '#e2e8f0', '#64748b', ['#93c5fd', '#38bdf8', '#93c5fd'])
    case 'goldCrown':
      return renderCrown(a.headY, '#fbbf24', '#b45309', ['#f43f5e', '#38bdf8', '#10b981'])
    case 'halo':
      return `<g transform="translate(24, ${Math.max(4, a.headY - 4.5)})">`
        + `<ellipse cx="0" cy="0" rx="10.5" ry="2.8" fill="none" stroke="#fde047" stroke-width="2.4"/>`
        + `<ellipse cx="0" cy="0" rx="10.5" ry="2.8" fill="none" stroke="#ffffff" stroke-opacity="0.65" stroke-width="0.9"/>`
        + `</g>`
    case 'rainbowCrown':
      return `<g transform="translate(24, ${a.headY})">`
        + `<path d="M-9.5 1.5 L-11 -7 L-5 -2.5 L0 -9.5 L5 -2.5 L11 -7 L9.5 1.5 Z" fill="#fb7185" stroke="#be123c" stroke-width="1.1" stroke-linejoin="round"/>`
        + `<path d="M-5 1.5 L-5 -2.5 L0 -9.5 L5 -2.5 L5 1.5 Z" fill="#fbbf24"/>`
        + `<path d="M0 1.5 L0 -9.5 L5 -2.5 L11 -7 L9.5 1.5 Z" fill="#38bdf8" fill-opacity="0.85"/>`
        + `<rect x="-9.5" y="0.6" width="19" height="2.4" rx="1.1" fill="#7c3aed"/>`
        + `<circle cx="0" cy="-3" r="1.7" fill="#ffffff"/>`
        + `<circle cx="-5.5" cy="-1.5" r="1.2" fill="#fde047"/>`
        + `<circle cx="5.5" cy="-1.5" r="1.2" fill="#34d399"/>`
        + `</g>`
    case 'roundGlasses':
      return `<g transform="translate(24, ${a.eyeY})">`
        + `<circle cx="-5.4" cy="0" r="4.1" fill="#ffffff" fill-opacity="0.22" stroke="#1e293b" stroke-width="1.5"/>`
        + `<circle cx="5.4" cy="0" r="4.1" fill="#ffffff" fill-opacity="0.22" stroke="#1e293b" stroke-width="1.5"/>`
        + `<line x1="-1.3" y1="0" x2="1.3" y2="0" stroke="#1e293b" stroke-width="1.5"/>`
        + `<line x1="-11" y1="-0.8" x2="-9.4" y2="0" stroke="#1e293b" stroke-width="1.3"/>`
        + `<line x1="11" y1="-0.8" x2="9.4" y2="0" stroke="#1e293b" stroke-width="1.3"/>`
        + `</g>`
    case 'sunglasses':
      return `<g transform="translate(24, ${a.eyeY})">`
        + `<rect x="-10.8" y="-3.3" width="9.4" height="6.2" rx="2.4" fill="#0f172a" stroke="#334155" stroke-width="1"/>`
        + `<rect x="1.4" y="-3.3" width="9.4" height="6.2" rx="2.4" fill="#0f172a" stroke="#334155" stroke-width="1"/>`
        + `<line x1="-1.4" y1="-1" x2="1.4" y2="-1" stroke="#0f172a" stroke-width="1.8"/>`
        + `<path d="M-8.8 -1.5 L-5.2 1.2 M3.4 -1.5 L7 1.2" stroke="#ffffff" stroke-opacity="0.45" stroke-width="1.2" stroke-linecap="round"/>`
        + `</g>`
    case 'monocle':
      return `<g transform="translate(24, ${a.eyeY})">`
        + `<circle cx="5.5" cy="0" r="4.5" fill="#fef3c7" fill-opacity="0.26" stroke="#d97706" stroke-width="1.7"/>`
        + `<path d="M9.8 2.2 C11.2 5.2 10.6 8.8 8.8 11.4" fill="none" stroke="#d97706" stroke-width="1.2" stroke-dasharray="1.4 1.4"/>`
        + `</g>`
    case 'bowTie':
      return `<g transform="translate(24, ${a.neckY})">`
        + `<polygon points="0,0 -6.8,-3.3 -6.8,3.3" fill="#e11d48" stroke="#9f1239" stroke-width="0.9" stroke-linejoin="round"/>`
        + `<polygon points="0,0 6.8,-3.3 6.8,3.3" fill="#e11d48" stroke="#9f1239" stroke-width="0.9" stroke-linejoin="round"/>`
        + `<circle cx="0" cy="0" r="1.9" fill="#fda4af" stroke="#9f1239" stroke-width="0.8"/>`
        + `</g>`
    case 'tie':
      return `<g transform="translate(24, ${a.neckY - 0.5})">`
        + `<polygon points="-2.4,-1.5 2.4,-1.5 1.6,1.2 -1.6,1.2" fill="#1d4ed8"/>`
        + `<polygon points="-1.6,1.2 1.6,1.2 2.8,6.6 0,9.2 -2.8,6.6" fill="#2563eb" stroke="#1e3a8a" stroke-width="0.8" stroke-linejoin="round"/>`
        + `<line x1="-1.5" y1="3.4" x2="1.8" y2="4.4" stroke="#93c5fd" stroke-width="1"/>`
        + `</g>`
    case 'medal':
      return `<g transform="translate(24, ${a.neckY})">`
        + `<polygon points="-3.8,-2.8 -1,-2.8 0,1.2 -2.4,1.2" fill="#dc2626"/>`
        + `<polygon points="3.8,-2.8 1,-2.8 0,1.2 2.4,1.2" fill="#2563eb"/>`
        + `<circle cx="0" cy="3.8" r="3.6" fill="#fbbf24" stroke="#b45309" stroke-width="1"/>`
        + `<polygon points="0,1.6 0.7,3.1 2.3,3.3 1.1,4.4 1.4,6 0,5.2 -1.4,6 -1.1,4.4 -2.3,3.3 -0.7,3.1" fill="#fffbeb"/>`
        + `</g>`
  }
}

export function renderOutfitOverlays(shape: string, outfit: OutfitId[]): string {
  if (!outfit.length) return ''
  const anchors = anchorsForShape(shape)
  // Render neck -> face -> head so layering feels natural
  const order: Record<OutfitSlot, number> = { neck: 0, face: 1, head: 2 }
  const sorted = [...outfit].sort((a, b) => (order[PIECE_MAP.get(a)?.slot || 'head'] - order[PIECE_MAP.get(b)?.slot || 'head']))
  return sorted.map(id => renderPiece(id, anchors)).join('')
}

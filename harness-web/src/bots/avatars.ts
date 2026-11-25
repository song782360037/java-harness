/**
 * 「工坊伙伴 / Deskfolk 30」原创 Bot 形象图库（30 款分层矢量角色）。
 * 统一 48×48 画板（主体收束在 7..41 区间，完美适配外圈经验环与 20px–120px 任意尺寸），
 * 支持随 Bot 主题色动态着色，不依赖 SVG defs 全局 ID，杜绝多头像同屏冲突。
 */

export type BotShape =
  // 机甲伙伴 (6)
  | 'orb'
  | 'cube'
  | 'radar'
  | 'visor'
  | 'astro'
  | 'clockwork'
  // 桌面萌宠 (6)
  | 'fox'
  | 'cat'
  | 'owl'
  | 'bear'
  | 'bunny'
  | 'shiba'
  // 自然奇物 (6)
  | 'leaf'
  | 'clover'
  | 'panda'
  | 'penguin'
  | 'frog'
  | 'octopus'
  // 职场工坊 (6)
  | 'hex'
  | 'prism'
  | 'pill'
  | 'chef'
  | 'wizard'
  | 'coffee'
  // 星际幻想 (6)
  | 'star'
  | 'cloud'
  | 'ghost'
  | 'dino'
  | 'duck'
  | 'rocket'

export type BotAvatarCategory = 'mech' | 'creature' | 'nature' | 'craft' | 'cosmic'

export interface BotAvatarCategoryInfo {
  id: BotAvatarCategory
  label: string
}

export interface BotAvatarSpec {
  id: BotShape
  name: string
  en: string
  category: BotAvatarCategory
  render: (color: string) => string
}

export const BOT_AVATAR_CATEGORIES: BotAvatarCategoryInfo[] = [
  { id: 'mech', label: '机甲伙伴' },
  { id: 'creature', label: '桌面萌宠' },
  { id: 'nature', label: '自然奇物' },
  { id: 'craft', label: '职场工坊' },
  { id: 'cosmic', label: '星际幻想' },
]

const SAFE_HEX = /^#[0-9a-fA-F]{6}$/

export function sanitizeHexColor(color: string): string {
  return SAFE_HEX.test(color) ? color : '#276649'
}

/** 公共精致五官片段：炭黑亮眼 + 白色高光点 + 柔和腮红 */
function cuteEyes(lx: number, rx: number, y: number, r = 2.3): string {
  return `<circle cx="${lx}" cy="${y}" r="${r}" fill="#1a2220"/>`
    + `<circle cx="${rx}" cy="${y}" r="${r}" fill="#1a2220"/>`
    + `<circle cx="${lx - r * 0.35}" cy="${y - r * 0.35}" r="${r * 0.36}" fill="#ffffff"/>`
    + `<circle cx="${rx - r * 0.35}" cy="${y - r * 0.35}" r="${r * 0.36}" fill="#ffffff"/>`
}

function blush(lx: number, rx: number, y: number): string {
  return `<ellipse cx="${lx}" cy="${y}" rx="2.6" ry="1.4" fill="#fb7185" fill-opacity="0.38"/>`
    + `<ellipse cx="${rx}" cy="${y}" rx="2.6" ry="1.4" fill="#fb7185" fill-opacity="0.38"/>`
}

export const BOT_AVATARS: BotAvatarSpec[] = [
  // ==================== 1. 机甲伙伴 (6) ====================
  {
    id: 'orb',
    name: '圆滚滚',
    en: 'Steward',
    category: 'mech',
    render: c =>
      // 双天线球形管家机：顶天线 + 立体主球 + 暖白面屏 + 微笑圆眼 + 领结
      `<line x1="17" y1="12" x2="14.5" y2="7.5" stroke="${c}" stroke-width="2" stroke-linecap="round"/>`
      + `<circle cx="14" cy="7" r="2" fill="#f59e0b"/>`
      + `<line x1="31" y1="12" x2="33.5" y2="7.5" stroke="${c}" stroke-width="2" stroke-linecap="round"/>`
      + `<circle cx="34" cy="7" r="2" fill="#f59e0b"/>`
      + `<circle cx="24" cy="24.5" r="14.5" fill="${c}"/>`
      + `<ellipse cx="19" cy="14.5" rx="6" ry="2.6" fill="#ffffff" fill-opacity="0.22" transform="rotate(-18 19 14.5)"/>`
      + `<path d="M11.5 31a14.5 14.5 0 0 0 25 0a16 10 0 0 1-25 0Z" fill="#0f172a" fill-opacity="0.18"/>`
      + `<rect x="13" y="17.5" width="22" height="12.5" rx="6.2" fill="#fffaf2"/>`
      + cuteEyes(19.2, 28.8, 23.2, 2.2)
      + `<path d="M21.8 26.5q2.2 2 4.4 0" fill="none" stroke="#1a2220" stroke-width="1.6" stroke-linecap="round"/>`
      + blush(15.5, 32.5, 26)
      + `<polygon points="24,35 20,33.2 20,36.8" fill="#f59e0b"/>`
      + `<polygon points="24,35 28,33.2 28,36.8" fill="#f59e0b"/>`
      + `<circle cx="24" cy="35" r="1.4" fill="#fef3c7"/>`,
  },
  {
    id: 'cube',
    name: '方块机',
    en: 'Coder',
    category: 'mech',
    render: c =>
      // 复古 CRT 编程小机：顶灯 + 方壳 + 深色屏幕 + 发光青绿像素眼 + 侧耳麦
      `<rect x="21.5" y="6.5" width="5" height="3.2" rx="1.6" fill="#34d399"/>`
      + `<rect x="6.5" y="20" width="3.5" height="9" rx="1.7" fill="${c}" fill-opacity="0.8"/>`
      + `<rect x="38" y="20" width="3.5" height="9" rx="1.7" fill="${c}" fill-opacity="0.8"/>`
      + `<rect x="9.5" y="9.5" width="29" height="29" rx="8.5" fill="${c}"/>`
      + `<path d="M14 11.5h20" stroke="#ffffff" stroke-opacity="0.28" stroke-width="2" stroke-linecap="round"/>`
      + `<rect x="13" y="14.5" width="22" height="16.5" rx="4.5" fill="#15201e"/>`
      + `<path d="M16.5 23l2.2-2.4 2.2 2.4" fill="none" stroke="#5eead4" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"/>`
      + `<path d="M27.1 23l2.2-2.4 2.2 2.4" fill="none" stroke="#5eead4" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"/>`
      + `<rect x="21.5" y="26.2" width="5" height="1.7" rx="0.85" fill="#5eead4" fill-opacity="0.85"/>`
      + `<circle cx="15" cy="34.5" r="1.3" fill="#ffffff" fill-opacity="0.5"/>`
      + `<circle cx="19" cy="34.5" r="1.3" fill="#ffffff" fill-opacity="0.5"/>`
      + `<rect x="27" y="33.5" width="7" height="2" rx="1" fill="#0f172a" fill-opacity="0.25"/>`,
  },
  {
    id: 'radar',
    name: '雷达哨',
    en: 'Scout',
    category: 'mech',
    render: c =>
      // 雷达侦察机：顶部倾斜雷达盘 + 圆角机身 + 宽银色双镜护目镜
      `<path d="M18 11.5a7 7 0 0 1 12-1" fill="none" stroke="${c}" stroke-width="2.6" stroke-linecap="round"/>`
      + `<line x1="24" y1="10" x2="24" y2="14.5" stroke="${c}" stroke-width="2.2"/>`
      + `<circle cx="24" cy="7.5" r="2" fill="#38bdf8"/>`
      + `<rect x="10" y="14" width="28" height="24" rx="11" fill="${c}"/>`
      + `<path d="M15 16.5h18" stroke="#ffffff" stroke-opacity="0.24" stroke-width="2" stroke-linecap="round"/>`
      + `<rect x="12" y="19.5" width="24" height="10.5" rx="5.2" fill="#e2e8f0"/>`
      + `<rect x="14" y="21.2" width="20" height="7.1" rx="3.5" fill="#1e293b"/>`
      + `<circle cx="19.5" cy="24.7" r="2.2" fill="#38bdf8"/>`
      + `<circle cx="28.5" cy="24.7" r="2.2" fill="#38bdf8"/>`
      + `<line x1="22.8" y1="24.7" x2="25.2" y2="24.7" stroke="#38bdf8" stroke-width="1.2" stroke-opacity="0.6"/>`
      + `<path d="M21 33.2h6" stroke="#ffffff" stroke-opacity="0.7" stroke-width="1.8" stroke-linecap="round"/>`,
  },
  {
    id: 'visor',
    name: '霓虹侠',
    en: 'Cyborg',
    category: 'mech',
    render: c =>
      // 流线型未来头盔 + 发光斜切护目镜 + 双翼耳饰
      `<polygon points="8,20 12,16 12,28 8,26" fill="${c}" fill-opacity="0.75"/>`
      + `<polygon points="40,20 36,16 36,28 40,26" fill="${c}" fill-opacity="0.75"/>`
      + `<path d="M11 24c0-9 5.5-14 13-14s13 5 13 14v5c0 5-5.5 9-13 9s-13-4-13-9Z" fill="${c}"/>`
      + `<path d="M18 12.5c3-1.2 9-1.2 12 0" fill="none" stroke="#ffffff" stroke-opacity="0.3" stroke-width="2" stroke-linecap="round"/>`
      + `<path d="M12.5 21.5h23l-1.5 6.8c-.4 1.6-1.8 2.7-3.5 2.7h-13c-1.7 0-3.1-1.1-3.5-2.7Z" fill="#0f172a"/>`
      + `<path d="M15.5 25.2h17" stroke="#38bdf8" stroke-width="2.6" stroke-linecap="round"/>`
      + `<path d="M16.5 23.2l4.5-1" stroke="#ffffff" stroke-opacity="0.45" stroke-width="1.3" stroke-linecap="round"/>`
      + `<path d="M21.5 34h5" stroke="#ffffff" stroke-opacity="0.6" stroke-width="1.6" stroke-linecap="round"/>`,
  },
  {
    id: 'astro',
    name: '宇航员',
    en: 'Astro',
    category: 'mech',
    render: c =>
      // 太空头盔 + 主题色边框与耳罩 + 深空金星反光面罩
      `<circle cx="10" cy="24" r="3.4" fill="${c}"/>`
      + `<circle cx="38" cy="24" r="3.4" fill="${c}"/>`
      + `<line x1="38" y1="20.5" x2="39.5" y2="11" stroke="${c}" stroke-width="2" stroke-linecap="round"/>`
      + `<circle cx="39.8" cy="10" r="1.8" fill="#fbbf24"/>`
      + `<circle cx="24" cy="24" r="14.5" fill="#f8fafc" stroke="${c}" stroke-width="2.6"/>`
      + `<rect x="13" y="15.5" width="22" height="16.5" rx="8.2" fill="#1e293b"/>`
      + `<path d="M16.5 19.2c2-1.8 5.2-2.2 8-1.5" fill="none" stroke="#ffffff" stroke-opacity="0.45" stroke-width="2.1" stroke-linecap="round"/>`
      + `<circle cx="19.2" cy="24.2" r="2" fill="#fde68a"/>`
      + `<circle cx="28.8" cy="24.2" r="2" fill="#fde68a"/>`
      + `<path d="M22.2 27.2q1.8 1.6 3.6 0" fill="none" stroke="#fde68a" stroke-width="1.5" stroke-linecap="round"/>`
      + `<circle cx="31.5" cy="19.5" r="1" fill="#ffffff" fill-opacity="0.7"/>`,
  },
  {
    id: 'clockwork',
    name: '发条匠',
    en: 'Tinker',
    category: 'mech',
    render: c =>
      // 顶部金色发条钥匙 + 复古圆筒机身 + 双圆框护目镜
      `<rect x="22.8" y="9.5" width="2.4" height="5" fill="#d97706"/>`
      + `<circle cx="19.5" cy="8.5" r="3.2" fill="none" stroke="#f59e0b" stroke-width="2.2"/>`
      + `<circle cx="28.5" cy="8.5" r="3.2" fill="none" stroke="#f59e0b" stroke-width="2.2"/>`
      + `<rect x="10.5" y="14" width="27" height="24.5" rx="9" fill="${c}"/>`
      + `<path d="M14 16.5h20" stroke="#ffffff" stroke-opacity="0.22" stroke-width="2" stroke-linecap="round"/>`
      + `<circle cx="18.8" cy="24.5" r="5.2" fill="#fef3c7" stroke="#d97706" stroke-width="2"/>`
      + `<circle cx="29.2" cy="24.5" r="5.2" fill="#fef3c7" stroke="#d97706" stroke-width="2"/>`
      + `<line x1="23.5" y1="24.5" x2="24.5" y2="24.5" stroke="#d97706" stroke-width="2"/>`
      + cuteEyes(18.8, 29.2, 24.5, 2.1)
      + `<path d="M20.8 33q3.2 2.2 6.4 0" fill="none" stroke="#fffbeb" stroke-width="1.8" stroke-linecap="round"/>`,
  },

  // ==================== 2. 桌面萌宠 (6) ====================
  {
    id: 'fox',
    name: '机灵狐',
    en: 'Fox',
    category: 'creature',
    render: c =>
      // 尖耳小狐狸 + 暖白双颊绒毛 + 机灵笑眼与小黑鼻
      `<polygon points="10,22 12,8 21,15" fill="${c}"/>`
      + `<polygon points="12.5,18.5 13.5,11.5 18.5,15.5" fill="#fff7ed"/>`
      + `<polygon points="38,22 36,8 27,15" fill="${c}"/>`
      + `<polygon points="35.5,18.5 34.5,11.5 29.5,15.5" fill="#fff7ed"/>`
      + `<ellipse cx="24" cy="26" rx="14.5" ry="12.5" fill="${c}"/>`
      + `<path d="M10.5 27.5c4-2.5 8.5-1.5 13.5 3.5c5-5 9.5-6 13.5-3.5c-1.8 6.8-7 11-13.5 11s-11.7-4.2-13.5-11Z" fill="#fffaf2"/>`
      + cuteEyes(18.5, 29.5, 24.2, 2.2)
      + `<polygon points="24,31.2 21.8,28.5 26.2,28.5" fill="#1a2220"/>`
      + blush(14.5, 33.5, 28.5),
  },
  {
    id: 'cat',
    name: '摸鱼猫',
    en: 'Cat',
    category: 'creature',
    render: c =>
      // 圆润小猫 + 粉嫩内耳 + 额头三道条纹 + 俏皮猫嘴与胡须
      `<polygon points="11,21 12.5,9 21,15.5" fill="${c}"/>`
      + `<polygon points="13,18.5 14,12.2 18.5,15.8" fill="#fda4af"/>`
      + `<polygon points="37,21 35.5,9 27,15.5" fill="${c}"/>`
      + `<polygon points="35,18.5 34,12.2 29.5,15.8" fill="#fda4af"/>`
      + `<ellipse cx="24" cy="26.5" rx="14.2" ry="12.2" fill="${c}"/>`
      + `<path d="M21 15.5v3.2M24 14.8v4M27 15.5v3.2" stroke="#0f172a" stroke-opacity="0.24" stroke-width="1.6" stroke-linecap="round"/>`
      + `<ellipse cx="24" cy="29.5" rx="8.2" ry="6.2" fill="#fffaf2"/>`
      + cuteEyes(18.5, 29.5, 24.8, 2.2)
      + `<path d="M21.5 29.5q1.2 1.8 2.5 0q1.3 1.8 2.5 0" fill="none" stroke="#1a2220" stroke-width="1.5" stroke-linecap="round"/>`
      + `<polygon points="24,28.8 22.5,27.2 25.5,27.2" fill="#fb7185"/>`
      + `<path d="M9 26.5h4.5M9.5 29.5h4M39 26.5h-4.5M38.5 29.5h-4" stroke="#ffffff" stroke-opacity="0.65" stroke-width="1.3" stroke-linecap="round"/>`,
  },
  {
    id: 'owl',
    name: '夜猫头鹰',
    en: 'Owl',
    category: 'creature',
    render: c =>
      // 博学猫头鹰：双耳羽簇 + 暖白面盘 + 细金框圆眼镜 + 小黄喙
      `<polygon points="12,16 11,9 18,13.5" fill="${c}"/>`
      + `<polygon points="36,16 37,9 30,13.5" fill="${c}"/>`
      + `<ellipse cx="24" cy="25.5" rx="14" ry="13.5" fill="${c}"/>`
      + `<circle cx="18.8" cy="24" r="6.2" fill="#fffaf2"/>`
      + `<circle cx="29.2" cy="24" r="6.2" fill="#fffaf2"/>`
      + `<circle cx="18.8" cy="24" r="5.3" fill="none" stroke="#d97706" stroke-width="1.5"/>`
      + `<circle cx="29.2" cy="24" r="5.3" fill="none" stroke="#d97706" stroke-width="1.5"/>`
      + `<line x1="23.8" y1="24" x2="24.2" y2="24" stroke="#d97706" stroke-width="1.5"/>`
      + cuteEyes(18.8, 29.2, 24, 2.3)
      + `<polygon points="24,31.2 21.6,27.4 26.4,27.4" fill="#f59e0b"/>`
      + `<path d="M18.5 34.5q2 1.6 4 0M25.5 34.5q2 1.6 4 0" fill="none" stroke="#ffffff" stroke-opacity="0.45" stroke-width="1.5" stroke-linecap="round"/>`,
  },
  {
    id: 'bear',
    name: '厚道熊',
    en: 'Bear',
    category: 'creature',
    render: c =>
      // 温暖圆耳小熊 + 奶白大圆吻部 + 憨厚微笑
      `<circle cx="13" cy="14" r="5" fill="${c}"/>`
      + `<circle cx="13" cy="14" r="2.5" fill="#fde68a" fill-opacity="0.6"/>`
      + `<circle cx="35" cy="14" r="5" fill="${c}"/>`
      + `<circle cx="35" cy="14" r="2.5" fill="#fde68a" fill-opacity="0.6"/>`
      + `<circle cx="24" cy="25.5" r="13.8" fill="${c}"/>`
      + `<ellipse cx="19" cy="16" rx="5" ry="2.2" fill="#ffffff" fill-opacity="0.18" transform="rotate(-15 19 16)"/>`
      + `<ellipse cx="24" cy="29.5" rx="7.2" ry="5.6" fill="#fff7ed"/>`
      + cuteEyes(18.5, 29.5, 23.5, 2.1)
      + `<ellipse cx="24" cy="27.8" rx="2.6" ry="1.8" fill="#1a2220"/>`
      + `<path d="M21.8 31.2q2.2 1.8 4.4 0" fill="none" stroke="#1a2220" stroke-width="1.5" stroke-linecap="round"/>`
      + blush(14.5, 33.5, 27.5),
  },
  {
    id: 'bunny',
    name: '闪电兔',
    en: 'Bunny',
    category: 'creature',
    render: c =>
      // 一竖一微折长耳兔 + 粉嫩内耳 + 活力眨眼
      `<ellipse cx="18" cy="13.5" rx="3.8" ry="8.5" fill="${c}" transform="rotate(-10 18 13.5)"/>`
      + `<ellipse cx="18" cy="14" rx="1.9" ry="5.5" fill="#fda4af" transform="rotate(-10 18 14)"/>`
      + `<ellipse cx="30.5" cy="14.5" rx="3.8" ry="8.2" fill="${c}" transform="rotate(18 30.5 14.5)"/>`
      + `<ellipse cx="30.5" cy="14.8" rx="1.9" ry="5.2" fill="#fda4af" transform="rotate(18 30.5 14.8)"/>`
      + `<ellipse cx="24" cy="28.5" rx="13" ry="11" fill="${c}"/>`
      + `<ellipse cx="24" cy="29.8" rx="9.5" ry="7.8" fill="#fffaf5"/>`
      + `<circle cx="19.5" cy="27.8" r="2.2" fill="#1a2220"/>`
      + `<circle cx="18.7" cy="27" r="0.8" fill="#ffffff"/>`
      + `<path d="M26.5 28.2q2.2-2.2 4.4 0" fill="none" stroke="#1a2220" stroke-width="1.9" stroke-linecap="round"/>`
      + `<ellipse cx="24" cy="30.8" rx="1.6" ry="1.1" fill="#fb7185"/>`
      + `<path d="M22.2 32.8q1.8 1.5 3.6 0" fill="none" stroke="#1a2220" stroke-width="1.4" stroke-linecap="round"/>`
      + blush(16.2, 31.8, 31.2),
  },
  {
    id: 'shiba',
    name: '柴柴',
    en: 'Shiba',
    category: 'creature',
    render: c =>
      // 柴犬：竖耳 + 经典豆豆眉 + 奶油色笑脸 + 吐舌头
      `<polygon points="11.5,20 13.5,9.5 21.5,15.5" fill="${c}"/>`
      + `<polygon points="13.5,18 14.5,12.5 19,15.8" fill="#fef3c7"/>`
      + `<polygon points="36.5,20 34.5,9.5 26.5,15.5" fill="${c}"/>`
      + `<polygon points="34.5,18 33.5,12.5 29,15.8" fill="#fef3c7"/>`
      + `<ellipse cx="24" cy="26.5" rx="14" ry="12.2" fill="${c}"/>`
      + `<path d="M12.5 27c2.8-3 7.5-3.2 11.5-1.2c4-2 8.7-1.8 11.5 1.2c-1 6.5-5.5 11.5-11.5 11.5S13.5 33.5 12.5 27Z" fill="#fffbeb"/>`
      + `<circle cx="19.5" cy="20.2" r="1.4" fill="#fffbeb"/>`
      + `<circle cx="28.5" cy="20.2" r="1.4" fill="#fffbeb"/>`
      + cuteEyes(18.8, 29.2, 25, 2.1)
      + `<ellipse cx="24" cy="28.4" rx="2.3" ry="1.6" fill="#1a2220"/>`
      + `<path d="M21.2 31.2q2.8 2.4 5.6 0" fill="none" stroke="#1a2220" stroke-width="1.5" stroke-linecap="round"/>`
      + `<ellipse cx="24" cy="33.4" rx="2" ry="1.6" fill="#fb7185"/>`,
  },

  // ==================== 3. 自然奇物 (6) ====================
  {
    id: 'leaf',
    name: '嫩芽灵',
    en: 'Sprout',
    category: 'nature',
    render: c =>
      // 柔和团子精灵 + 顶部发芽双翠叶
      `<path d="M24 14v-4.5" stroke="#16a34a" stroke-width="2.2" stroke-linecap="round"/>`
      + `<path d="M24 11c-5.5-5-10-1.5-7 2.5c2 2.2 5.5 1 7-2.5Z" fill="#4ade80"/>`
      + `<path d="M24 10c5.5-5.5 10.5-1.8 7 2.5c-2 2.2-5.5 1-7-2.5Z" fill="#22c55e"/>`
      + `<path d="M11 27.5c0-8 5.8-14 13-14s13 6 13 14c0 6.5-5.5 11-13 11s-13-4.5-13-11Z" fill="${c}"/>`
      + `<ellipse cx="18.5" cy="19.5" rx="4.5" ry="2" fill="#ffffff" fill-opacity="0.24" transform="rotate(-24 18.5 19.5)"/>`
      + `<circle cx="19" cy="26.2" r="2.2" fill="#ffffff"/>`
      + `<circle cx="29" cy="26.2" r="2.2" fill="#ffffff"/>`
      + `<circle cx="19.4" cy="26.4" r="1.1" fill="#1a2220"/>`
      + `<circle cx="29.4" cy="26.4" r="1.1" fill="#1a2220"/>`
      + `<path d="M21.8 30.2q2.2 2 4.4 0" fill="none" stroke="#ffffff" stroke-width="1.7" stroke-linecap="round"/>`
      + blush(15, 33, 29.5),
  },
  {
    id: 'clover',
    name: '幸运草',
    en: 'Clover',
    category: 'nature',
    render: c =>
      // 四叶草花瓣蓬松头套 + 元气小脸
      `<circle cx="17.5" cy="17.5" r="8.5" fill="${c}"/>`
      + `<circle cx="30.5" cy="17.5" r="8.5" fill="${c}"/>`
      + `<circle cx="17.5" cy="30.5" r="8.5" fill="${c}"/>`
      + `<circle cx="30.5" cy="30.5" r="8.5" fill="${c}"/>`
      + `<circle cx="24" cy="24" r="10.2" fill="#fffaf2"/>`
      + cuteEyes(20.2, 27.8, 23.5, 2)
      + `<path d="M21.8 27.2q2.2 2 4.4 0" fill="none" stroke="#1a2220" stroke-width="1.6" stroke-linecap="round"/>`
      + blush(17.5, 30.5, 26.5)
      + `<circle cx="14.5" cy="14" r="1.6" fill="#ffffff" fill-opacity="0.32"/>`,
  },
  {
    id: 'panda',
    name: '团子熊',
    en: 'Panda',
    category: 'nature',
    render: c =>
      // 黑白圆滚熊猫 + 主题色小围巾/耳环点缀
      `<circle cx="13" cy="14" r="4.8" fill="#1e293b"/>`
      + `<circle cx="35" cy="14" r="4.8" fill="#1e293b"/>`
      + `<ellipse cx="24" cy="25" rx="14" ry="12.5" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.2"/>`
      + `<ellipse cx="18" cy="24" rx="4.2" ry="5" fill="#1e293b" transform="rotate(-14 18 24)"/>`
      + `<ellipse cx="30" cy="24" rx="4.2" ry="5" fill="#1e293b" transform="rotate(14 30 24)"/>`
      + `<circle cx="18.4" cy="23.5" r="1.6" fill="#ffffff"/>`
      + `<circle cx="29.6" cy="23.5" r="1.6" fill="#ffffff"/>`
      + `<circle cx="18.6" cy="23.7" r="0.7" fill="#1e293b"/>`
      + `<circle cx="29.4" cy="23.7" r="0.7" fill="#1e293b"/>`
      + `<ellipse cx="24" cy="28.2" rx="2.3" ry="1.5" fill="#1e293b"/>`
      + `<path d="M21.8 31q2.2 1.7 4.4 0" fill="none" stroke="#1e293b" stroke-width="1.4" stroke-linecap="round"/>`
      + blush(14.5, 33.5, 28.5)
      + `<path d="M14 35.2c3.5 2.5 16.5 2.5 20 0v3c-3.8 2.2-16.2 2.2-20 0Z" fill="${c}"/>`,
  },
  {
    id: 'penguin',
    name: '企鹅君',
    en: 'Penguin',
    category: 'nature',
    render: c =>
      // 绅士小企鹅：主题色外羽 + 心形暖白脸罩 + 橙色扁嘴 + 领结
      `<ellipse cx="24" cy="25" rx="14" ry="14.5" fill="${c}"/>`
      + `<path d="M24 19c-2.5-3.2-8.5-2.6-9.5 2.8c-.8 4.6 1.5 11.8 9.5 13.4c8-1.6 10.3-8.8 9.5-13.4c-1-5.4-7-6-9.5-2.8Z" fill="#fffaf2"/>`
      + cuteEyes(19.2, 28.8, 23.8, 2.1)
      + `<ellipse cx="24" cy="27.8" rx="3.8" ry="2.2" fill="#f59e0b"/>`
      + `<path d="M21.2 28q2.8 1.4 5.6 0" fill="none" stroke="#b45309" stroke-width="1"/>`
      + blush(15.8, 32.2, 27.5)
      + `<polygon points="24,35.8 20,34 20,37.6" fill="#ef4444"/>`
      + `<polygon points="24,35.8 28,34 28,37.6" fill="#ef4444"/>`
      + `<circle cx="24" cy="35.8" r="1.3" fill="#fecaca"/>`,
  },
  {
    id: 'frog',
    name: '青蛙仔',
    en: 'Frog',
    category: 'nature',
    render: c =>
      // 头顶大圆眼小青蛙 + 宽治愈笑嘴 + 荷叶高光
      `<circle cx="15.5" cy="15.5" r="6" fill="${c}"/>`
      + `<circle cx="32.5" cy="15.5" r="6" fill="${c}"/>`
      + `<circle cx="15.5" cy="15.5" r="3.8" fill="#ffffff"/>`
      + `<circle cx="32.5" cy="15.5" r="3.8" fill="#ffffff"/>`
      + cuteEyes(15.5, 32.5, 15.5, 2.1)
      + `<ellipse cx="24" cy="27.5" rx="14.5" ry="11" fill="${c}"/>`
      + `<ellipse cx="24" cy="31" rx="10.5" ry="6" fill="#ffffff" fill-opacity="0.22"/>`
      + `<path d="M16.5 28.5q7.5 5 15 0" fill="none" stroke="#1a2220" stroke-width="1.9" stroke-linecap="round"/>`
      + blush(13.5, 34.5, 27.5),
  },
  {
    id: 'octopus',
    name: '八爪鱼',
    en: 'Octo',
    category: 'nature',
    render: c =>
      // 多线程小章鱼：圆顶头 + 底部波浪触手 + 气泡
      `<circle cx="36.5" cy="10.5" r="2.2" fill="#38bdf8" fill-opacity="0.55"/>`
      + `<circle cx="39.5" cy="15" r="1.3" fill="#38bdf8" fill-opacity="0.45"/>`
      + `<path d="M11.5 23.5c0-7.5 5.5-13 12.5-13s12.5 5.5 12.5 13v7c0 2.5-2.5 4.2-4.8 3c-1.5-.8-2.8-.8-4.2 0c-1.5.9-3.5.9-5 0c-1.4-.8-2.7-.8-4.2 0c-2.3 1.2-4.8-.5-4.8-3Z" fill="${c}"/>`
      + `<ellipse cx="18.5" cy="16" rx="4.5" ry="2.2" fill="#ffffff" fill-opacity="0.24" transform="rotate(-24 18.5 16)"/>`
      + `<circle cx="19" cy="23.5" r="3" fill="#ffffff"/>`
      + `<circle cx="29" cy="23.5" r="3" fill="#ffffff"/>`
      + cuteEyes(19.3, 28.7, 23.7, 1.7)
      + `<circle cx="24" cy="28" r="1.6" fill="#1a2220" fill-opacity="0.75"/>`
      + blush(14.8, 33.2, 27.2),
  },

  // ==================== 4. 职场工坊 (6) ====================
  {
    id: 'hex',
    name: '架构师',
    en: 'Architect',
    category: 'craft',
    render: c =>
      // 精密六边形面具 + 单边金色眼镜 + 冷静微笑
      `<polygon points="24,7.5 38,15.5 38,32.5 24,40.5 10,32.5 10,15.5" fill="${c}" stroke="${c}" stroke-width="2" stroke-linejoin="round"/>`
      + `<polygon points="24,10.5 35,16.8 35,24 24,18 13,24 13,16.8" fill="#ffffff" fill-opacity="0.14"/>`
      + `<circle cx="19" cy="23.5" r="2.2" fill="#ffffff"/>`
      + `<circle cx="19" cy="23.5" r="1.1" fill="#1a2220"/>`
      + `<circle cx="29.2" cy="23.5" r="4.6" fill="#fef3c7" fill-opacity="0.32" stroke="#fbbf24" stroke-width="1.8"/>`
      + `<circle cx="29.2" cy="23.5" r="2" fill="#ffffff"/>`
      + `<circle cx="29.2" cy="23.5" r="1" fill="#1a2220"/>`
      + `<path d="M33.5 26.5c1 2.8.8 6-.6 8.5" fill="none" stroke="#fbbf24" stroke-width="1.2" stroke-dasharray="1.5 1.5"/>`
      + `<path d="M21 31.2h6" stroke="#ffffff" stroke-width="1.8" stroke-linecap="round"/>`,
  },
  {
    id: 'prism',
    name: '侦探棱',
    en: 'Sleuth',
    category: 'craft',
    render: c =>
      // 侦探帽檐 + 菱形利落面庞 + 放大镜片
      `<path d="M11 18c2-5.5 7.5-9 13-9s11 3.5 13 9Z" fill="${c}"/>`
      + `<ellipse cx="24" cy="18.2" rx="15.5" ry="2.6" fill="#0f172a" fill-opacity="0.32"/>`
      + `<path d="M12.5 18.5h23l-2 12.5c-.8 4.8-4.8 8-9.5 8s-8.7-3.2-9.5-8Z" fill="#fffaf2" stroke="${c}" stroke-width="2"/>`
      + cuteEyes(19, 28.8, 25, 2)
      + `<path d="M22 30.8h4.5" stroke="#1a2220" stroke-width="1.6" stroke-linecap="round"/>`
      + `<circle cx="31.5" cy="31.5" r="5" fill="#e0f2fe" stroke="#0284c7" stroke-width="2"/>`
      + `<line x1="35.2" y1="35.2" x2="39" y2="39" stroke="#0369a1" stroke-width="2.6" stroke-linecap="round"/>`
      + `<path d="M29.8 29.8a2.5 2.5 0 0 1 2.8-.4" fill="none" stroke="#ffffff" stroke-width="1.4" stroke-linecap="round"/>`,
  },
  {
    id: 'pill',
    name: '胶囊娃',
    en: 'Capsule',
    category: 'craft',
    render: c =>
      // 专注降噪耳机胶囊娃：头梁耳机 + 圆润双色胶囊体
      `<path d="M10.5 24a13.5 13.5 0 0 1 27 0" fill="none" stroke="#1e293b" stroke-width="3" stroke-linecap="round"/>`
      + `<rect x="7" y="20.5" width="4.5" height="9.5" rx="2.2" fill="#1e293b"/>`
      + `<rect x="36.5" y="20.5" width="4.5" height="9.5" rx="2.2" fill="#1e293b"/>`
      + `<rect x="11.5" y="13.5" width="25" height="25.5" rx="12.5" fill="${c}"/>`
      + `<path d="M11.5 27h25v-.5a12.5 12.5 0 0 1-25 .5Z" fill="#fffaf2"/>`
      + `<path d="M11.5 26.5h25v1a12.5 12.5 0 0 1-25 0Z" fill="#fffaf2"/>`
      + `<ellipse cx="18" cy="18.5" rx="3.8" ry="1.8" fill="#ffffff" fill-opacity="0.26" transform="rotate(-24 18 18.5)"/>`
      + `<circle cx="19.5" cy="23.2" r="2.1" fill="#ffffff"/>`
      + `<circle cx="28.5" cy="23.2" r="2.1" fill="#ffffff"/>`
      + `<path d="M21.5 31.2q2.5 2 5 0" fill="none" stroke="#1a2220" stroke-width="1.6" stroke-linecap="round"/>`
      + blush(16.5, 31.5, 30.5),
  },
  {
    id: 'chef',
    name: '大厨师',
    en: 'Chef',
    category: 'craft',
    render: c =>
      // 蓬松厨师高帽 + 温暖主题色脸颊/帽带 + 俏皮小八字胡
      `<circle cx="17" cy="13.5" r="5" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.2"/>`
      + `<circle cx="24" cy="11.5" r="5.6" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.2"/>`
      + `<circle cx="31" cy="13.5" r="5" fill="#ffffff" stroke="#e2e8f0" stroke-width="1.2"/>`
      + `<rect x="14.5" y="14.5" width="19" height="6.5" rx="2" fill="#ffffff"/>`
      + `<rect x="14" y="19" width="20" height="3.2" rx="1.2" fill="${c}"/>`
      + `<circle cx="24" cy="29.5" r="10.8" fill="#fde68a"/>`
      + `<circle cx="24" cy="29.5" r="10.8" fill="#fff7ed" fill-opacity="0.55"/>`
      + cuteEyes(20, 28, 28, 2)
      + blush(16.8, 31.2, 30.8)
      + `<path d="M24 32c-2-1.4-4.6-.8-5.2.8c1.8 1 3.6.8 5.2-.2c1.6 1 3.4 1.2 5.2.2c-.6-1.6-3.2-2.2-5.2-.8Z" fill="#475569"/>`,
  },
  {
    id: 'wizard',
    name: '法术帽',
    en: 'Mage',
    category: 'craft',
    render: c =>
      // 尖顶星月巫师帽 + 阴影下发光神秘金色双眼
      `<path d="M12.5 22.5L23.5 7l9.5 15.5Z" fill="${c}"/>`
      + `<ellipse cx="24" cy="23" rx="16" ry="3.8" fill="${c}"/>`
      + `<path d="M15.5 21.2c3-1.2 14-1.2 17 0" fill="none" stroke="#fbbf24" stroke-width="2.2" stroke-linecap="round"/>`
      + `<polygon points="24,13 24.8,15.2 27.2,15.2 25.3,16.5 26,18.8 24,17.4 22,18.8 22.7,16.5 20.8,15.2 23.2,15.2" fill="#fef08a"/>`
      + `<path d="M14.5 25c0 8.2 4 14 9.5 14s9.5-5.8 9.5-14Z" fill="#1e1b4b"/>`
      + `<ellipse cx="20" cy="30.5" rx="2.1" ry="2.6" fill="#fde047"/>`
      + `<ellipse cx="28" cy="30.5" rx="2.1" ry="2.6" fill="#fde047"/>`
      + `<circle cx="20.6" cy="29.6" r="0.7" fill="#ffffff"/>`
      + `<circle cx="28.6" cy="29.6" r="0.7" fill="#ffffff"/>`,
  },
  {
    id: 'coffee',
    name: '咖啡杯',
    en: 'Barista',
    category: 'craft',
    render: c =>
      // 提神马克杯精灵：飘浮热气 + 主题色杯身 + 杯耳 + 笑脸
      `<path d="M18.5 12.5c0-2 1.8-2.5 1.8-4.5M24 11.8c0-2 1.8-2.5 1.8-4.5M29.5 12.5c0-2 1.8-2.5 1.8-4.5" fill="none" stroke="#94a3b8" stroke-width="1.6" stroke-linecap="round"/>`
      + `<path d="M35.5 20.5h2.2a4.3 4.3 0 0 1 0 8.6h-2.2" fill="none" stroke="${c}" stroke-width="3" stroke-linecap="round"/>`
      + `<rect x="10.5" y="15.5" width="25" height="22.5" rx="6" fill="${c}"/>`
      + `<ellipse cx="23" cy="16.5" rx="11.5" ry="2.6" fill="#78350f"/>`
      + `<rect x="13" y="21.5" width="20" height="12.5" rx="4" fill="#fffaf2" fill-opacity="0.92"/>`
      + cuteEyes(19, 27, 26.8, 2)
      + `<path d="M21 30.2q2 1.8 4 0" fill="none" stroke="#1a2220" stroke-width="1.5" stroke-linecap="round"/>`
      + blush(15.8, 30.2, 29.5),
  },

  // ==================== 5. 星际幻想 (6) ====================
  {
    id: 'star',
    name: '星光宝',
    en: 'Star',
    category: 'cosmic',
    render: c =>
      // 圆润五角星精灵 + 星星眼高光 + 温暖腮红
      `<polygon points="24,7 28.8,16.8 39.5,18.3 31.8,25.8 33.6,36.5 24,31.5 14.4,36.5 16.2,25.8 8.5,18.3 19.2,16.8" fill="${c}" stroke="${c}" stroke-width="2.4" stroke-linejoin="round"/>`
      + `<circle cx="21" cy="16" r="2.2" fill="#ffffff" fill-opacity="0.26"/>`
      + cuteEyes(20, 28, 23.5, 2.2)
      + `<path d="M21.8 27.5q2.2 2 4.4 0" fill="none" stroke="#1a2220" stroke-width="1.6" stroke-linecap="round"/>`
      + blush(16.8, 31.2, 26.8),
  },
  {
    id: 'cloud',
    name: '云朵君',
    en: 'Cloud',
    category: 'cosmic',
    render: c =>
      // 软绵多层主题色云朵 + 微粉腮红 + 小水滴吊坠
      `<circle cx="17" cy="21" r="7.5" fill="${c}"/>`
      + `<circle cx="25.5" cy="17.5" r="8.5" fill="${c}"/>`
      + `<circle cx="33" cy="23" r="6.8" fill="${c}"/>`
      + `<rect x="11" y="21" width="27" height="12.5" rx="6.2" fill="${c}"/>`
      + `<circle cx="23.5" cy="14" r="3" fill="#ffffff" fill-opacity="0.22"/>`
      + `<circle cx="19.8" cy="25" r="2.1" fill="#ffffff"/>`
      + `<circle cx="28.2" cy="25" r="2.1" fill="#ffffff"/>`
      + `<circle cx="20.1" cy="25.2" r="1.1" fill="#1a2220"/>`
      + `<circle cx="28.5" cy="25.2" r="1.1" fill="#1a2220"/>`
      + `<path d="M22.2 28.6q1.8 1.6 3.6 0" fill="none" stroke="#ffffff" stroke-width="1.6" stroke-linecap="round"/>`
      + blush(16, 32, 28)
      + `<path d="M18 36.2c0 1.4-1.3 2.5-1.3 2.5s-1.3-1.1-1.3-2.5a1.3 1.3 0 0 1 2.6 0Z" fill="#38bdf8"/>`
      + `<path d="M31.5 36.8c0 1.4-1.3 2.5-1.3 2.5s-1.3-1.1-1.3-2.5a1.3 1.3 0 0 1 2.6 0Z" fill="#38bdf8"/>`,
  },
  {
    id: 'ghost',
    name: '小幽灵',
    en: 'Boo',
    category: 'cosmic',
    render: c =>
      // 软萌波浪裙摆小幽灵 + 金色小光环 + 好奇大眼睛
      `<ellipse cx="24" cy="8.8" rx="6.5" ry="1.8" fill="none" stroke="#fbbf24" stroke-width="1.8"/>`
      + `<path d="M12 24.5c0-7.2 5.4-12.5 12-12.5s12 5.3 12 12.5v11.2c0 1.2-1.4 1.8-2.3 1l-2.5-2.2c-.7-.6-1.7-.6-2.4 0l-2.4 2.2c-.7.6-1.7.6-2.4 0l-2.4-2.2c-.7-.6-1.7-.6-2.4 0l-2.5 2.2c-.9.8-2.3.2-2.3-1Z" fill="${c}"/>`
      + `<ellipse cx="18.5" cy="17.5" rx="4" ry="2" fill="#ffffff" fill-opacity="0.24" transform="rotate(-24 18.5 17.5)"/>`
      + `<ellipse cx="19.5" cy="23.8" rx="2.6" ry="3.2" fill="#ffffff"/>`
      + `<ellipse cx="28.5" cy="23.8" rx="2.6" ry="3.2" fill="#ffffff"/>`
      + `<circle cx="20" cy="24.2" r="1.4" fill="#1a2220"/>`
      + `<circle cx="28" cy="24.2" r="1.4" fill="#1a2220"/>`
      + `<circle cx="24" cy="28.8" r="1.6" fill="#ffffff" fill-opacity="0.85"/>`
      + blush(15.5, 32.5, 27.5),
  },
  {
    id: 'dino',
    name: '小恐龙',
    en: 'Dino',
    category: 'cosmic',
    render: c =>
      // 圆头小霸王龙 + 背脊三枚小背鳍 + 活力笑颜
      `<polygon points="16,13 19.5,8.5 22,12.5" fill="#fbbf24"/>`
      + `<polygon points="22.5,11.5 26,7.5 28.5,12" fill="#fbbf24"/>`
      + `<polygon points="29,12.5 33,9.5 34,14.5" fill="#fbbf24"/>`
      + `<path d="M11 24.5c0-7.5 5.8-13 13.2-13c7.8 0 13.3 5 13.3 12c0 4.5-2.5 7.5-6.5 8.5v4c0 2-1.8 3.5-4 3.5H18c-4 0-7-3-7-7Z" fill="${c}"/>`
      + `<path d="M11 29.5h15c1.5 0 2.5 1.2 2.5 2.5v5.5H18c-4 0-7-3-7-7Z" fill="#fffaf2" fill-opacity="0.85"/>`
      + cuteEyes(18.5, 28.8, 22, 2.2)
      + `<path d="M19.5 26.8q5 2.2 10.5 0" fill="none" stroke="#1a2220" stroke-width="1.6" stroke-linecap="round"/>`
      + `<polygon points="22,27.5 23.2,29.8 24.4,27.7" fill="#ffffff"/>`
      + `<polygon points="26.2,27.7 27.4,29.8 28.6,27.5" fill="#ffffff"/>`,
  },
  {
    id: 'duck',
    name: '小黄鸭',
    en: 'Duck',
    category: 'cosmic',
    render: c =>
      // 程序员调试小鸭：头顶呆毛 + 橙色扁嘴 + 主题色领结/围巾
      `<path d="M23.5 10.5c-.8-3 1.5-4.5 3-3.2c1 1 0 2.8-1 3.5" fill="none" stroke="#f59e0b" stroke-width="2.2" stroke-linecap="round"/>`
      + `<circle cx="24" cy="24" r="13.5" fill="#fde047"/>`
      + `<ellipse cx="18.5" cy="15.8" rx="4.8" ry="2.2" fill="#ffffff" fill-opacity="0.45" transform="rotate(-20 18.5 15.8)"/>`
      + cuteEyes(18.8, 29.2, 22.5, 2.2)
      + `<ellipse cx="24" cy="27.5" rx="5.6" ry="2.8" fill="#f97316"/>`
      + `<path d="M20 27.6q4 1.6 8 0" fill="none" stroke="#c2410c" stroke-width="1.1"/>`
      + blush(14.8, 33.2, 26.5)
      + `<path d="M14.5 35c3 2.5 16 2.5 19 0v3.2c-3.5 2-15.5 2-19 0Z" fill="${c}"/>`,
  },
  {
    id: 'rocket',
    name: '火箭仔',
    en: 'Rocket',
    category: 'cosmic',
    render: c =>
      // 冲天小火箭：侧翼 + 流线主体 + 圆舷窗微笑 + 火焰尾焰
      `<path d="M14.5 28L9 35l6.5-1.5Z" fill="${c}" fill-opacity="0.82"/>`
      + `<path d="M33.5 28L39 35l-6.5-1.5Z" fill="${c}" fill-opacity="0.82"/>`
      + `<path d="M20.5 34.5c0 3 1.8 5.5 3.5 6.8c1.7-1.3 3.5-3.8 3.5-6.8Z" fill="#f97316"/>`
      + `<path d="M22.2 34.8c0 1.8.9 3.4 1.8 4.2c.9-.8 1.8-2.4 1.8-4.2Z" fill="#fde047"/>`
      + `<path d="M24 7c5.8 4 9.5 10.5 9.5 18.5v7.5c0 1.4-1.1 2.5-2.5 2.5H17c-1.4 0-2.5-1.1-2.5-2.5v-7.5C14.5 17.5 18.2 11 24 7Z" fill="${c}"/>`
      + `<path d="M19.5 12c2.5-2.5 6.5-2.5 9 0" fill="none" stroke="#ffffff" stroke-opacity="0.3" stroke-width="1.8" stroke-linecap="round"/>`
      + `<circle cx="24" cy="22.5" r="6.2" fill="#e0f2fe" stroke="#ffffff" stroke-width="2"/>`
      + cuteEyes(21.8, 26.2, 22, 1.5)
      + `<path d="M22.2 25q1.8 1.5 3.6 0" fill="none" stroke="#1a2220" stroke-width="1.3" stroke-linecap="round"/>`,
  },
]

const AVATAR_MAP = new Map<string, BotAvatarSpec>(BOT_AVATARS.map(spec => [spec.id, spec]))

export const BOT_SHAPES: BotShape[] = BOT_AVATARS.map(spec => spec.id)

export const BOT_COLORS = [
  '#276649',
  '#2d5d9c',
  '#b04a6f',
  '#6e56a8',
  '#c08238',
  '#2f7d7a',
  '#a63d34',
  '#4b5563',
  '#0284c7',
  '#d97706',
  '#0d9488',
  '#7c3aed',
]

export function getBotAvatar(shape: string): BotAvatarSpec {
  return AVATAR_MAP.get(shape) || BOT_AVATARS[0]
}

export function renderBotAvatarInner(shape: string, color: string): string {
  return getBotAvatar(shape).render(sanitizeHexColor(color))
}

export function buildBotAvatarSvg(shape: string, color: string): string {
  const safe = sanitizeHexColor(color)
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">${getBotAvatar(shape).render(safe)}</svg>`
}

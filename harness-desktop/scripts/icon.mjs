// 按 harness-web/public/harness-icon.svg 的图形栅格化出跨平台应用图标（Windows .ico、macOS .icns、Linux PNG 图标集）；无第三方依赖。
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'

const SAMPLES = 4
const BACKGROUND = [0x25, 0x2a, 0x28]
const LIGHT = [0xf5, 0xf4, 0xee]
const ACCENT = [0x8d, 0xd9, 0xb6]
const HALF_STROKE = 1.25

// 与 SVG 一致的折线（32 单位坐标系），后绘制的覆盖先绘制的。
const STROKES = [
  { color: LIGHT, points: [[12, 8], [8, 8], [8, 24], [12, 24]] },
  { color: LIGHT, points: [[20, 8], [24, 8], [24, 24], [20, 24]] },
  { color: ACCENT, points: [[8, 16], [24, 16]] },
]

/** 写出 Windows 图标：单个 256px PNG 帧的 ICO 文件。 */
export function writeIcon(file) {
  fs.writeFileSync(file, ico(renderPng(256)))
}

/** 一次渲染并写出全平台图标资源：icon.ico、icon.icns 及 Linux icons/{256x256,512x512}.png。 */
export function writeIcons(buildDir) {
  const png256 = renderPng(256)
  const png512 = renderPng(512)
  fs.mkdirSync(path.join(buildDir, 'icons'), { recursive: true })
  fs.writeFileSync(path.join(buildDir, 'icon.ico'), ico(png256))
  fs.writeFileSync(path.join(buildDir, 'icon.icns'), icns([
    ['ic08', png256],
    ['ic09', png512],
  ]))
  fs.writeFileSync(path.join(buildDir, 'icons', '256x256.png'), png256)
  fs.writeFileSync(path.join(buildDir, 'icons', '512x512.png'), png512)
}

function renderPng(size) {
  const scale = size / 32
  const pixels = Buffer.alloc(size * (size * 4 + 1))
  for (let y = 0; y < size; y++) {
    const row = y * (size * 4 + 1)
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0
      for (let sy = 0; sy < SAMPLES; sy++) {
        for (let sx = 0; sx < SAMPLES; sx++) {
          const px = (x + (sx + 0.5) / SAMPLES) / scale
          const py = (y + (sy + 0.5) / SAMPLES) / scale
          if (!insideRoundedSquare(px, py)) continue
          const color = colorAt(px, py)
          r += color[0]; g += color[1]; b += color[2]; a += 255
        }
      }
      const count = SAMPLES * SAMPLES
      const covered = a / 255
      const offset = row + 1 + x * 4
      pixels[offset] = covered ? Math.round(r / covered) : 0
      pixels[offset + 1] = covered ? Math.round(g / covered) : 0
      pixels[offset + 2] = covered ? Math.round(b / covered) : 0
      pixels[offset + 3] = Math.round(a / count)
    }
  }
  return png(pixels, size)
}

function insideRoundedSquare(x, y) {
  const radius = 8
  const dx = Math.max(radius - x, 0, x - (32 - radius))
  const dy = Math.max(radius - y, 0, y - (32 - radius))
  return dx * dx + dy * dy <= radius * radius
}

function colorAt(x, y) {
  let color = BACKGROUND
  for (const stroke of STROKES) {
    for (let i = 1; i < stroke.points.length; i++) {
      if (segmentDistance(x, y, stroke.points[i - 1], stroke.points[i]) <= HALF_STROKE) color = stroke.color
    }
  }
  return color
}

function segmentDistance(x, y, [x1, y1], [x2, y2]) {
  const dx = x2 - x1, dy = y2 - y1
  const t = Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
  return Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))
}

function ico(image) {
  const header = Buffer.alloc(22)
  header.writeUInt16LE(1, 2)
  header.writeUInt16LE(1, 4)
  // 宽高字节写 0 表示 256，色板数与保留位为 0；1 个色平面、32 位色深。
  header.writeUInt16LE(1, 10)
  header.writeUInt16LE(32, 12)
  header.writeUInt32LE(image.length, 14)
  header.writeUInt32LE(header.length, 18)
  return Buffer.concat([header, image])
}

/** Apple ICNS 容器：封装标准 PNG 块（ic08 = 256px，ic09 = 512px）。 */
function icns(entries) {
  const blocks = entries.map(([type, data]) => {
    const header = Buffer.alloc(8)
    header.write(type, 0, 4, 'ascii')
    header.writeUInt32BE(8 + data.length, 4)
    return Buffer.concat([header, data])
  })
  const total = 8 + blocks.reduce((sum, block) => sum + block.length, 0)
  const fileHeader = Buffer.alloc(8)
  fileHeader.write('icns', 0, 4, 'ascii')
  fileHeader.writeUInt32BE(total, 4)
  return Buffer.concat([fileHeader, ...blocks])
}

function png(raw, size) {
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0)
  header.writeUInt32BE(size, 4)
  header.set([8, 6, 0, 0, 0], 8)
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

function chunk(type, data) {
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(zlib.crc32(body))
  return Buffer.concat([length, body, crc])
}

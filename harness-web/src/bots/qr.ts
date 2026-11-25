/**
 * ISO/IEC 18004 QR Code Model 2 (ECC Level L, Byte Mode, Versions 1–5) zero-dependency encoder.
 * Supports up to 106 UTF-8 bytes — plenty for LAN companion URLs (e.g. http://192.168.1.20:4317).
 */

interface VersionSpec {
  version: number
  size: number
  dataCodewords: number
  ecCodewords: number
}

const VERSIONS: VersionSpec[] = [
  { version: 1, size: 21, dataCodewords: 19, ecCodewords: 7 },
  { version: 2, size: 25, dataCodewords: 34, ecCodewords: 10 },
  { version: 3, size: 29, dataCodewords: 55, ecCodewords: 15 },
  { version: 4, size: 33, dataCodewords: 80, ecCodewords: 20 },
  { version: 5, size: 37, dataCodewords: 108, ecCodewords: 26 },
]

const GF_EXP = new Uint8Array(512)
const GF_LOG = new Uint8Array(256)
{
  let v = 1
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = v
    GF_LOG[v] = i
    v <<= 1
    if (v & 0x100) v ^= 0x11d
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255]
}

function gfMul(a: number, b: number): number {
  return a === 0 || b === 0 ? 0 : GF_EXP[GF_LOG[a] + GF_LOG[b]]
}

function rsRemainder(data: Uint8Array, ecLen: number): Uint8Array {
  let gen = [1]
  for (let i = 0; i < ecLen; i++) {
    const next = new Array<number>(gen.length + 1).fill(0)
    const root = GF_EXP[i]
    for (let j = 0; j < gen.length; j++) {
      next[j] ^= gen[j]
      next[j + 1] ^= gfMul(gen[j], root)
    }
    gen = next
  }
  const msg = new Uint8Array(data.length + ecLen)
  msg.set(data)
  for (let i = 0; i < data.length; i++) {
    const coef = msg[i]
    if (coef !== 0) {
      for (let j = 1; j <= ecLen; j++) {
        msg[i + j] ^= gfMul(gen[j], coef)
      }
    }
  }
  return msg.slice(data.length)
}

function formatBitsLevelL(mask: number): number {
  const data = (0b01 << 3) | (mask & 7)
  let rem = data << 10
  for (let i = 14; i >= 10; i--) {
    if ((rem >>> i) & 1) rem ^= 0x537 << (i - 10)
  }
  return ((data << 10) | rem) ^ 0x5412
}

function maskBit(mask: number, r: number, c: number): boolean {
  switch (mask) {
    case 0: return (r + c) % 2 === 0
    case 1: return r % 2 === 0
    case 2: return c % 3 === 0
    case 3: return (r + c) % 3 === 0
    case 4: return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0
    case 5: return ((r * c) % 2) + ((r * c) % 3) === 0
    case 6: return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0
    default: return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0
  }
}

export interface QrMatrix {
  version: number
  size: number
  modules: boolean[][]
}

export function encodeQr(text: string): QrMatrix {
  const bytes = new TextEncoder().encode(text)
  const spec = VERSIONS.find(v => bytes.length + 2 <= v.dataCodewords)
  if (!spec) {
    throw new Error('QR 内容过长，超出当前编码支持上限')
  }

  // 1. Build data codewords (Byte mode 0100 + 8-bit length + bytes + terminator + pad bytes)
  const bits: number[] = []
  const pushBits = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1)
  }
  pushBits(0b0100, 4)
  pushBits(bytes.length, 8)
  for (const b of bytes) pushBits(b, 8)
  const maxBits = spec.dataCodewords * 8
  for (let i = 0; i < 4 && bits.length < maxBits; i++) bits.push(0)
  while (bits.length % 8 !== 0) bits.push(0)
  const data = new Uint8Array(spec.dataCodewords)
  let di = 0
  for (let i = 0; i < bits.length; i += 8) {
    let byte = 0
    for (let b = 0; b < 8; b++) byte = (byte << 1) | bits[i + b]
    data[di++] = byte
  }
  const padBytes = [0xec, 0x11]
  for (let p = 0; di < spec.dataCodewords; p++) data[di++] = padBytes[p & 1]

  const ec = rsRemainder(data, spec.ecCodewords)
  const allCodewords = new Uint8Array(data.length + ec.length)
  allCodewords.set(data)
  allCodewords.set(ec, data.length)

  // 2. Prepare base grid and function module mask
  const size = spec.size
  const base: boolean[][] = Array.from({ length: size }, () => Array<boolean>(size).fill(false))
  const isFunc: boolean[][] = Array.from({ length: size }, () => Array<boolean>(size).fill(false))

  const setFunc = (r: number, c: number, dark: boolean) => {
    if (r >= 0 && r < size && c >= 0 && c < size) {
      base[r][c] = dark
      isFunc[r][c] = true
    }
  }

  const drawFinder = (top: number, left: number) => {
    for (let dr = -1; dr <= 7; dr++) {
      for (let dc = -1; dc <= 7; dc++) {
        const inOuter = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6
        const dark = inOuter && (
          dr === 0 || dr === 6 || dc === 0 || dc === 6 ||
          (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4)
        )
        setFunc(top + dr, left + dc, dark)
      }
    }
  }

  drawFinder(0, 0)
  drawFinder(0, size - 7)
  drawFinder(size - 7, 0)

  // Timing patterns
  for (let i = 8; i < size - 8; i++) {
    setFunc(6, i, i % 2 === 0)
    setFunc(i, 6, i % 2 === 0)
  }

  // Alignment pattern for versions 2–5 (only center not overlapping finders is (size-7, size-7))
  if (spec.version >= 2) {
    const center = size - 7
    for (let dr = -2; dr <= 2; dr++) {
      for (let dc = -2; dc <= 2; dc++) {
        const dist = Math.max(Math.abs(dr), Math.abs(dc))
        setFunc(center + dr, center + dc, dist !== 1)
      }
    }
  }

  // Reserve format info cells & dark module
  const writeFormat = (grid: boolean[][], mask: number, markFunc: boolean) => {
    const fmt = formatBitsLevelL(mask)
    const put = (r: number, c: number, bitIndex: number) => {
      grid[r][c] = ((fmt >>> bitIndex) & 1) === 1
      if (markFunc) isFunc[r][c] = true
    }
    const tlRow8Cols = [0, 1, 2, 3, 4, 5, 7, 8]
    for (let i = 0; i < 8; i++) put(8, tlRow8Cols[i], 14 - i)
    const tlCol8Rows = [7, 5, 4, 3, 2, 1, 0]
    for (let i = 0; i < 7; i++) put(tlCol8Rows[i], 8, 6 - i)
    for (let i = 0; i < 7; i++) put(size - 1 - i, 8, 14 - i)
    for (let i = 0; i < 8; i++) put(8, size - 8 + i, 7 - i)
    grid[size - 8][8] = true
    if (markFunc) isFunc[size - 8][8] = true
  }
  writeFormat(base, 0, true)

  // 3. Place data + EC bits in zig-zag order
  const dataBits: boolean[] = []
  for (const byte of allCodewords) {
    for (let i = 7; i >= 0; i--) dataBits.push(((byte >>> i) & 1) === 1)
  }
  let bitIdx = 0
  let upward = true
  for (let col = size - 1; col >= 1; col -= 2) {
    if (col === 6) col--
    for (let step = 0; step < size; step++) {
      const r = upward ? size - 1 - step : step
      for (let dc = 0; dc < 2; dc++) {
        const c = col - dc
        if (!isFunc[r][c]) {
          base[r][c] = bitIdx < dataBits.length ? dataBits[bitIdx++] : false
        }
      }
    }
    upward = !upward
  }

  // 4. Evaluate all 8 masks and pick the lowest penalty score
  let bestGrid = base
  let bestScore = Number.POSITIVE_INFINITY
  for (let mask = 0; mask < 8; mask++) {
    const candidate = base.map((row, r) =>
      row.map((val, c) => (isFunc[r][c] ? val : maskBit(mask, r, c) ? !val : val)),
    )
    writeFormat(candidate, mask, false)
    const score = penaltyScore(candidate)
    if (score < bestScore) {
      bestScore = score
      bestGrid = candidate
    }
  }

  return { version: spec.version, size, modules: bestGrid }
}

function penaltyScore(grid: boolean[][]): number {
  const n = grid.length
  let score = 0
  let darkCount = 0

  // Rule 1: runs of >= 5 same-colored modules in rows and columns
  for (let r = 0; r < n; r++) {
    let runColor = grid[r][0]
    let runLen = 1
    if (runColor) darkCount++
    for (let c = 1; c < n; c++) {
      if (grid[r][c]) darkCount++
      if (grid[r][c] === runColor) {
        runLen++
      } else {
        if (runLen >= 5) score += 3 + (runLen - 5)
        runColor = grid[r][c]
        runLen = 1
      }
    }
    if (runLen >= 5) score += 3 + (runLen - 5)
  }
  for (let c = 0; c < n; c++) {
    let runColor = grid[0][c]
    let runLen = 1
    for (let r = 1; r < n; r++) {
      if (grid[r][c] === runColor) {
        runLen++
      } else {
        if (runLen >= 5) score += 3 + (runLen - 5)
        runColor = grid[r][c]
        runLen = 1
      }
    }
    if (runLen >= 5) score += 3 + (runLen - 5)
  }

  // Rule 2: 2x2 blocks of same color
  for (let r = 0; r < n - 1; r++) {
    for (let c = 0; c < n - 1; c++) {
      const v = grid[r][c]
      if (grid[r][c + 1] === v && grid[r + 1][c] === v && grid[r + 1][c + 1] === v) score += 3
    }
  }

  // Rule 4: dark/light balance
  const percent = (darkCount * 100) / (n * n)
  const deviation = Math.floor(Math.abs(percent - 50) / 5)
  score += deviation * 10
  return score
}

/** Build compact SVG path data (with 4-module quiet zone) for dark modules. */
export function qrSvgData(text: string): { viewBoxSize: number; path: string; size: number } {
  const qr = encodeQr(text)
  const quiet = 4
  const parts: string[] = []
  for (let r = 0; r < qr.size; r++) {
    for (let c = 0; c < qr.size; c++) {
      if (qr.modules[r][c]) {
        parts.push(`M${c + quiet},${r + quiet}h1v1h-1z`)
      }
    }
  }
  return { viewBoxSize: qr.size + quiet * 2, path: parts.join(''), size: qr.size }
}

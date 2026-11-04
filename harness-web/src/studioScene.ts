import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { CSS2DObject, CSS2DRenderer } from 'three/addons/renderers/CSS2DRenderer.js'
import type { MemberStateKind } from './groups'

/** One desk in the room; the host desk uses kind "host" and a robot figure. */
export interface SceneWorker {
  id: string; name: string; template: string; color: string; kind: MemberStateKind | 'host'; label: string
  attention: boolean; busy: boolean; selected: boolean; related: boolean
}

type Hair = 'short' | 'long' | 'bun' | 'spiky'
type Extra = 'tie' | 'beret' | 'headphones' | 'hoodie' | 'glasses' | 'cap'
const looks: Record<string, { hair: Hair; extra?: Extra }> = {
  'project-manager': { hair: 'short', extra: 'tie' }, 'ui-designer': { hair: 'long', extra: 'beret' }, frontend: { hair: 'spiky', extra: 'headphones' },
  backend: { hair: 'short', extra: 'hoodie' }, 'tech-lead': { hair: 'short', extra: 'glasses' }, qa: { hair: 'bun', extra: 'glasses' }, devops: { hair: 'short', extra: 'cap' },
}
const skins = ['#f3cfa8', '#e2b088', '#c98d62', '#9c6a45']
const hairs = ['#2b2118', '#5a3825', '#8c5a2b', '#1f2a44', '#6b3d5e', '#3d3d3d', '#b5733a']
const lamp: Partial<Record<MemberStateKind | 'host', string>> = {
  approval: '#f5b342', review: '#f5b342', stopping: '#f5b342', failed: '#e5484d', done: '#46a758',
  working: '#3e9bff', reviewing: '#3e9bff', finishing: '#3e9bff', queued: '#7aa7d8',
}
const typing = new Set<string>(['working', 'reviewing', 'finishing', 'stopping'])
const COLUMNS = 4, SPACING_X = 3.6, SPACING_Z = 3.4, LABEL_Y = 2.45

const hashOf = (seed: string) => [...seed].reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 7)
const box = (w: number, h: number, d: number, material: THREE.Material, x = 0, y = 0, z = 0) => {
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), material)
  mesh.position.set(x, y, z)
  mesh.castShadow = true
  mesh.receiveShadow = true
  return mesh
}
const standard = (color: string, extra: THREE.MeshStandardMaterialParameters = {}) => new THREE.MeshStandardMaterial({ color, roughness: .8, ...extra })

/** A seated figure plus its desk, with the parts the animation moves. */
class Station {
  readonly root = new THREE.Group()
  readonly figure = new THREE.Group()
  readonly head = new THREE.Group()
  readonly leftArm = new THREE.Group()
  readonly rightArm = new THREE.Group()
  readonly torso: THREE.Mesh
  readonly lamp: THREE.Mesh
  readonly screen: THREE.Mesh
  readonly ring: THREE.Mesh
  readonly mug: THREE.Group
  readonly label: CSS2DObject
  readonly element: HTMLDivElement
  readonly phase: number
  worker: SceneWorker

  constructor(worker: SceneWorker, onSelect: (id: string) => void) {
    this.worker = worker
    const hash = hashOf(worker.id)
    this.phase = (hash % 628) / 100
    this.root.userData.id = worker.id
    const robot = worker.kind === 'host'
    const look = looks[worker.template] ?? { hair: (['short', 'long', 'bun', 'spiky'] as Hair[])[hash % 4] }
    const skin = standard(robot ? '#9aa7b4' : skins[hash % skins.length], robot ? { metalness: .4, roughness: .4 } : {})
    const shirt = standard(worker.color)
    const hair = standard(hairs[(hash >>> 3) % hairs.length])
    const dark = standard('#2f3542')
    const wood = standard('#b98b5c'), woodDark = standard('#8f6a44')

    // 桌子在人物前方（朝向镜头一侧），显示器屏幕朝向人物。
    const desk = new THREE.Group()
    desk.add(box(1.7, .08, .85, wood, 0, .76, .55))
    for (const [x, z] of [[-.78, .2], [.78, .2], [-.78, .9], [.78, .9]]) desk.add(box(.07, .76, .07, woodDark, x, .38, z))
    // 笔记本电脑：屏幕较矮且向后倾，不挡人物的脸。
    desk.add(box(.66, .03, .42, standard('#3a3f47'), 0, .815, .5))
    this.screen = box(.66, .4, .03, standard('#1f2329', { emissive: '#000000' }), 0, 1.0, .74)
    this.screen.rotation.x = .3
    desk.add(this.screen)
    this.lamp = new THREE.Mesh(new THREE.SphereGeometry(.07, 16, 12), standard('#9aa0a6', { emissive: '#000000' }))
    this.lamp.position.set(.7, .87, .85)
    desk.add(this.lamp)
    this.mug = new THREE.Group()
    this.mug.add(box(.12, .14, .12, standard('#f0ede4'), -.62, .87, .45))
    desk.add(this.mug)
    // 椅子。
    desk.add(box(.6, .08, .55, dark, 0, .45, -.3), box(.6, .6, .07, dark, 0, .8, -.58), box(.06, .45, .06, dark, 0, .22, -.3))
    this.root.add(desk)

    this.torso = box(.56, .6, .32, robot ? skin : shirt, 0, .86, -.25)
    this.figure.add(this.torso)
    if (!robot) {
      const pants = standard('#3b4252')
      for (const x of [-.13, .13]) this.figure.add(box(.18, .16, .5, pants, x, .56, -.05), box(.16, .45, .16, pants, x, .25, .2))
      if (look.extra === 'hoodie') this.figure.add(box(.6, .22, .2, standard(worker.color, { color: new THREE.Color(worker.color).multiplyScalar(.75) }), 0, 1.13, -.42))
      if (look.extra === 'tie') this.figure.add(box(.08, .36, .02, standard('#a33a3a'), 0, .92, -.08))
    } else this.figure.add(box(.3, .16, .02, standard(worker.color, { emissive: worker.color, emissiveIntensity: .4 }), 0, .9, -.08))

    this.head.position.set(0, 1.36, -.24)
    this.head.add(box(.42, .42, .4, skin))
    const eye = robot ? standard('#5ee6ff', { emissive: '#5ee6ff', emissiveIntensity: 1 }) : standard('#1d1d1d')
    const eyeSize = robot ? .09 : .05
    this.head.add(box(eyeSize, eyeSize, .02, eye, -.09, .03, .205), box(eyeSize, eyeSize, .02, eye, .09, .03, .205))
    if (robot) {
      this.head.add(box(.03, .18, .03, dark, 0, .3, 0))
      const bulb = new THREE.Mesh(new THREE.SphereGeometry(.06, 12, 10), standard('#f2c94c', { emissive: '#f2c94c', emissiveIntensity: .8 }))
      bulb.position.set(0, .42, 0)
      this.head.add(bulb, box(.06, .14, .14, dark, -.24, 0, 0), box(.06, .14, .14, dark, .24, 0, 0))
    } else {
      this.head.add(box(.44, .1, .42, hair, 0, .25, 0), box(.44, .2, .1, hair, 0, .12, -.17))
      if (look.hair === 'long') this.head.add(box(.08, .45, .38, hair, -.24, -.05, -.02), box(.08, .45, .38, hair, .24, -.05, -.02), box(.44, .45, .08, hair, 0, -.05, -.22))
      if (look.hair === 'bun') this.head.add(new THREE.Mesh(new THREE.SphereGeometry(.12, 12, 10), hair).translateY(.36).translateZ(-.08))
      if (look.hair === 'spiky') for (const x of [-.14, 0, .14]) this.head.add(box(.08, .12, .08, hair, x, .34, .02).rotateZ(x))
      if (look.hair === 'short' || look.hair === 'spiky') this.head.add(box(.44, .06, .06, hair, 0, .19, .19))
      if (look.extra === 'glasses') {
        const frame = standard('#2f3542')
        this.head.add(box(.13, .09, .02, frame, -.09, .03, .215), box(.13, .09, .02, frame, .09, .03, .215), box(.05, .02, .02, frame, 0, .05, .215))
      }
      if (look.extra === 'beret') this.head.add(new THREE.Mesh(new THREE.CylinderGeometry(.26, .26, .08, 20), standard('#c0392b')).translateY(.32).rotateZ(.15))
      if (look.extra === 'cap') {
        const cap = standard(worker.color, { color: new THREE.Color(worker.color).multiplyScalar(.7) })
        this.head.add(box(.46, .12, .44, cap, 0, .3, 0), box(.42, .03, .22, cap, 0, .25, .3))
      }
      if (look.extra === 'headphones') {
        const band = new THREE.Mesh(new THREE.TorusGeometry(.25, .03, 8, 20, Math.PI), dark)
        band.position.y = .05
        this.head.add(band, box(.08, .16, .16, standard('#ff6b6b'), -.25, 0, 0), box(.08, .16, .16, standard('#ff6b6b'), .25, 0, 0))
      }
      this.head.add(box(.1, .03, .02, standard('#b06a5a'), 0, -.1, .205))
    }
    this.figure.add(this.head)

    for (const [arm, x] of [[this.leftArm, -.36], [this.rightArm, .36]] as const) {
      arm.position.set(x, 1.1, -.25)
      arm.add(box(.14, .5, .14, robot ? skin : shirt, 0, -.25, 0), box(.13, .12, .13, skin, 0, -.54, 0))
      this.figure.add(arm)
    }
    this.root.add(this.figure)

    this.ring = new THREE.Mesh(new THREE.RingGeometry(1.25, 1.42, 48), new THREE.MeshBasicMaterial({ color: '#4b705b', transparent: true, opacity: 0, depthWrite: false }))
    this.ring.rotation.x = -Math.PI / 2
    this.ring.userData.ignorePick = true
    this.ring.position.set(0, .015, .2)
    this.root.add(this.ring)

    this.element = document.createElement('div')
    this.element.className = 'scene-label'
    this.element.addEventListener('click', event => { event.stopPropagation(); onSelect(this.worker.id) })
    this.label = new CSS2DObject(this.element)
    this.label.position.set(0, LABEL_Y, -.2)
    this.root.add(this.label)
    this.apply(worker)
  }

  /** Refresh the label, lamp, ring and visibility for a new state; poses follow in tick(). */
  apply(worker: SceneWorker) {
    this.worker = worker
    const color = lamp[worker.kind] ?? (worker.kind === 'host' && worker.busy ? '#3e9bff' : '#9aa0a6')
    const lampMaterial = this.lamp.material as THREE.MeshStandardMaterial
    lampMaterial.color.set(color)
    lampMaterial.emissive.set(worker.kind === 'disabled' ? '#000000' : color)
    lampMaterial.emissiveIntensity = lamp[worker.kind] || worker.busy ? 1.2 : .25
    const screen = this.screen.material as THREE.MeshStandardMaterial
    screen.emissive.set(typing.has(worker.kind) || (worker.kind === 'host' && worker.busy) ? '#6fa8ff' : worker.kind === 'disabled' ? '#000000' : '#1c2a3a')
    this.figure.visible = worker.kind !== 'disabled'
    this.mug.visible = ['idle', 'done', 'skipped', 'host'].includes(worker.kind)
    const ring = this.ring.material as THREE.MeshBasicMaterial
    ring.opacity = worker.selected ? .95 : worker.related ? .45 : 0
    const className = `scene-label is-${worker.kind}${worker.attention ? ' is-attention' : ''}${worker.selected ? ' is-selected' : ''}`
    const text = `${worker.attention ? '! ' : ''}${worker.label}|${worker.name}`
    // 每次快照都会调用，标签内容没变时不动 DOM。
    if (className === this.element.className && text === this.element.dataset.text) return
    this.element.className = className
    this.element.dataset.text = text
    this.element.replaceChildren()
    const bubble = document.createElement('span')
    bubble.className = 'scene-bubble'
    bubble.textContent = `${worker.attention ? '! ' : ''}${worker.label}`
    const name = document.createElement('span')
    name.className = 'scene-name'
    name.textContent = worker.name
    this.element.append(bubble, name)
  }

  /** Ease every moving part towards the pose of the current state. */
  tick(time: number, ease: number, motion: number) {
    const kind = this.worker.kind
    const t = time + this.phase
    const busy = typing.has(kind) || (kind === 'host' && this.worker.busy)
    const speed = kind === 'stopping' ? 6 : 16
    let left = -.95, right = -.95, rightZ = 0, headX = 0, headY = 0, lean = 0
    if (busy) {
      left = -1.2 + Math.sin(t * speed) * .1 * motion
      right = -1.2 + Math.sin(t * speed + Math.PI) * .1 * motion
      headX = .18 + Math.sin(t * 2) * .03 * motion
    } else if (kind === 'approval') {
      right = -2.9; rightZ = Math.sin(t * 7) * .3 * motion; headX = -.15; left = -1
    } else if (kind === 'review') {
      headX = -.1; headY = Math.sin(t * .8) * .25 * motion; right = -1.5
    } else if (kind === 'failed') {
      headX = .5; left = -1.25; right = -1.25
    } else if (kind === 'done') {
      left = -.25; right = -.25; lean = -.12; headY = Math.sin(t * .6) * .2 * motion
    } else {
      headY = Math.sin(t * .5) * .45 * motion; headX = Math.sin(t * .3) * .06 * motion
    }
    const to = (value: number, target: number) => value + (target - value) * ease
    this.leftArm.rotation.x = to(this.leftArm.rotation.x, left)
    this.rightArm.rotation.x = to(this.rightArm.rotation.x, right)
    this.rightArm.rotation.z = to(this.rightArm.rotation.z, rightZ)
    this.head.rotation.x = to(this.head.rotation.x, headX)
    this.head.rotation.y = to(this.head.rotation.y, headY)
    this.figure.rotation.x = to(this.figure.rotation.x, lean)
    this.torso.scale.y = 1 + Math.sin(t * 2.2) * .015 * motion
    this.label.position.y = LABEL_Y + (this.worker.attention ? Math.abs(Math.sin(t * 4)) * .08 * motion : 0)
  }
}

/** The 3D studio room: owns the renderer, camera controls, picking and the animation loop. */
export class StudioScene {
  private readonly renderer: THREE.WebGLRenderer
  private readonly labels = new CSS2DRenderer()
  private readonly scene = new THREE.Scene()
  private readonly camera = new THREE.PerspectiveCamera(38, 1, .1, 120)
  private readonly controls: OrbitControls
  private readonly room = new THREE.Group()
  private readonly stations = new Map<string, Station>()
  private readonly raycaster = new THREE.Raycaster()
  private readonly pointer = new THREE.Vector2()
  private readonly observer: ResizeObserver
  private readonly hemisphere = new THREE.HemisphereLight('#ffffff', '#b59a7a', 1.1)
  private readonly sun = new THREE.DirectionalLight('#fff4e0', 1.6)
  private board: { canvas: HTMLCanvasElement; texture: THREE.CanvasTexture } | null = null
  private layoutKey = ''
  private frame = 0
  private last = 0
  private down: { x: number; y: number } | null = null
  private readonly pointers = new Set<number>()
  private hover: PointerEvent | null = null
  private boardKey = ''
  private framedRows = 0
  private dark = false

  constructor(private readonly container: HTMLElement, private readonly onSelect: (id: string) => void, private readonly reducedMotion: boolean) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    this.renderer.shadowMap.enabled = true
    // r186 起 PCFSoftShadowMap 已移除，使用 PCFShadowMap。
    this.renderer.shadowMap.type = THREE.PCFShadowMap
    container.append(this.renderer.domElement)
    this.labels.domElement.className = 'scene-labels'
    container.append(this.labels.domElement)

    this.sun.castShadow = true
    this.sun.shadow.mapSize.set(2048, 2048)
    this.sun.shadow.bias = -.0005
    this.scene.add(this.hemisphere, this.sun, this.sun.target, this.room)

    this.controls = new OrbitControls(this.camera, this.renderer.domElement)
    Object.assign(this.controls, { enablePan: false, enableDamping: true, dampingFactor: .08, minPolarAngle: .35, maxPolarAngle: 1.3, minAzimuthAngle: -1.05, maxAzimuthAngle: 1.05 })

    const canvas = this.renderer.domElement
    canvas.addEventListener('pointerdown', this.pointerDown)
    canvas.addEventListener('pointerup', this.pointerUp)
    canvas.addEventListener('pointermove', this.pointerMove)
    canvas.addEventListener('pointercancel', this.pointerCancel)
    this.observer = new ResizeObserver(() => this.resize())
    this.observer.observe(container)
    this.resize()
    this.frame = requestAnimationFrame(this.loop)
  }

  /** Rebuild the room when the member set changes, otherwise only refresh each desk. */
  update(workers: SceneWorker[], title: string, progress: string, dark: boolean) {
    const key = `${workers.map(worker => `${worker.id}:${worker.name}:${worker.template}:${worker.color}`).join('|')}|${dark}`
    if (key !== this.layoutKey) {
      this.layoutKey = key
      this.dark = dark
      this.build(workers)
    } else for (const worker of workers) this.stations.get(worker.id)?.apply(worker)
    this.drawBoard(title, progress)
  }

  /** Return the camera to the default view. */
  resetView() { this.controls.reset() }

  dispose() {
    cancelAnimationFrame(this.frame)
    this.observer.disconnect()
    this.controls.dispose()
    const canvas = this.renderer.domElement
    canvas.removeEventListener('pointerdown', this.pointerDown)
    canvas.removeEventListener('pointerup', this.pointerUp)
    canvas.removeEventListener('pointermove', this.pointerMove)
    canvas.removeEventListener('pointercancel', this.pointerCancel)
    this.clear()
    // renderer.dispose 不释放阴影贴图与上下文，反复切换视图时需主动释放，避免 WebGL 上下文堆积。
    this.sun.dispose()
    this.renderer.dispose()
    this.renderer.forceContextLoss()
    canvas.remove()
    this.labels.domElement.remove()
  }

  private clear() {
    for (const station of this.stations.values()) station.label.removeFromParent()
    this.stations.clear()
    this.room.traverse(object => {
      if (object instanceof THREE.Mesh) {
        object.geometry.dispose()
        const materials = Array.isArray(object.material) ? object.material : [object.material]
        for (const material of materials) { (material as THREE.MeshStandardMaterial).map?.dispose(); material.dispose() }
      }
    })
    this.room.clear()
    this.board = null
    this.boardKey = ''
  }

  private build(workers: SceneWorker[]) {
    this.clear()
    const members = workers.filter(worker => worker.kind !== 'host')
    const rows = Math.max(1, Math.ceil(members.length / COLUMNS))
    const width = Math.max(COLUMNS * SPACING_X + 2, 16)
    const depth = 7 + rows * SPACING_Z
    const front = -depth / 2
    const palette = this.dark
      ? { floor: '#5a4632', wall: '#3a3f4c', trim: '#2c2f38', rug: '#3d4a5c', sky: '#28385a', background: '#15181d' }
      : { floor: '#c9a27a', wall: '#efe6d6', trim: '#a9835a', rug: '#d7e3d1', sky: '#bfe3f7', background: '#e9efe9' }
    this.scene.background = new THREE.Color(palette.background)
    this.hemisphere.intensity = this.dark ? .55 : 1.1
    this.sun.intensity = this.dark ? .9 : 1.6

    // 地板做成有厚度的底座，像一个剖开的房间模型。
    const floor = box(width, .4, depth, standard(palette.floor, { map: this.planks(palette.floor) }), 0, -.2, 0)
    floor.castShadow = false
    const wall = standard(palette.wall)
    const back = box(width, 4.2, .2, wall, 0, 2.1, front - .1)
    const side = box(.2, 4.2, depth, wall, -width / 2 - .1, 2.1, 0)
    const trim = standard(palette.trim)
    this.room.add(floor, back, side, box(width, .18, .06, trim, 0, .09, front + .03), box(.06, .18, depth, trim, -width / 2 + .03, .09, 0))
    const rug = new THREE.Mesh(new THREE.PlaneGeometry(COLUMNS * SPACING_X, rows * SPACING_Z), standard(palette.rug))
    rug.rotation.x = -Math.PI / 2
    rug.position.set(0, .005, front + 4.2 + rows * SPACING_Z / 2)
    rug.receiveShadow = true
    this.room.add(rug)

    // 侧墙上的两扇窗。
    for (const z of [front + 3, front + depth * .62]) {
      const glass = new THREE.Mesh(new THREE.PlaneGeometry(1.8, 1.3), new THREE.MeshBasicMaterial({ color: palette.sky }))
      glass.rotation.y = Math.PI / 2
      glass.position.set(-width / 2 + .01, 2.3, z)
      const frame = standard('#fff8ee')
      this.room.add(glass, box(.06, .08, 1.9, frame, -width / 2 + .03, 2.98, z), box(.06, .08, 1.9, frame, -width / 2 + .03, 1.62, z),
        box(.06, 1.4, .08, frame, -width / 2 + .03, 2.3, z), box(.06, .06, 1.9, frame, -width / 2 + .03, 2.3, z))
    }
    // 后墙：白板、书架、绿植和挂钟。
    const canvas = document.createElement('canvas')
    canvas.width = 1024; canvas.height = 400
    const texture = new THREE.CanvasTexture(canvas)
    texture.colorSpace = THREE.SRGBColorSpace
    this.board = { canvas, texture }
    const boardMesh = new THREE.Mesh(new THREE.PlaneGeometry(4.2, 1.64), new THREE.MeshBasicMaterial({ map: texture }))
    boardMesh.position.set(0, 2.45, front + .02)
    this.room.add(boardMesh, box(4.4, .1, .08, standard('#8d96a0'), 0, 1.6, front + .05), box(4.4, .08, .06, standard('#8d96a0'), 0, 3.3, front + .04))
    const shelf = new THREE.Group()
    shelf.add(box(1.6, 2.4, .5, standard('#8f6a44'), 0, 1.2, 0))
    const bookColors = ['#c0563b', '#5b6bbf', '#4f7d3a', '#d9a441', '#7a4a6e', '#3f8f8a']
    for (let level = 0; level < 3; level++) {
      shelf.add(box(1.5, .05, .46, standard('#b98b5c'), 0, .45 + level * .7, .03))
      for (let index = 0; index < 6; index++) shelf.add(box(.16, .36 + (index % 3) * .06, .3, standard(bookColors[(index + level) % 6]), -.55 + index * .22, .67 + level * .7, .05))
    }
    shelf.position.set(width / 2 - 1.6, 0, front + .3)
    const plant = new THREE.Group()
    plant.add(new THREE.Mesh(new THREE.CylinderGeometry(.28, .22, .5, 16), standard('#b5653d')).translateY(.25))
    for (const [x, y, z, r] of [[0, .85, 0, .35], [.18, 1.15, .05, .26], [-.16, 1.1, -.05, .24]]) plant.add(new THREE.Mesh(new THREE.IcosahedronGeometry(r, 0), standard('#4f9a55', { flatShading: true })).translateX(x).translateY(y).translateZ(z))
    plant.position.set(-width / 2 + .8, 0, front + .8)
    const clock = new THREE.Mesh(new THREE.CylinderGeometry(.3, .3, .06, 24), standard('#fbfbf8'))
    clock.rotation.x = Math.PI / 2
    clock.position.set(-3.6, 3, front + .05)
    this.room.add(shelf, plant, clock)
    for (const object of [shelf, plant]) object.traverse(child => { child.castShadow = true; child.receiveShadow = true })

    // 主持人站在白板前，成员按每行 4 个排开。
    const host = workers.find(worker => worker.kind === 'host')
    if (host) this.place(host, 0, front + 2.1)
    members.forEach((worker, index) => {
      const row = Math.floor(index / COLUMNS)
      const count = Math.min(COLUMNS, members.length - row * COLUMNS)
      this.place(worker, (index % COLUMNS - (count - 1) / 2) * SPACING_X, front + 5 + row * SPACING_Z)
    })

    this.sun.position.set(-width / 2 + 2, 11, front + depth * .2)
    this.sun.target.position.set(0, 0, 0)
    const shadow = this.sun.shadow.camera
    Object.assign(shadow, { left: -width / 2 - 2, right: width / 2 + 2, top: depth / 2 + 2, bottom: -depth / 2 - 2, near: 1, far: 40 })
    shadow.updateProjectionMatrix()
    // 只在首次或行数变化时重设视角，切换主题等重建不打断用户调好的视角。
    if (rows === this.framedRows) return
    this.framedRows = rows
    this.camera.position.set(0, 8 + rows, depth / 2 + 4 + rows * .5)
    this.controls.target.set(0, .9, front + depth * .55)
    this.controls.minDistance = 6
    this.controls.maxDistance = 18 + rows * 4
    this.controls.update()
    this.controls.saveState()
  }

  private place(worker: SceneWorker, x: number, z: number) {
    const station = new Station(worker, this.onSelect)
    station.root.position.set(x, 0, z)
    this.stations.set(worker.id, station)
    this.room.add(station.root)
  }

  /** Wooden plank texture drawn once per build. */
  private planks(color: string) {
    const canvas = document.createElement('canvas')
    canvas.width = 256; canvas.height = 256
    const context = canvas.getContext('2d')!
    context.fillStyle = color
    context.fillRect(0, 0, 256, 256)
    context.fillStyle = 'rgba(0,0,0,.12)'
    for (let x = 0; x < 256; x += 32) context.fillRect(x, 0, 2, 256)
    for (let index = 0; index < 8; index++) context.fillRect(index * 32, (index * 97) % 256, 32, 2)
    const texture = new THREE.CanvasTexture(canvas)
    texture.colorSpace = THREE.SRGBColorSpace
    texture.wrapS = texture.wrapT = THREE.RepeatWrapping
    texture.repeat.set(4, 4)
    return texture
  }

  private drawBoard(title: string, progress: string) {
    if (!this.board || this.boardKey === `${title}|${progress}`) return
    this.boardKey = `${title}|${progress}`
    const { canvas, texture } = this.board
    const context = canvas.getContext('2d')!
    context.fillStyle = '#fbfbf8'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.fillStyle = '#2f3542'
    context.textAlign = 'center'
    context.font = 'bold 84px system-ui, sans-serif'
    context.fillText(title.length > 14 ? `${title.slice(0, 13)}…` : title, canvas.width / 2, 170, canvas.width - 80)
    context.fillStyle = '#5b6470'
    context.font = '56px system-ui, sans-serif'
    context.fillText(progress, canvas.width / 2, 290, canvas.width - 80)
    texture.needsUpdate = true
  }

  private resize() {
    const { clientWidth: width, clientHeight: height } = this.container
    if (!width || !height) return
    this.camera.aspect = width / height
    // 保持约 60° 的水平视野，窄窗口下仍能看到整排工位。
    const fov = 2 * Math.atan(Math.tan(THREE.MathUtils.degToRad(30)) / this.camera.aspect)
    this.camera.fov = Math.min(75, Math.max(38, THREE.MathUtils.radToDeg(fov)))
    this.camera.updateProjectionMatrix()
    this.renderer.setSize(width, height)
    this.labels.setSize(width, height)
  }

  private loop = (now: number) => {
    this.frame = requestAnimationFrame(this.loop)
    // 页面隐藏或容器被 display:none（如打开设置页）时不渲染，节省资源。
    if (document.hidden || !this.container.clientWidth) return
    if (this.hover) {
      this.renderer.domElement.style.cursor = this.pick(this.hover) ? 'pointer' : 'grab'
      this.hover = null
    }
    const delta = this.last ? Math.min((now - this.last) / 1000, .1) : 0
    this.last = now
    const ease = 1 - Math.exp(-delta * 8)
    const motion = this.reducedMotion ? 0 : 1
    for (const station of this.stations.values()) station.tick(now / 1000, this.reducedMotion ? 1 : ease, motion)
    this.controls.update()
    this.renderer.render(this.scene, this.camera)
    this.labels.render(this.scene, this.camera)
  }

  private pick(event: PointerEvent) {
    const rect = this.renderer.domElement.getBoundingClientRect()
    this.pointer.set(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1)
    // 不依赖上一帧渲染留下的矩阵（例如页面在后台时没有渲染）。
    this.scene.updateMatrixWorld()
    this.camera.updateMatrixWorld()
    this.raycaster.setFromCamera(this.pointer, this.camera)
    // Raycaster 不看 visible，需跳过隐藏的人物和不可见的选中光环。
    for (const hit of this.raycaster.intersectObjects([...this.stations.values()].map(station => station.root), true)) {
      if (hit.object.userData.ignorePick) continue
      let object: THREE.Object3D | null = hit.object
      let visible = true
      while (object && !object.userData.id) { visible &&= object.visible; object = object.parent }
      if (object && visible) return object.userData.id as string
    }
    return null
  }

  // 多指（双指缩放）期间不判定点击。
  private pointerDown = (event: PointerEvent) => {
    this.pointers.add(event.pointerId)
    this.down = this.pointers.size === 1 ? { x: event.clientX, y: event.clientY } : null
  }
  // 只把几乎没有移动的按下抬起当作点击，拖动用于旋转视角。
  private pointerUp = (event: PointerEvent) => {
    this.pointers.delete(event.pointerId)
    const start = this.down
    this.down = null
    if (!start || Math.hypot(event.clientX - start.x, event.clientY - start.y) > 5) return
    const id = this.pick(event)
    if (id) this.onSelect(id)
  }
  private pointerCancel = (event: PointerEvent) => { this.pointers.delete(event.pointerId); this.down = null }
  // 悬停检测放到下一帧统一做，避免每次移动都做射线检测。
  private pointerMove = (event: PointerEvent) => { if (!event.buttons) this.hover = event }
}

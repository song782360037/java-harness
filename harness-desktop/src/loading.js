(() => {
  const params = new URLSearchParams(window.location.search)
  const animate = params.get('animate') !== '0'
  const rawStyle = params.get('style')
  const style = rawStyle === 'prism' || rawStyle === 'terminal' ? rawStyle : 'orbital'
  const root = document.documentElement
  root.dataset.animate = animate ? '1' : '0'
  root.dataset.style = style

  const reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (!animate || reduced) return

  const stages = style === 'terminal'
    ? ['[BOOT] 唤醒本地 Java 21 运行时…', '[INIT] 挂载工作区与本地数据索引…', '[LINK] 正在连接本地服务 127.0.0.1…']
    : ['正在唤醒本地 Java 内核…', '加载模型、扩展与长期记忆…', '正在连接本地工作台…']

  const statusNode = document.getElementById('status-text')
  let stageIndex = 0
  if (statusNode) {
    statusNode.textContent = stages[0]
    window.setInterval(() => {
      stageIndex = Math.min(stageIndex + 1, stages.length - 1)
      statusNode.textContent = stages[stageIndex]
    }, 1200)
  }

  const canvas = document.getElementById('fx')
  if (!(canvas instanceof HTMLCanvasElement)) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  let width = 0
  let height = 0
  let dpr = 1
  const particles = []
  const count = 44

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 2)
    width = window.innerWidth
    height = window.innerHeight
    canvas.width = Math.floor(width * dpr)
    canvas.height = Math.floor(height * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }

  function spawn(initial) {
    const angle = Math.random() * Math.PI * 2
    const maxRadius = Math.hypot(width, height) * 0.56
    const radius = initial ? 90 + Math.random() * maxRadius : maxRadius * (0.72 + Math.random() * 0.35)
    return {
      angle,
      radius,
      speed: 0.25 + Math.random() * 0.55,
      spin: (Math.random() - 0.5) * 0.0045,
      size: 1.1 + Math.random() * 2.1,
      alpha: 0.2 + Math.random() * 0.55,
    }
  }

  resize()
  for (let i = 0; i < count; i++) particles.push(spawn(true))
  window.addEventListener('resize', resize)

  function frame() {
    ctx.clearRect(0, 0, width, height)
    const cx = width * 0.5
    const cy = height * 0.46
    for (let i = 0; i < particles.length; i++) {
      const p = particles[i]
      p.radius -= p.speed
      p.angle += p.spin
      if (p.radius < 54) {
        particles[i] = spawn(false)
        continue
      }
      const x = cx + Math.cos(p.angle) * p.radius
      const y = cy + Math.sin(p.angle) * p.radius
      const edgeFade = Math.min(1, (p.radius - 54) / 70)
      ctx.beginPath()
      ctx.arc(x, y, p.size, 0, Math.PI * 2)
      ctx.fillStyle = `rgba(141, 217, 182, ${(p.alpha * edgeFade).toFixed(3)})`
      ctx.fill()
    }
    window.requestAnimationFrame(frame)
  }
  window.requestAnimationFrame(frame)
})()

// 桌面壳主进程：拉起内置 JRE 运行的后端 JAR，就绪后在窗口中加载工作台；后端随窗口退出而关闭。
import { app, BrowserWindow, dialog, Menu, shell } from 'electron'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const STARTUP_TIMEOUT_MS = 120_000
const SHUTDOWN_TIMEOUT_MS = 30_000
const MAX_LOG_BYTES = 10 * 1024 * 1024

let mainWindow = null
let backend = null
let backendReady = false
let backendUrl = null
let quitting = false
let failed = false

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (!mainWindow) return
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.focus()
  })
  app.whenReady().then(start)
}

/** 启动顺序：先显示加载页，再启动（或连接开发用）后端，就绪后切到工作台。 */
async function start() {
  Menu.setApplicationMenu(null)
  mainWindow = createWindow()
  try {
    backendUrl = process.env.HARNESS_DESKTOP_BACKEND_URL || (await startBackend())
    await waitForHealth(backendUrl)
    backendReady = true
    await mainWindow.loadURL(backendUrl)
  } catch (error) {
    fatal('Java Harness 启动失败', error.message)
  }
}

/** 弹出一次错误提示并退出；退出过程中或已提示过时不再重复。 */
function fatal(title, message) {
  if (quitting || failed) return
  failed = true
  dialog.showErrorBox(title, `${message}\n\n日志目录：${logDirectory()}`)
  app.quit()
}

function createWindow() {
  const window = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 960,
    minHeight: 640,
    title: 'Java Harness',
    backgroundColor: '#252A28',
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  })
  // 开发模式下后端可能立即就绪，loadURL 会中断加载页，忽略这次中断。
  window.loadFile(path.join(here, 'loading.html')).catch(() => {})
  // 只允许停留在本地后端页面，其他链接交给系统浏览器。
  window.webContents.setWindowOpenHandler(({ url }) => {
    openExternal(url)
    return { action: 'deny' }
  })
  window.webContents.on('will-navigate', (event, url) => {
    if (backendUrl && new URL(url).origin === new URL(backendUrl).origin) return
    event.preventDefault()
    openExternal(url)
  })
  // Windows 关机或注销时不会触发 before-quit，尽早通知后端开始关闭。
  window.on('session-end', () => backend?.stdin.end())
  window.on('closed', () => {
    mainWindow = null
  })
  return window
}

function openExternal(url) {
  if (/^https?:\/\//i.test(url)) shell.openExternal(url)
}

/** 用内置 JRE 拉起后端，数据写到用户目录；stdin 管道保持打开，关闭即通知后端退出。 */
async function startBackend() {
  const resources = app.isPackaged ? path.join(process.resourcesPath, 'backend') : path.join(here, '..', 'build', 'backend')
  const jar = path.join(resources, 'harness-server.jar')
  if (!fs.existsSync(jar)) throw new Error(`未找到后端程序：${jar}\n请先执行 npm run prepare:backend`)
  const bundledJava = path.join(resources, 'runtime', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  if (app.isPackaged && !fs.existsSync(bundledJava)) throw new Error(`未找到内置 Java 运行时：${bundledJava}`)
  const java = fs.existsSync(bundledJava) ? bundledJava : 'java'

  const home = app.getPath('userData')
  const data = path.join(home, 'data')
  fs.mkdirSync(data, { recursive: true })
  const log = openLog()

  const port = await freePort()
  const h2File = path.join(data, 'harness').replaceAll('\\', '/')
  // 配置走命令行参数而不是环境变量，避免被 Agent 执行的命令继承（例如覆盖用户项目的数据源）。
  const args = [
    '-jar', jar,
    `--server.port=${port}`,
    `--spring.datasource.url=jdbc:h2:file:${h2File};DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0`,
    `--harness.plugins.directory=${path.join(data, 'plugins')}`,
    `--harness.settings.key-file=${path.join(data, 'settings.key')}`,
    '--harness.desktop.exit-with-parent=true',
  ]
  // 工作目录放在用户目录，兜底其余相对路径，避免写入只读的安装目录。
  backend = spawn(java, args, { cwd: home, stdio: ['pipe', log, log], windowsHide: true })
  fs.closeSync(log)
  backend.stdin.on('error', () => {
    // 后端已退出时关闭管道可能报 EPIPE，退出由 exit 事件处理。
  })
  backend.on('error', (error) => {
    backend = null
    fatal('Java Harness 后端无法启动', error.message)
  })
  backend.on('exit', (code, signal) => {
    backend = null
    fatal('Java Harness 后端已退出', `退出码：${code ?? signal}`)
  })
  return `http://127.0.0.1:${port}`
}

function logDirectory() {
  return path.join(app.getPath('userData'), 'logs')
}

/** 打开后端日志；超过上限时先轮转为 backend.log.1。 */
function openLog() {
  fs.mkdirSync(logDirectory(), { recursive: true })
  const file = path.join(logDirectory(), 'backend.log')
  if (fs.existsSync(file) && fs.statSync(file).size > MAX_LOG_BYTES) fs.renameSync(file, `${file}.1`)
  return fs.openSync(file, 'a')
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      server.close(() => resolve(port))
    })
  })
}

async function waitForHealth(url) {
  const deadline = Date.now() + STARTUP_TIMEOUT_MS
  while (Date.now() < deadline) {
    if (quitting) throw new Error('应用正在退出')
    if (!process.env.HARNESS_DESKTOP_BACKEND_URL && !backend) throw new Error('后端进程启动后立即退出')
    try {
      const response = await fetch(`${url}/actuator/health`)
      if (response.ok) return
    } catch {
      // 后端尚未监听端口，继续等待。
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`后端在 ${STARTUP_TIMEOUT_MS / 1000} 秒内未就绪`)
}

app.on('window-all-closed', () => app.quit())

// 先关闭 stdin 让后端正常关闭（释放 H2 文件锁），超时再强制结束。
// 后端在就绪后才开始监听 stdin，启动中退出时要多等一个启动超时，避免打断数据库迁移。
app.on('before-quit', (event) => {
  if (!backend) return
  event.preventDefault()
  if (quitting) return
  quitting = true
  const child = backend
  const timeout = backendReady ? SHUTDOWN_TIMEOUT_MS : STARTUP_TIMEOUT_MS + SHUTDOWN_TIMEOUT_MS
  const timer = setTimeout(() => child.kill(), timeout)
  child.once('exit', () => {
    clearTimeout(timer)
    app.quit()
  })
  child.stdin.end()
})

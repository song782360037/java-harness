// 准备桌面打包所需的后端资源：构建前端与后端 JAR、按依赖裁剪内置 JRE、生成应用图标。
// 需要 JAVA_HOME 指向 JDK 21+（使用其中的 jar、jdeps、jlink）。
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { writeIcon } from './icon.mjs'

const desktop = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const root = path.resolve(desktop, '..')
const web = path.join(root, 'harness-web')
const output = path.join(desktop, 'build', 'backend')
const windows = process.platform === 'win32'

// jdeps 无法发现的运行期模块：TLS 椭圆曲线、Windows 系统证书库、PKCS#11、中文等区域数据、字符集、ZIP 文件系统、DNS 解析，
// 以及插件以 "java" 命令启动时也会用到本运行时，补上常用的内置 HTTP 服务。
const EXTRA_MODULES = [
  'jdk.crypto.ec', 'jdk.crypto.mscapi', 'jdk.crypto.cryptoki', 'jdk.localedata', 'jdk.charsets',
  'jdk.zipfs', 'jdk.naming.dns', 'jdk.httpserver',
]

const jdk = process.env.JAVA_HOME
const tool = (name) => path.join(jdk, 'bin', windows ? `${name}.exe` : name)
if (!jdk || !fs.existsSync(tool('jlink'))) fail('JAVA_HOME 需要指向 JDK 21 或更高版本（包含 jlink）')

if (!fs.existsSync(path.join(web, 'node_modules'))) run('npm', ['ci'], web)
run('npm', ['run', 'build'], web)
run(path.join(root, windows ? 'mvnw.cmd' : 'mvnw'), ['-q', '-Pweb', '-pl', 'harness-server', '-am', '-DskipTests', 'package'], root)

const target = path.join(root, 'harness-server', 'target')
const jarName = fs.existsSync(target)
  ? fs.readdirSync(target).find((name) => /^harness-server-[\d.]+(-SNAPSHOT)?\.jar$/.test(name))
  : undefined
if (!jarName) fail(`未在 ${target} 找到后端 JAR`)

fs.rmSync(output, { recursive: true, force: true })
fs.mkdirSync(output, { recursive: true })
const jar = path.join(output, 'harness-server.jar')
fs.copyFileSync(path.join(target, jarName), jar)

const modules = [...new Set([...dependencies(jar), ...EXTRA_MODULES])].sort()
console.log(`内置 JRE 模块：${modules.join(',')}`)
run(tool('jlink'), [
  '--add-modules', modules.join(','),
  '--strip-debug', '--no-man-pages', '--no-header-files', '--compress=zip-6',
  '--output', path.join(output, 'runtime'),
], root)

writeIcon(path.join(desktop, 'build', 'icon.ico'))
console.log(`后端资源已就绪：${output}`)

/** 解压 Spring Boot JAR，用 jdeps 分析应用类与全部依赖库实际用到的 JDK 模块。 */
function dependencies(bootJar) {
  const work = path.join(desktop, 'build', '.jdeps')
  fs.rmSync(work, { recursive: true, force: true })
  fs.mkdirSync(work, { recursive: true })
  try {
    run(tool('jar'), ['xf', bootJar], work)
    const libs = fs.readdirSync(path.join(work, 'BOOT-INF', 'lib'))
      .filter((name) => name.endsWith('.jar'))
      .map((name) => path.join('BOOT-INF', 'lib', name))
    const result = spawnSync(tool('jdeps'), [
      '--ignore-missing-deps', '--print-module-deps', '--multi-release', '21',
      '--class-path', libs.join(path.delimiter),
      path.join('BOOT-INF', 'classes'), ...libs,
    ], { cwd: work, encoding: 'utf8' })
    if (result.status !== 0) fail(`jdeps 分析失败：\n${result.stderr || result.stdout}`)
    const line = result.stdout.trim().split(/\r?\n/).pop()
    return line.split(',').map((name) => name.trim()).filter(Boolean)
  } finally {
    fs.rmSync(work, { recursive: true, force: true })
  }
}

function run(command, args, cwd) {
  // Windows 上 npm、mvnw 是 .cmd 脚本，需要经由 shell 启动；参数均不含空格以外的特殊字符。
  const shell = windows && !command.endsWith('.exe')
  // 不带路径的命令名不能加引号，否则 cmd 会把 npm.cmd 的 %~dp0 解析成当前目录。
  const quote = (value) => (shell && /\s/.test(value) ? `"${value}"` : value)
  const result = spawnSync(quote(command), args.map(quote), { cwd, stdio: 'inherit', shell })
  if (result.status !== 0) fail(`命令执行失败（${result.status}）：${command} ${args.join(' ')}`)
}

function fail(message) {
  console.error(message)
  process.exit(1)
}

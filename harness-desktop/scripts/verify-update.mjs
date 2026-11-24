import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'
import { readUpdatePolicy } from '../src/update-policy.mjs'

/** 发布前核对 Windows 更新元数据引用的安装包、哈希、大小与差分文件。 */
export async function verifyUpdateArtifacts(directory, version, requireSignature = false) {
  const metadata = yaml.load(await fs.readFile(path.join(directory, 'latest.yml'), 'utf8'))
  const expected = `Java-Harness-Setup-${version}-x64.exe`
  if (metadata.version !== version || metadata.path !== expected || metadata.files?.length !== 1 || metadata.files[0].url !== expected) throw new Error('更新版本或安装包文件名与发布配置不一致')
  const file = await fs.readFile(path.join(directory, expected))
  const sha512 = createHash('sha512').update(file).digest('base64')
  if (metadata.sha512 !== sha512 || metadata.files[0].sha512 !== sha512 || metadata.files[0].size !== file.length) throw new Error('安装包哈希或大小与更新元数据不一致')
  await fs.access(path.join(directory, `${expected}.blockmap`))
  if (requireSignature) {
    const resources = path.join(directory, 'win-unpacked', 'resources')
    const policy = readUpdatePolicy(resources, { packaged: true, platform: 'win32', arch: 'x64', externalBackend: false })
    if (!policy.supported) throw new Error(policy.disabledReason)
    const config = yaml.load(await fs.readFile(path.join(resources, 'app-update.yml'), 'utf8'))
    if (config.publisherName == null) throw new Error('安装包未配置签名发布者')
  }
}

/** 发布前核对 Linux 更新元数据引用的 AppImage（及附带 .deb 等条目）哈希、大小与运行期配置。 */
export async function verifyLinuxUpdateArtifacts(directory, version) {
  const metadata = yaml.load(await fs.readFile(path.join(directory, 'latest-linux.yml'), 'utf8'))
  const expected = `Java-Harness-${version}-linux-x64.AppImage`
  if (metadata.version !== version || !Array.isArray(metadata.files) || metadata.files.length === 0) {
    throw new Error('Linux 更新版本或文件清单无效')
  }
  const appImageEntry = metadata.files.find(entry => entry.url === expected)
  if (!appImageEntry) throw new Error('Linux 更新元数据缺少预期的 AppImage 条目')
  for (const entry of metadata.files) {
    if (!entry?.url || path.basename(entry.url) !== entry.url) throw new Error(`Linux 更新元数据包含无效文件名：${entry?.url}`)
    const file = await fs.readFile(path.join(directory, entry.url))
    const sha512 = createHash('sha512').update(file).digest('base64')
    if (entry.sha512 !== sha512 || entry.size !== file.length) throw new Error(`Linux 安装包哈希或大小与更新元数据不一致：${entry.url}`)
  }
  if (metadata.path && metadata.sha512) {
    const primary = metadata.files.find(entry => entry.url === metadata.path)
    if (!primary || primary.sha512 !== metadata.sha512) throw new Error('Linux 更新元数据主文件哈希不一致')
  }
  const resources = path.join(directory, 'linux-unpacked', 'resources')
  const policy = readUpdatePolicy(resources, { packaged: true, platform: 'linux', arch: 'x64', externalBackend: false, appImage: true })
  if (!policy.supported) throw new Error(policy.disabledReason)
}

function readOption(flag) {
  const index = process.argv.indexOf(flag)
  return index >= 0 && index + 1 < process.argv.length ? process.argv[index + 1] : ''
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const desktop = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const { version } = JSON.parse(await fs.readFile(path.join(desktop, 'package.json'), 'utf8'))
  const platform = readOption('--platform') || 'win'
  if (platform === 'linux') {
    await verifyLinuxUpdateArtifacts(path.join(desktop, 'dist'), version)
  } else if (platform === 'win') {
    await verifyUpdateArtifacts(path.join(desktop, 'dist'), version, process.argv.includes('--require-signature'))
  } else {
    throw new Error(`不支持的校验平台：${platform}`)
  }
  console.log(`更新产物校验通过（${platform}）：${version}`)
}

import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'
import { readUpdatePolicy } from '../src/update-policy.mjs'

/** 发布前核对更新元数据引用的安装包、哈希、大小与差分文件。 */
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

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const desktop = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const { version } = JSON.parse(await fs.readFile(path.join(desktop, 'package.json'), 'utf8'))
  await verifyUpdateArtifacts(path.join(desktop, 'dist'), version, process.argv.includes('--require-signature'))
  console.log(`更新产物校验通过：${version}`)
}

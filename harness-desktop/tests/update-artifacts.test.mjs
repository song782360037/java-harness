import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { createHash } from 'node:crypto'
import yaml from 'js-yaml'
import { verifyLinuxUpdateArtifacts, verifyUpdateArtifacts } from '../scripts/verify-update.mjs'
import { extraModules } from '../scripts/prepare-backend.mjs'
import { writeIcons } from '../scripts/icon.mjs'
import { readUpdatePolicy } from '../src/update-policy.mjs'

test('release validation rejects mismatched installer, missing blockmap and changed hash', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'harness-release-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const name = 'Java-Harness-Setup-1.1.0-x64.exe'
  const file = Buffer.from('installer')
  const sha512 = createHash('sha512').update(file).digest('base64')
  const metadata = { version: '1.1.0', path: name, sha512, files: [{ url: name, sha512, size: file.length }] }
  await fs.writeFile(path.join(directory, 'latest.yml'), yaml.dump(metadata))
  await fs.writeFile(path.join(directory, name), file)
  await assert.rejects(verifyUpdateArtifacts(directory, '1.1.0'))
  await fs.writeFile(path.join(directory, `${name}.blockmap`), 'blockmap')
  await verifyUpdateArtifacts(directory, '1.1.0')
  const resources = path.join(directory, 'win-unpacked', 'resources')
  await fs.mkdir(resources, { recursive: true })
  const config = { provider: 'github', owner: 'song782360037', repo: 'java-harness' }
  await fs.writeFile(path.join(resources, 'app-update.yml'), yaml.dump(config))
  await assert.rejects(verifyUpdateArtifacts(directory, '1.1.0', true), /未配置签名发布者/)
  config.publisherName = ['Java Harness Publisher']
  await fs.writeFile(path.join(resources, 'app-update.yml'), yaml.dump(config))
  await verifyUpdateArtifacts(directory, '1.1.0', true)
  await assert.rejects(verifyUpdateArtifacts(directory, '1.2.0'))
  await fs.writeFile(path.join(directory, name), 'tampered')
  await assert.rejects(verifyUpdateArtifacts(directory, '1.1.0'), /哈希/)
})

test('linux release validation checks AppImage and deb entries in latest-linux.yml', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'harness-linux-release-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const appImageName = 'Java-Harness-1.1.0-linux-x64.AppImage'
  const debName = 'Java-Harness-1.1.0-linux-x64.deb'
  const appImage = Buffer.from('appimage-binary')
  const deb = Buffer.from('deb-package')
  const appImageSha = createHash('sha512').update(appImage).digest('base64')
  const debSha = createHash('sha512').update(deb).digest('base64')
  const metadata = {
    version: '1.1.0',
    path: appImageName,
    sha512: appImageSha,
    files: [
      { url: appImageName, sha512: appImageSha, size: appImage.length },
      { url: debName, sha512: debSha, size: deb.length },
    ],
  }
  await fs.writeFile(path.join(directory, 'latest-linux.yml'), yaml.dump(metadata))
  await fs.writeFile(path.join(directory, appImageName), appImage)
  await fs.writeFile(path.join(directory, debName), deb)
  await assert.rejects(verifyLinuxUpdateArtifacts(directory, '1.1.0'), /缺少有效的更新配置/)
  const resources = path.join(directory, 'linux-unpacked', 'resources')
  await fs.mkdir(resources, { recursive: true })
  await fs.writeFile(path.join(resources, 'app-update.yml'), yaml.dump({ provider: 'github', owner: 'song782360037', repo: 'java-harness' }))
  await verifyLinuxUpdateArtifacts(directory, '1.1.0')
  await assert.rejects(verifyLinuxUpdateArtifacts(directory, '1.2.0'))
  await fs.writeFile(path.join(directory, debName), 'tampered-deb')
  await assert.rejects(verifyLinuxUpdateArtifacts(directory, '1.1.0'), /哈希/)
})

test('unsigned builds allow updates on Windows x64 and Linux x64 AppImage while blocking macOS, non-AppImage Linux and invalid configs', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'harness-policy-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const options = { packaged: true, platform: 'win32', arch: 'x64', externalBackend: false }
  assert.equal(readUpdatePolicy(directory, options).supported, false)
  const config = { provider: 'github', owner: 'song782360037', repo: 'java-harness' }
  await fs.writeFile(path.join(directory, 'app-update.yml'), yaml.dump(config))
  assert.equal(readUpdatePolicy(directory, options).supported, true)
  assert.equal(readUpdatePolicy(directory, { ...options, packaged: false }).supported, false)
  assert.equal(readUpdatePolicy(directory, { ...options, platform: 'linux' }).supported, false)
  assert.equal(readUpdatePolicy(directory, { ...options, platform: 'linux', appImage: true }).supported, true)
  assert.equal(readUpdatePolicy(directory, { ...options, platform: 'linux', arch: 'arm64', appImage: true }).supported, false)
  assert.equal(readUpdatePolicy(directory, { ...options, platform: 'darwin', arch: 'arm64' }).supported, false)
  assert.equal(readUpdatePolicy(directory, { ...options, platform: 'darwin', arch: 'x64' }).supported, false)
  assert.equal(readUpdatePolicy(directory, { ...options, arch: 'arm64' }).supported, false)
  config.publisherName = ['']
  await fs.writeFile(path.join(directory, 'app-update.yml'), yaml.dump(config))
  assert.equal(readUpdatePolicy(directory, options).supported, false)
  config.publisherName = ['Java Harness Publisher']
  await fs.writeFile(path.join(directory, 'app-update.yml'), yaml.dump(config))
  assert.equal(readUpdatePolicy(directory, options).supported, true)
  assert.equal(readUpdatePolicy(directory, { ...options, externalBackend: true }).supported, false)
  config.owner = 'foreign-owner'
  await fs.writeFile(path.join(directory, 'app-update.yml'), yaml.dump(config))
  assert.equal(readUpdatePolicy(directory, options).supported, false)
})

test('extraModules includes jdk.crypto.mscapi only on Windows', () => {
  assert.ok(extraModules('win32').includes('jdk.crypto.mscapi'))
  assert.ok(!extraModules('darwin').includes('jdk.crypto.mscapi'))
  assert.ok(!extraModules('linux').includes('jdk.crypto.mscapi'))
  assert.ok(extraModules('darwin').includes('jdk.crypto.ec'))
  assert.ok(extraModules('linux').includes('jdk.crypto.ec'))
})

test('writeIcons generates valid ICO, ICNS and Linux PNG icon set', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'harness-icons-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  writeIcons(directory)
  const ico = await fs.readFile(path.join(directory, 'icon.ico'))
  assert.equal(ico.readUInt16LE(0), 0)
  assert.equal(ico.readUInt16LE(2), 1)
  const icns = await fs.readFile(path.join(directory, 'icon.icns'))
  assert.equal(icns.subarray(0, 4).toString('ascii'), 'icns')
  assert.equal(icns.readUInt32BE(4), icns.length)
  assert.equal(icns.subarray(8, 12).toString('ascii'), 'ic08')
  const png256 = await fs.readFile(path.join(directory, 'icons', '256x256.png'))
  const png512 = await fs.readFile(path.join(directory, 'icons', '512x512.png'))
  assert.equal(png256.readUInt32BE(16), 256)
  assert.equal(png512.readUInt32BE(16), 512)
})

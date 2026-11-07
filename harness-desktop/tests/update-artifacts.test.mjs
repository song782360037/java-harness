import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { createHash } from 'node:crypto'
import yaml from 'js-yaml'
import { verifyUpdateArtifacts } from '../scripts/verify-update.mjs'
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

test('unsigned builds allow updates while missing, invalid and foreign configurations stay blocked', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'harness-policy-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const options = { packaged: true, platform: 'win32', arch: 'x64', externalBackend: false }
  assert.equal(readUpdatePolicy(directory, options).supported, false)
  const config = { provider: 'github', owner: 'song782360037', repo: 'java-harness' }
  await fs.writeFile(path.join(directory, 'app-update.yml'), yaml.dump(config))
  assert.equal(readUpdatePolicy(directory, options).supported, true)
  assert.equal(readUpdatePolicy(directory, { ...options, packaged: false }).supported, false)
  assert.equal(readUpdatePolicy(directory, { ...options, platform: 'linux' }).supported, false)
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

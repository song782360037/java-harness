import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { EventEmitter } from 'node:events'
import { backupUpdateData, closeBackendForUpdate } from '../src/update-backup.mjs'

test('backend must exit cleanly; timeout never kills it', async () => {
  for (const outcome of ['clean', 'error', 'timeout']) {
    const child = new EventEmitter()
    child.exitCode = null
    child.signalCode = null
    let killed = false
    child.kill = () => { killed = true }
    child.stdin = { end() { if (outcome !== 'timeout') queueMicrotask(() => child.emit('exit', outcome === 'clean' ? 0 : 1, null)) } }
    const closing = closeBackendForUpdate(child, 10)
    if (outcome === 'clean') await closing
    else await assert.rejects(closing)
    assert.equal(killed, false)
    assert.equal(child.listenerCount('exit'), 0)
  }
  await assert.rejects(closeBackendForUpdate(null))
})

test('backup retains database and key while preserving user plugins', async t => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'harness-update-'))
  t.after(() => fs.rm(home, { recursive: true, force: true }))
  await fs.mkdir(path.join(home, 'data', 'plugins'), { recursive: true })
  await fs.writeFile(path.join(home, 'data', 'harness.mv.db'), 'database')
  await fs.writeFile(path.join(home, 'data', 'settings.key'), 'secret-key')
  await fs.writeFile(path.join(home, 'data', 'plugins', 'user-plugin.jar'), 'plugin')
  const destination = await backupUpdateData(home, '1.0.0', '1.1.0')
  assert.equal(await fs.readFile(path.join(destination, 'harness.mv.db'), 'utf8'), 'database')
  assert.equal(await fs.readFile(path.join(destination, 'settings.key'), 'utf8'), 'secret-key')
  const metadata = JSON.parse(await fs.readFile(path.join(destination, 'update.json'), 'utf8'))
  assert.equal(metadata.toVersion, '1.1.0')
  assert.equal(await fs.readFile(path.join(home, 'data', 'plugins', 'user-plugin.jar'), 'utf8'), 'plugin')
})

test('failed backup leaves no completed or partial backup', async t => {
  const home = await fs.mkdtemp(path.join(os.tmpdir(), 'harness-update-'))
  t.after(() => fs.rm(home, { recursive: true, force: true }))
  await fs.mkdir(path.join(home, 'data'))
  await assert.rejects(backupUpdateData(home, '1.0.0', '1.1.0'))
  assert.deepEqual(await fs.readdir(path.join(home, 'backups')), [])
})

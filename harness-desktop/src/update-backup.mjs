import fs from 'node:fs/promises'
import path from 'node:path'

/** 等待后端正常退出；超时或异常退出均禁止继续安装，不调用 kill。 */
export function closeBackendForUpdate(child, timeout = 30_000) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return Promise.reject(new Error('后端已退出，请重启当前版本后重试'))
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.removeListener('exit', exited)
      reject(new Error('后端未在 30 秒内正常退出，安装未开始；请重启当前版本后重试'))
    }, timeout)
    const exited = (code, signal) => {
      clearTimeout(timer)
      if (code === 0 && !signal) resolve()
      else reject(new Error('后端未正常退出，安装未开始；请重启当前版本后重试'))
    }
    child.once('exit', exited)
    child.stdin.end()
  })
}

/** Java 正常退出后备份数据库与密钥；元数据仅在全部复制成功后写入。 */
export async function backupUpdateData(home, fromVersion, toVersion) {
  const parent = path.join(home, 'backups')
  await fs.mkdir(parent, { recursive: true })
  const destination = await fs.mkdtemp(path.join(parent, 'pre-update-'))
  try {
    const source = path.join(home, 'data')
    const names = await fs.readdir(source)
    const files = names.filter(name => name === 'settings.key' || /^harness\.(mv\.db|trace\.db)$/.test(name))
    if (!files.includes('harness.mv.db')) throw new Error('未找到本地数据库，已停止安装')
    for (const name of files) await fs.copyFile(path.join(source, name), path.join(destination, name))
    await fs.writeFile(path.join(destination, 'update.json'), JSON.stringify({ fromVersion, toVersion, createdAt: new Date().toISOString(), files }, null, 2), 'utf8')
    return destination
  } catch (error) {
    await fs.rm(destination, { recursive: true, force: true })
    throw error
  }
}

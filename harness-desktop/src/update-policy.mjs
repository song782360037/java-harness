import fs from 'node:fs'
import path from 'node:path'
import yaml from 'js-yaml'

/** 自动安装只用于内置后端和固定发布源；Windows x64 与 Linux x64 AppImage 允许未签名安装包。 */
export function readUpdatePolicy(resources, { packaged, platform, arch, externalBackend, appImage = false }) {
  const disabled = disabledReason => ({ supported: false, disabledReason })
  if (!packaged || externalBackend) return disabled('开发模式或外接后端未启用自动更新')
  if (platform === 'darwin') return disabled('macOS 暂未启用应用内自动更新，请前往 GitHub Release 下载新版 DMG')
  if (platform === 'linux') {
    if (arch !== 'x64' || !appImage) return disabled('Linux 自动更新仅支持 x64 AppImage，请前往 GitHub Release 下载新版')
  } else if (platform !== 'win32' || arch !== 'x64') {
    return disabled('当前仅支持 Windows x64 与 Linux x64 AppImage 自动更新')
  }
  try {
    const config = yaml.load(fs.readFileSync(path.join(resources, 'app-update.yml'), 'utf8'))
    if (config?.provider !== 'github' || config.owner !== 'song782360037' || config.repo !== 'java-harness' || config.host && config.host !== 'github.com') return disabled('更新源配置无效，请重新安装正式版本')
    if (config.publisherName != null) {
      const publishers = typeof config.publisherName === 'string' ? [config.publisherName] : config.publisherName
      if (!Array.isArray(publishers) || !publishers.length || publishers.some(value => typeof value !== 'string' || !value.trim())) return disabled('更新签名发布者配置无效，请重新安装正式版本')
    }
    return { supported: true, disabledReason: '' }
  } catch {
    return disabled('缺少有效的更新配置，请重新安装正式版本')
  }
}

export interface PluginCommand { id: string; description: string }
type CommandRequest = <T>(path: string) => Promise<T>

// 仅补全消息开头的命令，保留普通路径、正文斜杠和已有参数的输入行为。
export function slashCommandQuery(text: string, start: number, end: number): string | null {
  if (start !== end || start < 1 || start > text.length || !text.startsWith('/')) return null
  const prefix = text.slice(1, start)
  return /[\s/]/.test(prefix) ? null : prefix
}

// 搜索完整命令标识和说明，仍向后端提交原有命名空间命令。
export function matchingPluginCommands(commands: PluginCommand[], query: string): PluginCommand[] {
  const value = query.toLocaleLowerCase()
  return commands.filter(command => `${command.id} ${command.description}`.toLocaleLowerCase().includes(value))
}

// 只替换第一个命令标记，不重复添加斜杠或丢失后面的任务要求。
export function completePluginCommand(text: string, id: string): { text: string; caret: number } {
  const end = text.search(/\s/)
  const suffix = end < 0 ? ' ' : text.slice(end)
  const prefix = `/${id}`
  return { text: prefix + suffix, caret: prefix.length + 1 }
}

// 已有会话按生效范围读取；首次对话复用用户绑定，浏览菜单不创建会话或启动工具。
export async function fetchPluginCommands(request: CommandRequest, sessionId?: string): Promise<PluginCommand[]> {
  if (sessionId) return request<PluginCommand[]>(`/sessions/${sessionId}/plugin-commands`)
  const bindings = await request<{ installationId: string; name: string; enabled: boolean }[]>('/plugin-bindings?scope=user')
  const values = await Promise.all(bindings.filter(binding => binding.enabled).map(async binding => {
    const detail = await request<{ commands: PluginCommand[] }>(`/plugins/${binding.installationId}`)
    return detail.commands.map(command => ({ ...command, id: `${binding.name}:${command.id}` }))
  }))
  return values.flat()
}

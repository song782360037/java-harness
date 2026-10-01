export const workspaceIcons = [
  { value: 'folder', label: '文件夹' }, { value: 'code', label: '代码' }, { value: 'branch', label: '分支' },
  { value: 'cube', label: '模块' }, { value: 'reasoning', label: '闪电' }, { value: 'coffee', label: '咖啡' },
  { value: 'flask', label: '实验' }, { value: 'rocket', label: '火箭' }, { value: 'bug', label: '虫子' },
  { value: 'star', label: '星星' }, { value: 'music', label: '音乐' }, { value: 'heart', label: '爱心' },
  { value: 'globe', label: '地球' }, { value: 'terminal', label: '终端' }, { value: 'gamepad', label: '游戏' },
]
export const workspaceColors = ['#4b705b', '#549c70', '#377ff0', '#f5b238', '#f25555', '#dd4d95', '#6655ee', '#f5c840', '#f08336', '#4fb4a5', '#398bff', '#8e8c99', '#202a3b', '#b399ff', '#7db39b']
// 展示旧单目录路径和任务保存的多目录快照。
export function workspaceDirectories(value: string): string[] {
  if (!value) return []
  if (!value.startsWith('[')) return [value]
  try { return JSON.parse(value) as string[] } catch { return [value] }
}
export const workspaceSummary = (value: string) => workspaceDirectories(value).join(' · ')
export const directoryName = (path: string) => path.split(/[\\/]/).filter(Boolean).pop() || path

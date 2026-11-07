import type { MemberState } from './groups'

export type StudioPose = 'idle' | 'code' | 'review' | 'plan'
const poses: StudioPose[] = ['idle', 'code', 'review', 'plan']
const variants: Record<string, number> = { frontend: 0, backend: 0, 'tech-lead': 1, qa: 1, 'project-manager': 2, host: 2, 'ui-designer': 3, devops: 1 }
const seats = [
  { x: 26, y: 51 }, { x: 43, y: 48 }, { x: 73, y: 46 }, { x: 89, y: 61 },
  { x: 32, y: 84 }, { x: 56, y: 86 }, { x: 88, y: 92 }, { x: 62, y: 67 },
]
const roleSeats: Record<string, number> = { frontend: 0, backend: 1, 'project-manager': 2, 'ui-designer': 3, 'tech-lead': 4, qa: 5, devops: 6 }

/** 角色行与动作列共同定位透明人物图集中的一格。 */
export function studioSprite(template: string, name: string, pose: StudioPose): { x: number; y: number } {
  const row = variants[template] ?? [...name].reduce((sum, char) => sum + char.charCodeAt(0), 0) % 4
  return { x: poses.indexOf(pose) * 100 / 3, y: row * 100 / 3 }
}

/** 工作姿态只由真实执行状态决定，等待批准或成果审核时停止工作动作。 */
export function studioPose(state: MemberState): StudioPose {
  if (['approval', 'review', 'reviewing'].includes(state.kind)) return 'review'
  if (!['working', 'finishing'].includes(state.kind)) return 'idle'
  if (['project-manager', 'host'].includes(state.member.template)) return 'plan'
  if (['tech-lead', 'qa'].includes(state.member.template)) return 'review'
  return 'code'
}

/** 优先安排角色对应的工位；重复角色使用空位，更多成员仍可从成员栏选择。 */
export function studioSeats(states: MemberState[]) {
  const used = new Set<number>()
  return states.slice(0, seats.length).map(state => {
    const preferred = roleSeats[state.member.template]
    const index = preferred !== undefined && !used.has(preferred) ? preferred : seats.findIndex((_, index) => !used.has(index))
    used.add(index)
    return { state, ...seats[index]! }
  })
}

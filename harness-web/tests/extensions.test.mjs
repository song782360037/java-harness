import test from 'node:test'
import assert from 'node:assert/strict'
import { filterExtensions } from '../src/extensions.ts'
const items = [
  { id: 'a', kind: 'plugin', title: 'Java 审查', description: '跟踪调用链', category: '代码评审', official: true, featured: true, installationId: '', parentName: '' },
  { id: 'b', kind: 'skill', title: '团队技能', description: '检查日志', category: '代码开发', official: false, featured: false, installationId: '1', parentName: '团队插件' },
  { id: 'c', kind: 'skill', title: '官方审查', description: '检查回归', category: '代码评审', official: true, featured: true, installationId: '', parentName: '' },
]
test('search combines terms, type, category and installed state without mutating the catalog', () => {
  assert.deepEqual(filterExtensions(items, 'skill', '团队 日志', '自定义', true, 'featured').map(item => item.id), ['b'])
  assert.equal(filterExtensions(items, 'skill', '团队', '官方精选', false, 'featured').length, 0)
  assert.equal(filterExtensions(items, 'skill', '', '全部', true, 'featured').length, 1)
  assert.deepEqual(filterExtensions(items, 'skill', '', '全部', false, 'featured').map(item => item.id), ['c', 'b'])
  assert.deepEqual(items.map(item => item.id), ['a', 'b', 'c'])
})

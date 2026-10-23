import test from 'node:test'
import assert from 'node:assert/strict'
import { slashCommandQuery, matchingPluginCommands, completePluginCommand, fetchPluginCommands } from '../src/pluginCommands.ts'

test('slash completion only opens inside the first command token with a collapsed caret', () => {
  assert.equal(slashCommandQuery('/', 1, 1), '')
  assert.equal(slashCommandQuery('/审查', 3, 3), '审查')
  assert.equal(slashCommandQuery('/java-review:run 检查接口', 12, 12), 'java-review')
  for (const [text, start, end] of [['', 0, 0], ['问题 /review', 10, 10], ['/src/main', 9, 9], ['https://example.com', 8, 8], ['/run 参数', 7, 7], ['/run', 0, 0], ['/run', 1, 4]]) {
    assert.equal(slashCommandQuery(text, start, end), null, text)
  }
})

test('search matches names and localized descriptions without changing command identity', () => {
  const commands = [{id:'java-review:run',description:'审查调用链'}, {id:'tests:run',description:'设计测试计划'}]
  assert.deepEqual(matchingPluginCommands(commands, 'JAVA'), [commands[0]])
  assert.deepEqual(matchingPluginCommands(commands, '测试'), [commands[1]])
  assert.deepEqual(matchingPluginCommands(commands, 'missing'), [])
})

test('completion replaces the partial command and retains task text and line breaks', () => {
  assert.deepEqual(completePluginCommand('/jav', 'java-review:run'), {text:'/java-review:run ',caret:17})
  assert.deepEqual(completePluginCommand('/old 检查接口\n保留第二行', 'java-review:run'), {text:'/java-review:run 检查接口\n保留第二行',caret:17})
  assert.equal(completePluginCommand('/old\n  保留缩进', 'tests:run').text, '/tests:run\n  保留缩进')
})

test('existing sessions use their effective scope instead of assembling user-only commands', async () => {
  const calls = []
  const expected = [{id:'workspace-plugin:run',description:'工作区命令'}]
  const result = await fetchPluginCommands(async path => { calls.push(path); return expected }, 'session-id')
  assert.deepEqual(calls, ['/sessions/session-id/plugin-commands'])
  assert.deepEqual(result, expected)
})

test('first prompt offers only enabled user bindings without creating a session or starting a tool', async () => {
  const calls = []
  const result = await fetchPluginCommands(async path => {
    calls.push(path)
    if (path === '/plugin-bindings?scope=user') return [{installationId:'a',name:'review',enabled:true},{installationId:'b',name:'disabled',enabled:false}]
    assert.equal(path, '/plugins/a')
    return {commands:[{id:'run',description:'审查项目'}]}
  })
  assert.deepEqual(calls, ['/plugin-bindings?scope=user','/plugins/a'])
  assert.deepEqual(result, [{id:'review:run',description:'审查项目'}])
  await assert.rejects(fetchPluginCommands(async () => { throw new Error('unavailable') }))
})

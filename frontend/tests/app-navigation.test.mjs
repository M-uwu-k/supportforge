import assert from 'node:assert/strict'
import test from 'node:test'
import { createAppChatHarness } from './support/app-chat-harness.mjs'

const settings = { accessToken: 'test-token', apiBaseUrl: '/api', tenantId: 'tenant-one',
  actorId: 'agent-one', userId: 'customer-one', roles: ['AGENT'] }
function harness() {
  const calls = [], pending = []
  class Api {
    identity() { return Promise.resolve({}) }
    submitFeedback() { return Promise.resolve({}) }
    chat(payload) {
      calls.push(payload)
      return new Promise(resolve => pending.push(value => resolve({
        response_id: 'response-one', response: '保留这条回答', conversation_id: 'session-one',
        agent_types: ['general'], knowledge_sources: [], entities: {}, ...value,
      })))
    }
  }
  return { ui: createAppChatHarness(Api, settings), calls, pending }
}

test('menu navigation keeps completed messages, draft, feedback and conversation for the next request', async () => {
  const { ui, calls, pending } = harness()
  const first = ui.send('第一问')
  pending[0]({})
  await first
  await ui.rate()
  ui.draft('未发送的追问')
  for (const page of ['知识库', '运行中心', '企业设置']) {
    ui.navigate(page)
    assert.equal(ui.snapshot().hidden, true)
    ui.navigate('智能对话')
    const current = ui.snapshot()
    assert.equal(current.hidden, false)
    assert.equal(current.label, '#session-')
    assert.equal(current.draft, '未发送的追问')
    assert.equal(current.positiveSelected, true)
    assert.ok(current.text.includes('保留这条回答'))
  }
  const next = ui.send('继续原会话')
  assert.equal(calls[1].conversation_id, 'session-one')
  pending[1]({})
  await next
})

test('request completing on another page is available when returning to chat', async () => {
  const { ui, calls, pending } = harness()
  const first = ui.send('尚在生成的问题')
  ui.navigate('知识库')
  pending[0]({ response: '切页期间生成的回答' })
  await first
  ui.navigate('智能对话')
  assert.equal(ui.snapshot().label, '#session-')
  assert.ok(ui.snapshot().text.includes('切页期间生成的回答'))
  const next = ui.send('追问')
  assert.equal(calls[1].conversation_id, 'session-one')
  pending[1]({})
  await next
})

test('changing customer starts a fresh chat and ignores the previous customer pending response', async () => {
  const { ui, calls, pending } = harness()
  const old = ui.send('旧客户的问题')
  ui.navigate('企业设置')
  ui.settings({ ...settings, userId: 'customer-two' })
  pending[0]({ response: '旧客户的延迟回答' })
  await old
  ui.navigate('智能对话')
  assert.equal(ui.snapshot().label, '等待第一条消息')
  assert.equal(ui.snapshot().draft, '')
  assert.ok(!ui.snapshot().text.includes('旧客户的延迟回答'))
  const next = ui.send('新客户的问题')
  assert.equal(calls[1].user_id, 'customer-two')
  assert.equal(calls[1].conversation_id, undefined)
  pending[1]({ conversation_id: 'session-two' })
  await next
})

test('IME candidate confirmation keeps the draft and does not submit or prevent the composing key', () => {
  const { ui, calls } = harness()
  ui.draft('正在拼字的消息')
  for (const nativeEvent of [{ isComposing: true, keyCode: 13 }, { isComposing: false, keyCode: 229 }]) {
    let prevented = false
    ui.keyDown({ key: 'Enter', shiftKey: false, nativeEvent, preventDefault() { prevented = true } })
    assert.equal(prevented, false)
    assert.equal(calls.length, 0)
    assert.equal(ui.snapshot().draft, '正在拼字的消息')
  }
})

test('Enter submits normally after composition has ended', async () => {
  const { ui, calls, pending } = harness()
  ui.draft('已经确认的消息')
  let prevented = false
  ui.keyDown({ key: 'Enter', shiftKey: false, nativeEvent: { isComposing: false, keyCode: 13 },
    preventDefault() { prevented = true } })
  assert.equal(prevented, true)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].message, '已经确认的消息')
  pending[0]({})
  await Promise.resolve()
})

test('Shift Enter remains available for a newline without submitting', () => {
  const { ui, calls } = harness()
  ui.draft('需要换行的消息')
  let prevented = false
  ui.keyDown({ key: 'Enter', shiftKey: true, nativeEvent: { isComposing: false, keyCode: 13 },
    preventDefault() { prevented = true } })
  assert.equal(prevented, false)
  assert.equal(calls.length, 0)
  assert.equal(ui.snapshot().draft, '需要换行的消息')
})

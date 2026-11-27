import assert from 'node:assert/strict'
import test from 'node:test'
import { createAppChatHarness } from './support/app-chat-harness.mjs'

const settings = { accessToken: '', apiBaseUrl: '/api', tenantId: 'tenant-one',
  actorId: '未登录', userId: 'customer-one', roles: [] }
class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status }
}
async function observedText(identity) {
  class Api { identity() { return identity() } }
  const ui = createAppChatHarness(Api, settings, ApiError)
  ui.snapshot()
  await new Promise(resolve => setImmediate(resolve))
  return ui.snapshot().appText
}

for (const status of [401, 403]) {
  test(`HTTP ${status} shows reachable backend awaiting authentication instead of offline`, async () => {
    const text = await observedText(() => Promise.reject(new ApiError('身份验证未通过', status)))
    assert.ok(text.includes('后端在线 · 待验证'))
    assert.ok(text.includes('后端连接正常'))
    assert.ok(text.includes('访问令牌'))
    assert.ok(!text.includes('后端离线'))
  })
}

test('network failure still reports offline', async () => {
  const text = await observedText(() => Promise.reject(new ApiError('无法连接', 0)))
  assert.ok(text.includes('后端离线'))
  assert.ok(!text.includes('后端连接正常'))
})

test('valid authentication reports online without the login reminder', async () => {
  const text = await observedText(() => Promise.resolve({ roles: ['ADMIN'] }))
  assert.ok(text.includes('后端在线'))
  assert.ok(!text.includes('待验证'))
  assert.ok(!text.includes('后端连接正常'))
})

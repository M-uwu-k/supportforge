import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { transformWithOxc } from 'vite'

const file = new URL('../src/api.ts', import.meta.url)
const { code } = await transformWithOxc(fs.readFileSync(file, 'utf8'), file.pathname)
const executable = code.replace(/export class /g, 'class ')
const defaults = { accessToken: '', apiBaseUrl: '/api', tenantId: 'tenant-one',
  actorId: '未登录', roles: [], userId: 'customer-one' }
function client(settings, fetch) {
  const { SupportForgeApi, ApiError } = new Function('fetch', `${executable}\nreturn { SupportForgeApi, ApiError };`)(fetch)
  return { api: new SupportForgeApi(settings), ApiError }
}

test('logged out placeholder does not break Headers and identity request reaches the backend', async () => {
  const calls = []
  const { api, ApiError } = client(defaults, async (url, init) => {
    calls.push({ url, headers: init.headers })
    return new Response('{"status":401}', { status: 401 })
  })
  await assert.rejects(api.identity(), error => error instanceof ApiError && error.status === 401)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].headers.get('X-Actor-Id'), null)
  assert.equal(calls[0].headers.get('X-Roles'), null)
  assert.ok(calls[0].headers.get('X-Request-Id'))
})

test('token authentication does not send display identity in headers', async () => {
  let sent
  const { api } = client({ ...defaults, accessToken: ' test-token ', actorId: '管理员', roles: ['ADMIN'] },
    async (url, init) => { sent = init.headers; return new Response('{"roles":["ADMIN"]}', { status: 200 }) })
  await api.identity()
  assert.equal(sent.get('Authorization'), 'Bearer test-token')
  assert.equal(sent.get('X-Actor-Id'), null)
})

test('configured legacy development identity still sends its headers', async () => {
  let sent
  const { api } = client({ ...defaults, actorId: 'local-admin', roles: ['ADMIN'] },
    async (url, init) => { sent = init.headers; return new Response('{"roles":["ADMIN"]}', { status: 200 }) })
  await api.identity()
  assert.equal(sent.get('X-Actor-Id'), 'local-admin')
  assert.equal(sent.get('X-Roles'), 'ADMIN')
  assert.equal(sent.get('X-Tenant-Id'), 'tenant-one')
})

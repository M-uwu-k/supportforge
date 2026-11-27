import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { transformWithOxc } from 'vite'

// Execute the real component's event handlers with controlled hook state and delayed HTTP responses.
// This tests request ordering without a server, model API, or additional test dependencies.
const sourceFile = new URL('../src/pages/ChatPage.tsx', import.meta.url)
const { code } = await transformWithOxc(fs.readFileSync(sourceFile, 'utf8'), sourceFile.pathname,
  { jsx: { runtime: 'automatic' } })
const executable = code.replace(/^import\s+([\s\S]*?)\s+from\s+["']([^"']+)["'];?\s*$/gm,
  (_, binding, module) => `const ${binding.replace(/\bas\b/g, ':')} = imports[${JSON.stringify(module)}];`)
  .replace('export function ChatPage', 'function ChatPage')

function find(tree, predicate) {
  if (Array.isArray(tree)) {
    for (const child of tree) {
      const match = find(child, predicate)
      if (match) return match
    }
  } else if (tree && typeof tree === 'object') {
    if (predicate(tree)) return tree
    return find(tree.props?.children, predicate)
  }
}

function harness() {
  const hooks = []
  let cursor = 0
  const pending = []
  const calls = []
  const jsx = (type, props) => ({ type, props })
  const imports = {
    react: {
      useState(initial) {
        const index = cursor++
        if (!(index in hooks)) hooks[index] = typeof initial === 'function' ? initial() : initial
        return [hooks[index], value => { hooks[index] = typeof value === 'function' ? value(hooks[index]) : value }]
      },
      useRef(initial) { const index = cursor++; return hooks[index] ??= { current: initial } },
      useEffect() {},
    },
    'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    '../api': { ApiError: class extends Error {} },
    '../components/Icon': { Icon: 'icon' },
  }
  const ChatPage = new Function('imports', executable + '\nreturn ChatPage;')(imports)
  const api = { chat(request) {
    calls.push(request)
    return new Promise((resolve, reject) => pending.push({
      resolve(value) { resolve({ agent_types: ['general'], knowledge_sources: [], entities: {}, ...value }) },
      reject,
    }))
  } }
  const render = () => { cursor = 0; return ChatPage({ api, settings: { userId: 'test-user' } }) }
  const node = predicate => find(render(), predicate)
  return {
    calls, pending,
    async send(text) {
      node(item => item.type === 'textarea').props.onChange({ target: { value: text } })
      return node(item => item.type === 'form').props.onSubmit({ preventDefault() {} })
    },
    reset() { node(item => item.type === 'button' && item.props.onClick?.name === 'newConversation').props.onClick() },
    // The rendered label is the observable conversation identity, independent of hook positions.
    label() { return node(item => item.props?.className === 'conversation-label').props.children.at(-1) },
  }
}

test('late old response cannot attach its conversation to a new conversation', async () => {
  const ui = harness()
  const oldRequest = ui.send('旧会话问题')
  ui.reset()
  ui.pending[0].resolve({ conversation_id: 'old-session', response: '旧回答' })
  await oldRequest
  assert.equal(ui.label(), '等待第一条消息')
  const next = ui.send('新会话问题')
  assert.equal(ui.calls[1].conversation_id, undefined)
  ui.pending[1].resolve({ conversation_id: 'new-session', response: '新回答' })
  await next
  assert.equal(ui.label(), '#new-sess')
})

test('old request completion cannot re-enable sending while the new request is pending', async () => {
  const ui = harness()
  const oldRequest = ui.send('旧问题')
  ui.reset()
  const currentRequest = ui.send('新问题')
  assert.equal(ui.calls.length, 2)
  ui.pending[0].reject(new Error('delayed old failure'))
  await oldRequest
  // An extra submission must still be blocked by the current request.
  await ui.send('不能重复提交')
  assert.equal(ui.calls.length, 2)
  ui.pending[1].resolve({ conversation_id: 'new-session', response: '完成' })
  await currentRequest
})

test('normal follow-up messages continue using the returned conversation ID', async () => {
  const ui = harness()
  const first = ui.send('第一问')
  ui.pending[0].resolve({ conversation_id: 'normal-session', response: '第一答' })
  await first
  const next = ui.send('继续问')
  assert.equal(ui.calls[1].conversation_id, 'normal-session')
  ui.pending[1].resolve({ conversation_id: 'normal-session', response: '第二答' })
  await next
})

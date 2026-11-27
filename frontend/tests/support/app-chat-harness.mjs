import fs from 'node:fs'
import { transformWithOxc } from 'vite'

async function executable(relative, exported) {
  const file = new URL(relative, import.meta.url)
  const { code } = await transformWithOxc(fs.readFileSync(file, 'utf8'), file.pathname,
    { jsx: { runtime: 'automatic' } })
  return code.replace(/^import\s+([\s\S]*?)\s+from\s+["']([^"']+)["'];?\s*$/gm,
    (_, binding, module) => `const ${binding.replace(/\bas\b/g, ':')} = imports[${JSON.stringify(module)}];`)
    .replace('import.meta.env.VITE_API_BASE_URL', "''")
    .replace(`export ${exported}`, exported)
}
const chatCode = await executable('../../src/pages/ChatPage.tsx', 'function ChatPage')
const appCode = await executable('../../src/App.tsx', 'default function App')

function visit(tree, predicate, path = '', hidden = false) {
  if (Array.isArray(tree)) {
    for (let index = 0; index < tree.length; index++) {
      const found = visit(tree[index], predicate, `${path}/${index}`, hidden)
      if (found) return found
    }
  } else if (tree && typeof tree === 'object') {
    const concealed = hidden || tree.props?.hidden === true
    if (predicate(tree, concealed)) return { node: tree, path, hidden: concealed }
    return visit(tree.props?.children, predicate, `${path}/${tree.type?.name || tree.type}`, concealed)
  }
}
function text(tree) {
  if (Array.isArray(tree)) return tree.map(text).join('')
  return tree && typeof tree === 'object' ? text(tree.props?.children) : String(tree ?? '')
}
function hooks() {
  const values = [], effects = []
  let cursor = 0
  const changed = (old, next) => !old || !next || old.length !== next.length || old.some((v, i) => !Object.is(v, next[i]))
  return {
    begin() { cursor = 0 },
    flush() { for (const effect of effects.splice(0)) effect() },
    unmount() { for (const value of values) value?.cleanup?.() },
    react: {
      useState(initial) {
        const index = cursor++
        if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial
        return [values[index], value => { values[index] = typeof value === 'function' ? value(values[index]) : value }]
      },
      useRef(initial) { const index = cursor++; return values[index] ??= { current: initial } },
      useMemo(factory, deps) {
        const index = cursor++
        if (changed(values[index]?.deps, deps)) values[index] = { deps, value: factory() }
        return values[index].value
      },
      useEffect(callback, deps) {
        const index = cursor++
        if (changed(values[index]?.deps, deps)) {
          const previous = values[index]
          values[index] = { deps }
          effects.push(() => { previous?.cleanup?.(); values[index].cleanup = callback() })
        }
      },
    },
  }
}

// Runs the real App and ChatPage handlers and follows App's mount/key decisions, without a browser dependency.
// ApiClass can be the HTTP client itself for live enterprise regression; unit tests use controlled delayed replies.
export function createAppChatHarness(ApiClass, settings, ApiErrorClass = class extends Error {}) {
  const jsx = (type, props, key) => ({ type, props, key })
  const runtime = { jsx, jsxs: jsx, Fragment: 'fragment' }
  const appHooks = hooks()
  let chatHooks, chatIdentity, chatTree, chatHidden
  const ChatPage = new Function('imports', `${chatCode}\nreturn ChatPage;`)({
    react: new Proxy({}, { get: (_, name) => (...args) => chatHooks.react[name](...args) }),
    'react/jsx-runtime': runtime, '../api': { ApiError: ApiErrorClass }, '../components/Icon': { Icon: 'icon' },
  })
  const storage = new Map([['supportforge.console.settings.v1', JSON.stringify(settings)],
    ['supportforge.access-token', settings.accessToken]])
  const store = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) }
  const App = new Function('imports', 'localStorage', 'sessionStorage',
    `${appCode.replace('default function App', 'function App')}\nreturn App;`)({
    react: appHooks.react, 'react/jsx-runtime': runtime, './api': { SupportForgeApi: ApiClass, ApiError: ApiErrorClass },
    './components/Icon': { Icon: 'icon' }, './pages/ChatPage': { ChatPage },
    './pages/KnowledgePage': { KnowledgePage: 'knowledge-page' },
    './pages/OperationsPage': { OperationsPage: 'operations-page' },
    './pages/SettingsPage': { SettingsPage: 'settings-page' },
  }, store, store)
  let appTree
  function render() {
    appHooks.begin(); appTree = App(); appHooks.flush()
    const found = visit(appTree, node => node.type === ChatPage)
    const identity = found && `${found.path}/${found.node.key ?? ''}`
    if (identity !== chatIdentity) {
      chatHooks?.unmount(); chatHooks = undefined; chatIdentity = identity
    }
    chatTree = undefined
    chatHidden = found?.hidden ?? true
    if (found) {
      chatHooks ??= hooks()
      chatHooks.begin(); chatTree = ChatPage(found.node.props); chatHooks.flush()
    }
  }
  const chatNode = predicate => { render(); return visit(chatTree, predicate)?.node }
  return {
    navigate(label) {
      render()
      visit(appTree, node => node.type === 'button' && node.props.className?.includes('nav-item') && text(node).includes(label)).node.props.onClick()
      render()
    },
    draft(value) { chatNode(node => node.type === 'textarea').props.onChange({ target: { value } }) },
    keyDown(event) { chatNode(node => node.type === 'textarea').props.onKeyDown(event) },
    async send(value) {
      this.draft(value)
      return chatNode(node => node.type === 'form').props.onSubmit({ preventDefault() {} })
    },
    async rate() { chatNode(node => node.props?.['aria-label'] === '好评').props.onClick(); await Promise.resolve() },
    settings(next) { render(); visit(appTree, node => node.type === 'settings-page').node.props.onSave(next); render() },
    snapshot() {
      render()
      return {
        appText: text(appTree),
        hidden: chatHidden,
        label: visit(chatTree, node => node.props?.className === 'conversation-label')?.node.props.children.at(-1),
        draft: visit(chatTree, node => node.type === 'textarea')?.node.props.value,
        text: text(chatTree),
        positiveSelected: visit(chatTree, node => node.props?.['aria-label'] === '好评')?.node.props.className === 'selected',
      }
    },
  }
}

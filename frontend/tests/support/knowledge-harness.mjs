import fs from 'node:fs'
import { transformWithOxc } from 'vite'

const file = new URL('../../src/pages/KnowledgePage.tsx', import.meta.url)
const { code } = await transformWithOxc(fs.readFileSync(file, 'utf8'), file.pathname,
  { jsx: { runtime: 'automatic' } })
const executable = code.replace(/^import\s+([\s\S]*?)\s+from\s+["']([^"']+)["'];?\s*$/gm,
  (_, binding, module) => `const ${binding.replace(/\bas\b/g, ':')} = imports[${JSON.stringify(module)}];`)
  .replace('export function KnowledgePage', 'function KnowledgePage')

function text(tree) {
  if (Array.isArray(tree)) return tree.map(text).join('')
  return tree && typeof tree === 'object' ? text(tree.props?.children) : String(tree ?? '')
}
function find(tree, predicate) {
  if (Array.isArray(tree)) {
    for (const item of tree) { const match = find(item, predicate); if (match) return match }
  } else if (tree && typeof tree === 'object') {
    return predicate(tree) ? tree : find(tree.props?.children, predicate)
  }
}

// Execute the actual component effects and event handlers. Delayed API promises exercise stale-result cleanup.
export function createKnowledgeHarness(api, roles = ['ADMIN']) {
  const values = [], pendingEffects = []
  let cursor = 0, tree
  const react = {
    useState(initial) {
      const index = cursor++
      if (!(index in values)) values[index] = initial
      return [values[index], value => { values[index] = typeof value === 'function' ? value(values[index]) : value }]
    },
    useEffect(callback, deps) {
      const index = cursor++, previous = values[index]
      if (!previous || deps.some((value, i) => !Object.is(value, previous.deps[i]))) {
        values[index] = { deps }
        pendingEffects.push(() => { previous?.cleanup?.(); values[index].cleanup = callback() })
      }
    },
  }
  const jsx = (type, props, key) => ({ type, props, key })
  const KnowledgePage = new Function('imports', `${executable}\nreturn KnowledgePage;`)({
    react, 'react/jsx-runtime': { jsx, jsxs: jsx, Fragment: 'fragment' },
    '../api': { ApiError: Error }, '../components/Icon': { Icon: 'icon' },
  })
  function render() { cursor = 0; tree = KnowledgePage({ api, roles }); for (const effect of pendingEffects.splice(0)) effect() }
  const node = predicate => { render(); const result = find(tree, predicate); if (!result) throw new Error('Element not found'); return result }
  render()
  return {
    async settle() { await new Promise(resolve => setImmediate(resolve)); render() },
    text() { render(); return text(tree) },
    query(value) { node(item => item.props?.['aria-label'] === '知识搜索词').props.onChange({ target: { value } }) },
    category(value) { node(item => item.props?.['aria-label'] === '知识分类').props.onChange({ target: { value } }) },
    search() { node(item => item.type === 'form' && item.props.className === 'search-bar').props.onSubmit({ preventDefault() {} }); render() },
    click(label) {
      const button = node(item => item.type === 'button' && (item.props['aria-label'] === label || text(item) === label))
      if (button.props.disabled) throw new Error(`Disabled button: ${label}`)
      button.props.onClick(); render()
    },
    countCards() {
      render()
      let count = 0
      find(tree, item => { if (item.props?.className === 'knowledge-card') count++; return false })
      return count
    },
  }
}

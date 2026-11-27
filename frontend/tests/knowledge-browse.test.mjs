import assert from 'node:assert/strict'
import test from 'node:test'
import { createKnowledgeHarness } from './support/knowledge-harness.mjs'

const docs = Array.from({ length: 14 }, (_, i) => ({ id: `d${i}`, title: `文档${i}`,
  category: i < 13 ? 'TECHNICAL' : 'BILLING', content: `完整正文${i}\n下一段步骤`, keywords: ['测试'] }))
function api() {
  const calls = []
  return { calls,
    async knowledgeStats() { return { total_documents: docs.length } },
    async listKnowledge(category, page = 1, size = 12) {
      calls.push({ category, page })
      const visible = docs.filter(doc => !category || doc.category === category)
      return { documents: visible.slice((page - 1) * size, page * size), page, size,
        total_elements: visible.length, total_pages: Math.ceil(visible.length / size) }
    },
    async searchKnowledge() { return [{ document: docs[2], score: 0.9, matched_keywords: ['测试'] }] },
    async getKnowledge(id) { return docs.find(doc => doc.id === id) },
  }
}

test('opens with document list and supports pagination without any search words', async () => {
  const client = api(), ui = createKnowledgeHarness(client)
  await ui.settle()
  assert.equal(ui.countCards(), 12)
  assert.ok(ui.text().includes('第 1 / 2 页'))
  ui.click('下一页'); await ui.settle()
  assert.equal(ui.countCards(), 2)
  assert.ok(ui.text().includes('第 2 / 2 页'))
  ui.category('BILLING'); await ui.settle()
  assert.deepEqual(client.calls.at(-1), { category: 'BILLING', page: 1 })
  assert.equal(ui.countCards(), 1)
})

test('search then clearing query restores browse list automatically', async () => {
  const ui = createKnowledgeHarness(api())
  await ui.settle()
  ui.query('测试'); ui.search(); await ui.settle()
  assert.equal(ui.countCards(), 1)
  assert.ok(ui.text().includes('得分 0.900'))
  ui.query(''); await ui.settle(); await ui.settle()
  assert.equal(ui.countCards(), 12)
  assert.ok(!ui.text().includes('得分'))
})

test('full text opens from list and non-manager has no write controls', async () => {
  const client = api(), ui = createKnowledgeHarness(client, ['AGENT'])
  await ui.settle()
  assert.ok(!ui.text().includes('新增知识'))
  assert.ok(!ui.text().includes('选择文件'))
  assert.ok(ui.text().includes('我有权查看的文档'))
  ui.click('查看全文'); await ui.settle()
  assert.ok(ui.text().includes('知识文档全文'))
  assert.ok(ui.text().includes(docs[0].content))
  ui.click('关闭文档')
  assert.ok(!ui.text().includes('知识文档全文'))
})

test('late browse response does not overwrite a newer category selection', async () => {
  let finish
  const client = api(), list = client.listKnowledge.bind(client)
  client.listKnowledge = (category, ...args) => !category ? new Promise(resolve => { finish = resolve }) : list(category, ...args)
  const ui = createKnowledgeHarness(client)
  ui.category('BILLING'); await ui.settle()
  assert.equal(ui.countCards(), 1)
  finish(await list('', 1))
  await ui.settle()
  assert.equal(ui.countCards(), 1)
  assert.ok(ui.text().includes('文档13'))
})

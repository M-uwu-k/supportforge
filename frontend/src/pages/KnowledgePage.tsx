import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, SupportForgeApi } from '../api'
import { Icon } from '../components/Icon'
import type { EnterpriseRole, KnowledgeCategory, KnowledgeDocument, KnowledgeDocumentPage, KnowledgeSearchResult, KnowledgeStats } from '../types'

interface KnowledgePageProps { api: SupportForgeApi; roles: EnterpriseRole[] }

const categories: Array<{ value: KnowledgeCategory | ''; label: string }> = [
  { value: '', label: '全部分类' },
  { value: 'GENERAL', label: '通用' },
  { value: 'TECHNICAL', label: '技术' },
  { value: 'BILLING', label: '账务' },
]

export function KnowledgePage({ api, roles }: KnowledgePageProps) {
  const canManage = roles.some((role) => role === 'ADMIN' || role === 'KNOWLEDGE_EDITOR')
  const [stats, setStats] = useState<KnowledgeStats | null>(null)
  const [query, setQuery] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [category, setCategory] = useState<KnowledgeCategory | ''>('')
  const [page, setPage] = useState(1)
  const [revision, setRevision] = useState(0)
  const [documentPage, setDocumentPage] = useState<KnowledgeDocumentPage | null>(null)
  const [results, setResults] = useState<KnowledgeSearchResult[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [selectedDocument, setSelectedDocument] = useState<KnowledgeDocument | null>(null)
  const [detailError, setDetailError] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [keywords, setKeywords] = useState('')
  const [newCategory, setNewCategory] = useState<KnowledgeCategory>('GENERAL')
  const [file, setFile] = useState<File | null>(null)

  useEffect(() => {
    let active = true
    api.knowledgeStats().then((value) => { if (active) setStats(value) })
      .catch(() => { if (active) setStats(null) })
    return () => { active = false }
  }, [api, revision])

  // 默认浏览、分类筛选和分页都从后端获取已授权的文档；关键词检索仍按相关度返回 Top-K。
  // 切换筛选或身份后忽略旧请求，避免较慢的旧结果覆盖当前列表。
  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    setResults([])
    setDocumentPage(null)
    const load = async () => {
      try {
        if (submittedQuery) {
          const value = await api.searchKnowledge(submittedQuery, category)
          if (active) setResults(value)
        } else {
          const value = await api.listKnowledge(category, page)
          if (active) {
            if (page > Math.max(1, value.total_pages)) setPage(Math.max(1, value.total_pages))
            else setDocumentPage(value)
          }
        }
      } catch (failure) {
        if (active) setError(failure instanceof ApiError ? failure.message : '知识加载失败')
      } finally {
        if (active) setLoading(false)
      }
    }
    void load()
    return () => { active = false }
  }, [api, submittedQuery, category, page, revision])

  // 查看全文时重新请求详情接口；后端会再次检查权限和文档当前状态。
  useEffect(() => {
    let active = true
    setSelectedDocument(null)
    setDetailError('')
    if (selectedId) {
      api.getKnowledge(selectedId).then((value) => { if (active) setSelectedDocument(value) })
        .catch((failure) => { if (active) setDetailError(failure instanceof Error ? failure.message : '文档加载失败') })
    }
    return () => { active = false }
  }, [api, selectedId])

  function search(event?: FormEvent) {
    event?.preventDefault()
    setSubmittedQuery(query.trim())
    setPage(1)
    setRevision((value) => value + 1)
  }

  function browseAll() {
    setQuery('')
    setSubmittedQuery('')
    setPage(1)
    setRevision((value) => value + 1)
  }

  async function addDocument(event: FormEvent) {
    event.preventDefault()
    const parsedKeywords = keywords.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean)
    if (!title.trim() || !content.trim() || parsedKeywords.length === 0) return
    setSaving(true)
    setError('')
    try {
      await api.addKnowledge({
        title: title.trim(),
        content: content.trim(),
        category: newCategory,
        keywords: parsedKeywords,
      })
      setTitle('')
      setContent('')
      setKeywords('')
      setShowAdd(false)
      browseAll()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '知识新增失败')
    } finally {
      setSaving(false)
    }
  }

  async function upload() {
    if (!file) return
    setSaving(true)
    setError('')
    try {
      const result = await api.uploadKnowledge(file, newCategory)
      window.alert(`导入完成：成功 ${result.imported}，失败 ${result.failed}`)
      setFile(null)
      browseAll()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '文件上传失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="knowledge-page">
      <div className="summary-strip">
        <div className="summary-stat">
          <span className="summary-icon indigo"><Icon name="database" /></span>
          <div><strong>{stats?.total_documents ?? '—'}</strong><small>{roles.includes('ADMIN') ? '当前租户有效文档' : '我有权查看的文档'}</small></div>
        </div>
        <div className="summary-stat">
          <span className="summary-icon green"><Icon name="check" /></span>
          <div><strong>3</strong><small>支持的知识分类</small></div>
        </div>
        {canManage && <div className="summary-actions">
          <label className="button secondary compact file-button">
            <Icon name="upload" size={16} /> {file ? file.name : '选择文件'}
            <input type="file" accept=".txt,.md,.json" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
          </label>
          {file && <button className="button secondary compact" onClick={() => void upload()} disabled={saving}>上传</button>}
          <button className="button primary compact" onClick={() => setShowAdd(true)} disabled={saving}>
            <Icon name="plus" size={16} /> 新增知识
          </button>
        </div>}
      </div>

      <section className="panel knowledge-panel">
        <form className="search-bar" onSubmit={search}>
          <div className="search-input"><Icon name="search" size={18} /><input aria-label="知识搜索词" value={query} onChange={(event) => {
            setQuery(event.target.value)
            if (!event.target.value.trim() && submittedQuery) {
              setSubmittedQuery('')
              setPage(1)
            }
          }} placeholder="输入关键词检索；留空浏览文档" /></div>
          <select aria-label="知识分类" value={category} onChange={(event) => { setCategory(event.target.value as KnowledgeCategory | ''); setPage(1) }}>
            {categories.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
          </select>
          <button className="button primary" disabled={loading}>{loading ? '加载中…' : query.trim() ? '检索知识' : '浏览文档'}</button>
        </form>

        {error && <div className="inline-alert error">{error}</div>}

        <div className="knowledge-list-header">
          <div><span className="eyebrow">{submittedQuery ? '检索结果' : '文档列表'}</span><strong>{loading ? '正在加载…' : submittedQuery ? `${results.length} 条匹配（按相关度）` : `共 ${documentPage?.total_elements ?? 0} 篇 · 按标题排序`}</strong></div>
          {submittedQuery ? <button className="button secondary compact" onClick={browseAll}>返回文档列表</button> : <span>{roles.includes('ADMIN') ? '当前租户全部有效文档' : '仅显示已获授权的文档'}</span>}
        </div>

        <div className="knowledge-results">
          {!loading && !error && (submittedQuery ? results.length === 0 : !documentPage?.documents.length) ? (
            <div className="empty-state">
              <div className="soft-icon"><Icon name="book" /></div>
              <strong>{submittedQuery ? '没有找到匹配知识' : '暂无可查看的文档'}</strong>
              <p>{submittedQuery ? '试试其他关键词，或返回文档列表。' : roles.includes('ADMIN') ? '可以调整分类，或新增、上传知识文档。' : '可以调整分类；需要其他文档时请联系管理员授权。'}</p>
            </div>
          ) : (submittedQuery ? results : (documentPage?.documents ?? []).map((document) => ({ document, score: null, matched_keywords: [] }))).map((result) => (
            <article className="knowledge-card" key={result.document.id}>
              <div className="knowledge-card-top">
                <span className={`category-badge ${result.document.category.toLowerCase()}`}>{result.document.category}</span>
                {result.score !== null && <span className="score-badge">得分 {result.score.toFixed(3)}</span>}
              </div>
              <h3>{result.document.title}</h3>
              <p>{result.document.content}</p>
              <div className="keyword-row">
                {(result.matched_keywords.length ? result.matched_keywords : result.document.keywords).map((keyword) => <span key={keyword}>#{keyword}</span>)}
              </div>
              <small className="document-id">ID · {result.document.id}</small>
              <button className="button secondary compact knowledge-detail-button" onClick={() => setSelectedId(result.document.id)}>查看全文</button>
            </article>
          ))}
        </div>
        {!submittedQuery && documentPage && documentPage.total_pages > 0 && (
          <nav className="knowledge-pagination" aria-label="知识文档分页">
            <span>第 {documentPage.page} / {documentPage.total_pages} 页 · 每页 {documentPage.size} 篇</span>
            <div>
              <button className="button secondary compact" disabled={loading || page <= 1} onClick={() => setPage((value) => value - 1)}>上一页</button>
              <button className="button secondary compact" disabled={loading || page >= documentPage.total_pages} onClick={() => setPage((value) => value + 1)}>下一页</button>
            </div>
          </nav>
        )}
      </section>

      {selectedId && (
        <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setSelectedId(null)}>
          <section className="modal knowledge-detail" role="dialog" aria-modal="true" aria-label="知识文档全文">
            <div className="modal-header">
              <div><span className="eyebrow">知识文档全文</span><h2>{selectedDocument?.title ?? '文档详情'}</h2></div>
              <button type="button" className="icon-button" aria-label="关闭文档" onClick={() => setSelectedId(null)}><Icon name="x" /></button>
            </div>
            {detailError ? <div className="inline-alert error">{detailError}</div> : !selectedDocument ? <p>加载中…</p> : <>
              <span className={`category-badge ${selectedDocument.category.toLowerCase()}`}>{selectedDocument.category}</span>
              <small className="document-id">ID · {selectedDocument.id}</small>
              <div className="keyword-row">{selectedDocument.keywords.map((keyword) => <span key={keyword}>#{keyword}</span>)}</div>
              <div className="knowledge-full-content">{selectedDocument.content}</div>
            </>}
          </section>
        </div>
      )}

      {showAdd && (
        <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setShowAdd(false)}>
          <form className="modal" onSubmit={addDocument}>
            <div className="modal-header">
              <div><span className="eyebrow">知识管理</span><h2>新增知识文档</h2></div>
              <button type="button" className="icon-button" onClick={() => setShowAdd(false)}><Icon name="x" /></button>
            </div>
            <label>标题<input value={title} onChange={(event) => setTitle(event.target.value)} placeholder="例如：退款申请条件" required /></label>
            <label>分类<select value={newCategory} onChange={(event) => setNewCategory(event.target.value as KnowledgeCategory)}>{categories.filter((item) => item.value).map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
            <label>关键词<input value={keywords} onChange={(event) => setKeywords(event.target.value)} placeholder="退款，订单号，到账时间" required /></label>
            <label>正文<textarea value={content} onChange={(event) => setContent(event.target.value)} rows={8} placeholder="输入可被客服检索和引用的完整知识正文…" required /></label>
            <div className="modal-actions"><button type="button" className="button secondary" onClick={() => setShowAdd(false)}>取消</button><button className="button primary" disabled={saving}>保存知识</button></div>
          </form>
        </div>
      )}
    </div>
  )
}

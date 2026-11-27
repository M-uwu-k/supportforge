import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { ApiError, SupportForgeApi } from '../api'
import { Icon } from '../components/Icon'
import type { ChatResponse, ConsoleSettings } from '../types'

interface ChatPageProps {
  api: SupportForgeApi
  settings: ConsoleSettings
}

interface UiMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  result?: ChatResponse
  pending?: boolean
  failed?: boolean
}

function localId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`
}

const welcomeMessage: UiMessage = {
  id: 'welcome',
  role: 'assistant',
  content: '你好，我是 SupportForge 企业智能客服。你可以询问退款、账单、登录故障或产品使用问题，我会结合会话记忆与企业知识库给出回答。',
}

export function ChatPage({ api, settings }: ChatPageProps) {
  const [messages, setMessages] = useState<UiMessage[]>([welcomeMessage])
  const [conversationId, setConversationId] = useState('')
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [feedback, setFeedback] = useState<Record<string, 'positive' | 'negative'>>({})
  const bottomRef = useRef<HTMLDivElement>(null)
  // 切换会话时递增；已发出的旧请求即使稍后完成，也不能写入新会话。
  const requestGeneration = useRef(0)

  useEffect(() => () => { requestGeneration.current += 1 }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  async function sendMessage(event?: FormEvent) {
    event?.preventDefault()
    const message = draft.trim()
    if (!message || sending) return
    const generation = ++requestGeneration.current

    const userMessage: UiMessage = { id: localId(), role: 'user', content: message }
    const pendingId = localId()
    setMessages((current) => [
      ...current,
      userMessage,
      { id: pendingId, role: 'assistant', content: '', pending: true },
    ])
    setDraft('')
    setSending(true)

    try {
      const result = await api.chat({
        message,
        user_id: settings.userId,
        ...(conversationId ? { conversation_id: conversationId } : {}),
      })
      if (generation !== requestGeneration.current) return
      setConversationId(result.conversation_id)
      setMessages((current) => current.map((item) => item.id === pendingId
        ? { id: pendingId, role: 'assistant', content: result.response, result }
        : item))
    } catch (error) {
      if (generation !== requestGeneration.current) return
      const messageText = error instanceof ApiError ? error.message : '发送失败，请稍后重试。'
      setMessages((current) => current.map((item) => item.id === pendingId
        ? { id: pendingId, role: 'assistant', content: messageText, failed: true }
        : item))
    } finally {
      // 旧请求结束时，也不能把新请求的“正在发送”状态清掉。
      if (generation === requestGeneration.current) setSending(false)
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // 输入法确认候选文字时也可能触发Enter；229兼容部分浏览器在组合结束边界的按键事件。
    // 此时让输入法正常完成输入，不拦截按键，也不提交消息。
    if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      void sendMessage()
    }
  }

  async function rate(result: ChatResponse, value: 'positive' | 'negative') {
    try {
      await api.submitFeedback(result.response_id, result.conversation_id, value)
      setFeedback((current) => ({ ...current, [result.response_id]: value }))
    } catch (error) {
      window.alert(error instanceof Error ? error.message : '反馈提交失败')
    }
  }

  function newConversation() {
    requestGeneration.current += 1
    setSending(false)
    setConversationId('')
    setMessages([{ ...welcomeMessage, id: localId() }])
    setFeedback({})
    setDraft('')
  }

  return (
    <div className="chat-layout">
      <section className="chat-panel panel">
        <div className="chat-toolbar">
          <div>
            <span className="eyebrow">当前会话</span>
            <div className="conversation-label">
              <span className="live-dot" />
              {conversationId ? `#${conversationId.slice(0, 8)}` : '等待第一条消息'}
            </div>
          </div>
          <button className="button secondary compact" onClick={newConversation}>
            <Icon name="plus" size={16} /> 新会话
          </button>
        </div>

        <div className="message-stream">
          {messages.map((message) => (
            <article key={message.id} className={`message-row ${message.role}`}>
              <div className={`message-avatar ${message.role}`}>
                <Icon name={message.role === 'assistant' ? 'bot' : 'user'} size={18} />
              </div>
              <div className="message-body">
                <div className="message-author">
                  <strong>{message.role === 'assistant' ? 'SupportForge' : '你'}</strong>
                  {message.result && (
                    <span>{Math.round(message.result.latency_ms)} ms</span>
                  )}
                </div>

                {message.pending ? (
                  <div className="typing-indicator" aria-label="正在生成回答">
                    <i /><i /><i />
                  </div>
                ) : (
                  <div className={`message-bubble ${message.failed ? 'error' : ''}`}>
                    {message.content}
                  </div>
                )}

                {message.result && (
                  <>
                    <div className="response-badges">
                      <span className="tag indigo">{message.result.intent}</span>
                      <span className="tag neutral">{message.result.primary_agent} agent</span>
                      <span className={`tag ${message.result.verified ? 'success' : 'warning'}`}>
                        {message.result.verified ? '已验证' : '待复核'}
                      </span>
                      {message.result.grounded && message.result.knowledge_used && <span className="tag success">证据校验通过</span>}
                      {message.result.grounding_status && !['grounded', 'not_required'].includes(message.result.grounding_status) && (
                        <span className="tag warning">本轮未通过企业证据检查</span>
                      )}
                      {message.result.escalated && <span className="tag warning">建议转人工</span>}
                    </div>

                    {message.result.knowledge_sources?.length > 0 && (
                      <div className="source-list">
                        <div className="source-title"><Icon name="book" size={15} /> 引用知识</div>
                        {message.result.knowledge_sources.map((source, index) => (
                          <div className="source-item" key={`${source.id}-${index}`}>
                            <span className="source-index">{index + 1}</span>
                            <div>
                              <strong>{source.title}</strong>
                              <small>{source.category} · 检索得分 {source.score.toFixed(3)}</small>
                              {source.matched_keywords?.length > 0 && <small>命中关键词：{source.matched_keywords.join('、')}</small>}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {message.result.evidence && message.result.evidence.length > 0 && (
                      <details className="source-list">
                        <summary>查看本轮实际引用的证据（{message.result.evidence.length} 条）</summary>
                        {message.result.evidence.map((item) => (
                          <div className="source-item" key={item.point_id}>
                            <span className="source-index">{item.citation_id}</span>
                            <div>
                              <strong>{item.external_document_id} · 第 {item.chunk_index + 1} 段</strong>
                              <p>{item.content}</p>
                              <small>证据编号：{item.point_id}</small>
                            </div>
                          </div>
                        ))}
                        {message.result.retrieval_trace_id && <small>检索记录：{message.result.retrieval_trace_id}</small>}
                      </details>
                    )}

                    {message.result.verification_issues?.length > 0 && (
                      <details className="source-list">
                        <summary>查看本轮复核原因</summary>
                        <ul>{message.result.verification_issues.map((issue) => <li key={issue}>{issue}</li>)}</ul>
                      </details>
                    )}

                    <div className="message-actions">
                      <span>这个回答有帮助吗？</span>
                      <button
                        className={feedback[message.result.response_id] === 'positive' ? 'selected' : ''}
                        onClick={() => void rate(message.result!, 'positive')}
                        aria-label="好评"
                      ><Icon name="thumb-up" size={15} /></button>
                      <button
                        className={feedback[message.result.response_id] === 'negative' ? 'selected negative' : ''}
                        onClick={() => void rate(message.result!, 'negative')}
                        aria-label="差评"
                      ><Icon name="thumb-down" size={15} /></button>
                    </div>
                  </>
                )}
              </div>
            </article>
          ))}
          <div ref={bottomRef} />
        </div>

        <form className="composer" onSubmit={sendMessage}>
          <div className="composer-box">
            <textarea
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={onKeyDown}
              placeholder="输入客户问题，Enter 发送，Shift + Enter 换行…"
              rows={2}
              maxLength={4000}
            />
            <button className="send-button" disabled={!draft.trim() || sending} aria-label="发送消息">
              <Icon name="send" size={18} />
            </button>
          </div>
          <div className="composer-meta">
            <span><Icon name="shield" size={13} /> 租户隔离请求</span>
            <span>{draft.length}/4000</span>
          </div>
        </form>
      </section>

      <aside className="insight-panel panel">
        <div className="insight-heading">
          <span className="eyebrow">会话洞察</span>
          <strong>实时编排信息</strong>
        </div>
        {(() => {
          const latest = [...messages].reverse().find((item) => item.result)?.result
          if (!latest) {
            return (
              <div className="empty-insight">
                <div className="soft-icon"><Icon name="activity" /></div>
                <strong>等待对话</strong>
                <p>发送消息后，这里会展示意图、路由、证据和实体。</p>
              </div>
            )
          }
          return (
            <div className="insight-content">
              <div className="metric-block">
                <div className="metric-label"><span>意图置信度</span><strong>{(latest.intent_confidence * 100).toFixed(0)}%</strong></div>
                <div className="progress"><i style={{ width: `${Math.min(100, latest.intent_confidence * 100)}%` }} /></div>
                <small>{latest.intent} · {latest.intent_group}</small>
              </div>
              <div className="metric-block">
                <div className="metric-label"><span>路由置信度</span><strong>{(latest.routing_confidence * 100).toFixed(0)}%</strong></div>
                <div className="progress emerald"><i style={{ width: `${Math.min(100, latest.routing_confidence * 100)}%` }} /></div>
                <small>{latest.routing_reason}</small>
              </div>
              <div className="insight-section">
                <h3>参与 Agent</h3>
                <div className="agent-stack">
                  {latest.agent_types.map((agent) => (
                    <div className="agent-row" key={agent}>
                      <span className="mini-avatar"><Icon name="bot" size={14} /></span>
                      <span>{agent}</span>
                      <i className="agent-ready"><Icon name="check" size={12} /></i>
                    </div>
                  ))}
                </div>
              </div>
              <div className="insight-section">
                <h3>已识别实体</h3>
                <div className="entity-cloud">
                  {Object.entries(latest.entities || {}).flatMap(([name, values]) =>
                    values.map((value) => <span key={`${name}-${value}`}>{name}: {value}</span>))}
                  {Object.values(latest.entities || {}).flat().length === 0 && <small>本轮没有提取到实体</small>}
                </div>
              </div>
            </div>
          )
        })()}
      </aside>
    </div>
  )
}

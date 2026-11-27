import type {
  AnswerFeedback,
  AccessIdentity,
  ChatRequest,
  ChatResponse,
  ConsoleSettings,
  FeedbackSummary,
  HealthResponse,
  KnowledgeCategory,
  KnowledgeDocument,
  KnowledgeDocumentPage,
  KnowledgeImportResult,
  KnowledgeSearchResult,
  KnowledgeStats,
  MemoryMessage,
  SloSnapshot,
} from './types'

function requestId(): string {
  return globalThis.crypto?.randomUUID?.()
    ?? `web-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function cleanBaseUrl(value: string): string {
  const trimmed = value.trim() || '/api'
  return trimmed.endsWith('/') ? trimmed.slice(0, -1) : trimmed
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly body?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export class SupportForgeApi {
  private readonly baseUrl: string

  constructor(private readonly settings: ConsoleSettings) {
    this.baseUrl = cleanBaseUrl(settings.apiBaseUrl)
  }

  private identityHeaders(): Record<string, string> {
    if (this.settings.accessToken?.trim()) {
      return { Authorization: `Bearer ${this.settings.accessToken.trim()}`, 'X-Request-Id': requestId() }
    }
    // 尚未登录时只发请求编号，让后端正常返回401。
    // 不把“未登录”等界面占位文字放进HTTP头，否则浏览器会因非Latin-1字符在发送前就报错。
    if (!this.settings.roles.length || !this.settings.actorId || this.settings.actorId === '未登录') {
      return { 'X-Request-Id': requestId() }
    }
    return {
      'X-Tenant-Id': this.settings.tenantId,
      'X-Actor-Id': this.settings.actorId,
      'X-Roles': this.settings.roles.join(','),
      'X-Request-Id': requestId(),
    }
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')

    for (const [name, value] of Object.entries(this.identityHeaders())) {
      headers.set(name, value)
    }

    if (init.body && !(init.body instanceof FormData)) {
      headers.set('Content-Type', 'application/json')
    }

    let response: Response
    try {
      response = await fetch(`${this.baseUrl}${path}`, { ...init, headers })
    } catch (cause) {
      throw new ApiError(
        '无法连接 SupportForge 后端，请确认服务已启动且 API 地址正确。',
        0,
        cause,
      )
    }

    if (response.status === 204) {
      return undefined as T
    }

    const text = await response.text()
    let body: unknown = undefined
    if (text) {
      try {
        body = JSON.parse(text)
      } catch {
        body = text
      }
    }

    if (!response.ok) {
      const candidate = typeof body === 'object' && body !== null
        ? body as Record<string, unknown>
        : {}
      const message = [candidate.message, candidate.detail, candidate.error]
        .find((value): value is string => typeof value === 'string' && value.length > 0)
        ?? (response.status === 401
          ? '访问令牌无效，请在企业设置中填写管理员发放的凭证。'
          : `请求失败（HTTP ${response.status}）`)
      throw new ApiError(message, response.status, body)
    }

    return body as T
  }

  health(): Promise<HealthResponse> {
    return this.request('/actuator/health/readiness')
  }

  identity(): Promise<AccessIdentity> { return this.request('/auth/me') }

  chat(payload: ChatRequest): Promise<ChatResponse> {
    return this.request('/chat', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  }

  conversationMessages(conversationId: string, userId: string): Promise<MemoryMessage[]> {
    return this.request(
      `/conversations/${encodeURIComponent(conversationId)}/messages?user_id=${encodeURIComponent(userId)}`,
    )
  }

  clearConversation(conversationId: string, userId: string): Promise<void> {
    return this.request(
      `/conversations/${encodeURIComponent(conversationId)}/messages?user_id=${encodeURIComponent(userId)}`,
      { method: 'DELETE' },
    )
  }

  submitFeedback(
    responseId: string,
    conversationId: string,
    rating: 'positive' | 'negative',
    comment = '',
  ): Promise<AnswerFeedback> {
    return this.request('/feedback', {
      method: 'POST',
      body: JSON.stringify({
        response_id: responseId,
        conversation_id: conversationId,
        rating,
        comment,
      }),
    })
  }

  feedbackSummary(conversationId?: string): Promise<FeedbackSummary> {
    const suffix = conversationId
      ? `?conversation_id=${encodeURIComponent(conversationId)}`
      : ''
    return this.request(`/feedback/summary${suffix}`)
  }

  knowledgeStats(): Promise<KnowledgeStats> {
    return this.request('/knowledge/stats')
  }

  listKnowledge(category: KnowledgeCategory | '', page = 1, size = 12): Promise<KnowledgeDocumentPage> {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (category) params.set('category', category)
    return this.request(`/knowledge/documents?${params.toString()}`)
  }

  getKnowledge(documentId: string): Promise<KnowledgeDocument> {
    return this.request(`/knowledge/documents/${encodeURIComponent(documentId)}`)
  }

  searchKnowledge(
    query: string,
    category: KnowledgeCategory | '',
    limit = 8,
  ): Promise<KnowledgeSearchResult[]> {
    const params = new URLSearchParams({ query, limit: String(limit) })
    if (category) params.set('category', category)
    return this.request(`/knowledge/search?${params.toString()}`)
  }

  addKnowledge(document: Omit<KnowledgeDocument, 'id'> & { id?: string }): Promise<KnowledgeDocument> {
    return this.request('/knowledge/documents', {
      method: 'POST',
      body: JSON.stringify(document),
    })
  }

  deleteKnowledge(documentId: string): Promise<void> {
    return this.request(`/knowledge/documents/${encodeURIComponent(documentId)}`, {
      method: 'DELETE',
    })
  }

  uploadKnowledge(file: File, category: KnowledgeCategory): Promise<KnowledgeImportResult> {
    const body = new FormData()
    body.append('file', file)
    body.append('category', category)
    return this.request('/knowledge/upload', { method: 'POST', body })
  }

  slo(): Promise<SloSnapshot> {
    return this.request('/operations/slo')
  }

  dependencies(): Promise<Record<string, unknown>> {
    return this.request('/operations/dependencies')
  }
}

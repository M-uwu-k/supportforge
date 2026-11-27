export type EnterpriseRole = 'ADMIN' | 'AGENT' | 'KNOWLEDGE_EDITOR' | 'AUDITOR'

export interface ConsoleSettings {
  accessToken: string
  apiBaseUrl: string
  tenantId: string
  actorId: string
  roles: EnterpriseRole[]
  userId: string
}

export interface HealthResponse {
  status: string
  components?: Record<string, unknown>
}

export interface AccessIdentity {
  tenant_id: string
  actor_id: string
  roles: EnterpriseRole[]
  token_required: boolean
}

export interface KnowledgeSource {
  id: string
  title: string
  category: 'GENERAL' | 'TECHNICAL' | 'BILLING'
  score: number
  matched_keywords: string[]
}

export interface ChatRequest {
  message: string
  user_id: string
  conversation_id?: string
}

export interface ChatResponse {
  conversation_id: string
  response_id: string
  response: string
  intent: string
  intent_group: string
  agent_type: string
  agent_types: string[]
  primary_agent: string
  supporting_agents: string[]
  routing_reason: string
  routing_confidence: number
  escalated: boolean
  latency_ms: number
  knowledge_used: boolean
  knowledge_sources: KnowledgeSource[]
  verified: boolean
  grounded: boolean
  verification_issues: string[]
  entities: Record<string, string[]>
  intent_confidence: number
  intent_source_scores: Record<string, number>
  retrieval_trace_id?: string | null
  grounding_status?: string
  evidence?: Array<{
    citation_id: string
    point_id: string
    external_document_id: string
    chunk_index: number
    content: string
    content_sha256: string
    rerank_score: number
  }>
}

export interface MemoryMessage {
  role: 'USER' | 'ASSISTANT'
  content: string
  created_at: string
}

export interface AnswerFeedback {
  id: string
  response_id: string
  conversation_id: string
  rating: 'positive' | 'negative'
  comment: string
  submitted_at: string
}

export interface FeedbackSummary {
  total: number
  positive: number
  negative: number
  satisfaction_rate: number
  recent_negative_comments: string[]
}

export type KnowledgeCategory = 'GENERAL' | 'TECHNICAL' | 'BILLING'

export interface KnowledgeDocument {
  id: string
  title: string
  category: KnowledgeCategory
  content: string
  keywords: string[]
}

export interface KnowledgeSearchResult {
  document: KnowledgeDocument
  score: number
  matched_keywords: string[]
}

export interface KnowledgeDocumentPage {
  documents: KnowledgeDocument[]
  total_elements: number
  page: number
  size: number
  total_pages: number
}

export interface KnowledgeStats {
  total_documents: number
}

export interface KnowledgeImportResult {
  total: number
  imported: number
  failed: number
  documents: KnowledgeDocument[]
  errors: string[]
}

export interface SloSnapshot {
  requests: number
  errors: number
  availability: number
  availability_target: number
  average_latency_ms: number
  maximum_latency_ms: number
  latency_target_ms: number
  availability_met: boolean
  average_latency_met: boolean
}

export interface ApiFailureBody {
  message?: string
  error?: string
  detail?: string
  path?: string
  status?: number
}

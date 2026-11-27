import { useCallback, useEffect, useState } from 'react'
import { SupportForgeApi } from '../api'
import { Icon } from '../components/Icon'
import type { FeedbackSummary, HealthResponse, SloSnapshot } from '../types'

interface OperationsPageProps { api: SupportForgeApi }

export function OperationsPage({ api }: OperationsPageProps) {
  const [health, setHealth] = useState<HealthResponse | null>(null)
  const [slo, setSlo] = useState<SloSnapshot | null>(null)
  const [dependencies, setDependencies] = useState<Record<string, unknown> | null>(null)
  const [feedback, setFeedback] = useState<FeedbackSummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [updatedAt, setUpdatedAt] = useState('')

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    const [healthResult, sloResult, dependencyResult, feedbackResult] = await Promise.allSettled([
      api.health(), api.slo(), api.dependencies(), api.feedbackSummary(),
    ])
    if (healthResult.status === 'fulfilled') setHealth(healthResult.value)
    if (sloResult.status === 'fulfilled') setSlo(sloResult.value)
    if (dependencyResult.status === 'fulfilled') setDependencies(dependencyResult.value)
    if (feedbackResult.status === 'fulfilled') setFeedback(feedbackResult.value)
    const failed = [healthResult, sloResult, dependencyResult, feedbackResult]
      .filter((result) => result.status === 'rejected').length
    if (failed > 0) setError(`${failed} 项运行数据读取失败，请检查企业身份角色和后端配置。`)
    setUpdatedAt(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
    setLoading(false)
  }, [api])

  useEffect(() => { void refresh() }, [refresh])

  const availability = slo ? `${(slo.availability * 100).toFixed(2)}%` : '—'
  const satisfaction = feedback ? `${(feedback.satisfaction_rate * 100).toFixed(1)}%` : '—'

  return (
    <div className="operations-page">
      <div className="operations-toolbar">
        <div><span className="eyebrow">最后刷新</span><strong>{updatedAt || '尚未刷新'}</strong></div>
        <button className="button secondary compact" onClick={() => void refresh()} disabled={loading}>
          <Icon name="refresh" size={16} /> {loading ? '刷新中…' : '刷新数据'}
        </button>
      </div>

      {error && <div className="inline-alert warning">{error}</div>}

      <div className="metric-grid">
        <article className="metric-card panel">
          <div className="metric-card-top"><span className="summary-icon green"><Icon name="activity" /></span><span className={`metric-state ${health?.status === 'UP' ? 'good' : 'bad'}`}>{health?.status ?? 'UNKNOWN'}</span></div>
          <strong>服务就绪状态</strong><p>Spring Boot readiness probe</p>
        </article>
        <article className="metric-card panel">
          <div className="metric-card-top"><span className="summary-icon indigo"><Icon name="shield" /></span><span className={`metric-state ${slo?.availability_met ? 'good' : 'bad'}`}>{slo?.availability_met ? '达标' : '观察中'}</span></div>
          <strong>{availability}</strong><p>请求可用性 · 目标 {slo ? `${(slo.availability_target * 100).toFixed(1)}%` : '—'}</p>
        </article>
        <article className="metric-card panel">
          <div className="metric-card-top"><span className="summary-icon amber"><Icon name="activity" /></span><span className={`metric-state ${slo?.average_latency_met ? 'good' : 'bad'}`}>{slo?.average_latency_met ? '达标' : '观察中'}</span></div>
          <strong>{slo ? `${slo.average_latency_ms.toFixed(0)} ms` : '—'}</strong><p>平均延迟 · 目标 {slo ? `${slo.latency_target_ms} ms` : '—'}</p>
        </article>
        <article className="metric-card panel">
          <div className="metric-card-top"><span className="summary-icon rose"><Icon name="thumb-up" /></span><span className="metric-state neutral">{feedback?.total ?? 0} 条反馈</span></div>
          <strong>{satisfaction}</strong><p>客户满意率</p>
        </article>
      </div>

      <div className="operations-grid">
        <section className="panel operations-section">
          <div className="section-heading"><div><span className="eyebrow">SLO 快照</span><h2>服务目标</h2></div><span className="tag neutral">实时进程数据</span></div>
          <div className="slo-list">
            <div className="slo-row"><div><strong>可用性</strong><small>{slo?.errors ?? 0} 错误 / {slo?.requests ?? 0} 请求</small></div><div className="slo-value">{availability}</div></div>
            <div className="slo-row"><div><strong>平均响应时间</strong><small>目标不高于 {slo?.latency_target_ms ?? '—'} ms</small></div><div className="slo-value">{slo ? `${slo.average_latency_ms.toFixed(0)} ms` : '—'}</div></div>
            <div className="slo-row"><div><strong>最大响应时间</strong><small>当前进程观测窗口</small></div><div className="slo-value">{slo ? `${slo.maximum_latency_ms} ms` : '—'}</div></div>
          </div>
        </section>

        <section className="panel operations-section">
          <div className="section-heading"><div><span className="eyebrow">客户声音</span><h2>反馈概览</h2></div><span className="tag indigo">最终评价</span></div>
          <div className="feedback-bars">
            <div><span>好评</span><div className="progress emerald"><i style={{ width: feedback?.total ? `${feedback.positive / feedback.total * 100}%` : '0%' }} /></div><strong>{feedback?.positive ?? 0}</strong></div>
            <div><span>差评</span><div className="progress rose"><i style={{ width: feedback?.total ? `${feedback.negative / feedback.total * 100}%` : '0%' }} /></div><strong>{feedback?.negative ?? 0}</strong></div>
          </div>
          <div className="negative-comments">
            {(feedback?.recent_negative_comments ?? []).slice(0, 3).map((comment, index) => <p key={index}>“{comment}”</p>)}
            {!feedback?.recent_negative_comments?.length && <div className="muted-empty">暂无带说明的差评</div>}
          </div>
        </section>

        <section className="panel operations-section full-width">
          <div className="section-heading"><div><span className="eyebrow">依赖诊断</span><h2>缓存与熔断快照</h2></div><span className="tag success">只读</span></div>
          <pre className="json-viewer">{dependencies ? JSON.stringify(dependencies, null, 2) : '尚未取得依赖快照'}</pre>
        </section>
      </div>
    </div>
  )
}

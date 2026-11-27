import { useEffect, useState, type FormEvent } from 'react'
import { Icon } from '../components/Icon'
import type { AccessIdentity, ConsoleSettings } from '../types'

interface SettingsPageProps {
  settings: ConsoleSettings
  onSave: (settings: ConsoleSettings) => void
  onCheck: (candidate: ConsoleSettings) => Promise<AccessIdentity>
}

export function SettingsPage({ settings, onSave, onCheck }: SettingsPageProps) {
  const [form, setForm] = useState(settings)
  const [checking, setChecking] = useState(false)
  const [message, setMessage] = useState('')
  const [verified, setVerified] = useState(false)
  useEffect(() => { setForm(settings) }, [settings])

  async function save(event: FormEvent) {
    event.preventDefault()
    setChecking(true)
    setMessage('')
    setVerified(false)
    try {
      const candidate = { ...form, accessToken: form.accessToken.trim(),
        apiBaseUrl: form.apiBaseUrl.trim() || '/api', userId: form.userId.trim() }
      const identity = await onCheck(candidate)
      const validated = { ...candidate, tenantId: identity.tenant_id,
        actorId: identity.actor_id, roles: identity.roles }
      onSave(validated)
      setForm(validated)
      setVerified(true)
      setMessage(`身份验证成功：${identity.actor_id}（${identity.roles.join('、')}）。`)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '验证失败，请检查访问令牌和API地址。')
    } finally { setChecking(false) }
  }

  function logout() {
    onSave({ ...form, accessToken: '', actorId: '未登录', roles: [] })
    setMessage('已清除当前浏览器会话的访问令牌。')
    setVerified(false)
  }

  return (
    <div className="settings-layout">
      <form className="panel settings-form" onSubmit={(event) => void save(event)}>
        <div className="section-heading"><div><span className="eyebrow">访问设置</span><h2>连接企业工作台</h2></div><span className="tag indigo">服务端验证身份</span></div>
        <div className="form-section">
          <label>API 基础地址<span>通过局域网网页使用时保持 /api</span><input value={form.apiBaseUrl} onChange={(event) => setForm({ ...form, apiBaseUrl: event.target.value })} placeholder="/api" /></label>
          <label>访问令牌<span>向管理员索取相应角色的凭证；仅保存在当前浏览器标签页会话中</span><input type="password" autoComplete="off" value={form.accessToken} onChange={(event) => setForm({ ...form, accessToken: event.target.value })} placeholder="粘贴访问令牌" /></label>
          <label>聊天客户 ID<span>用于关联客户的会话记忆</span><input value={form.userId} onChange={(event) => setForm({ ...form, userId: event.target.value })} required /></label>
        </div>
        <div className="form-section">
          <div className="field-title"><strong>已验证身份</strong><span>租户和角色由后端分配，不能在浏览器自行提权</span></div>
          <div className="field-grid">
            <label>操作者<input value={settings.accessToken ? settings.actorId : '请验证访问令牌'} readOnly /></label>
            <label>角色<input value={settings.accessToken ? settings.roles.join('、') : '未验证'} readOnly /></label>
          </div>
          <label>租户 UUID<input value={settings.tenantId} readOnly /></label>
        </div>
        <div className="settings-actions">
          {settings.accessToken && <button type="button" className="button secondary" onClick={logout}>退出当前身份</button>}
          <button className="button primary" disabled={checking}>{checking ? '验证中…' : '验证并保存'}</button>
        </div>
        {message && <div className={`inline-alert ${verified ? 'success' : 'warning'}`}>{message}</div>}
      </form>
      <aside className="settings-side">
        <section className="panel guide-card">
          <div className="guide-icon"><Icon name="shield" /></div>
          <span className="eyebrow">局域网访问</span><h2>按职责分配凭证</h2>
          <ol className="flow-list">
            <li><span>1</span><div><strong>管理员保管管理令牌</strong><small>负责知识维护、访问授权、学习和评测</small></div></li>
            <li><span>2</span><div><strong>客服使用坐席令牌</strong><small>用于聊天及已授权知识，不具有管理角色</small></div></li>
            <li><span>3</span><div><strong>审计使用只读令牌</strong><small>查看运行状态和报告</small></div></li>
          </ol>
        </section>
        <section className="panel notice-card"><strong>首次连接</strong><p>请管理员在服务器项目的 data/lan-access/tokens.json 中取出对应凭证，分别发给使用人员。不要把整个凭证文件交给普通用户。</p></section>
      </aside>
    </div>
  )
}

import { useEffect, useMemo, useState } from 'react'
import { ApiError, SupportForgeApi } from './api'
import { Icon, type IconName } from './components/Icon'
import { ChatPage } from './pages/ChatPage'
import { KnowledgePage } from './pages/KnowledgePage'
import { OperationsPage } from './pages/OperationsPage'
import { SettingsPage } from './pages/SettingsPage'
import type { ConsoleSettings } from './types'

export type PageKey = 'chat' | 'knowledge' | 'operations' | 'settings'

const SETTINGS_KEY = 'supportforge.console.settings.v1'

const defaultSettings: ConsoleSettings = {
  accessToken: '',
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || '/api',
  tenantId: '00000000-0000-0000-0000-000000000001',
  actorId: '未登录',
  roles: [],
  userId: 'customer-demo',
}

function readSettings(): ConsoleSettings {
  try {
    const saved = localStorage.getItem(SETTINGS_KEY)
    return { ...defaultSettings, ...(saved ? JSON.parse(saved) : {}),
      accessToken: sessionStorage.getItem('supportforge.access-token') || '' }
  } catch {
    return defaultSettings
  }
}

const navigation: Array<{
  key: PageKey
  label: string
  detail: string
  icon: IconName
}> = [
  { key: 'chat', label: '智能对话', detail: '客服协同', icon: 'chat' },
  { key: 'knowledge', label: '知识库', detail: '内容与检索', icon: 'book' },
  { key: 'operations', label: '运行中心', detail: 'SLO 与依赖', icon: 'activity' },
  { key: 'settings', label: '企业设置', detail: '身份与连接', icon: 'settings' },
]

const pageMeta: Record<PageKey, { title: string; subtitle: string }> = {
  chat: { title: '智能客服工作台', subtitle: '基于意图、记忆、知识证据和多 Agent 协同生成回答' },
  knowledge: { title: '企业知识库', subtitle: '管理经过版本化、可检索和可追溯的客服知识' },
  operations: { title: '运行中心', subtitle: '查看服务健康、SLO、反馈和依赖状态' },
  settings: { title: '企业连接设置', subtitle: '配置 API、租户身份和操作角色' },
}

type ConnectionState = 'checking' | 'online' | 'offline' | 'auth-required'

const connectionLabels: Record<ConnectionState, string> = {
  checking: '正在检测',
  online: '后端在线',
  offline: '后端离线',
  'auth-required': '后端在线 · 待验证',
}

export default function App() {
  const [page, setPage] = useState<PageKey>('chat')
  const [settings, setSettings] = useState<ConsoleSettings>(readSettings)
  const [connection, setConnection] = useState<ConnectionState>('checking')
  const [mobileOpen, setMobileOpen] = useState(false)

  const api = useMemo(() => new SupportForgeApi(settings), [settings])
  // 菜单切换保留同一聊天实例；切换客户、租户、操作身份或后端时才创建新的会话界面。
  const chatScope = JSON.stringify([
    settings.apiBaseUrl, settings.tenantId, settings.actorId, settings.userId,
  ])

  useEffect(() => {
    let active = true
    setConnection('checking')
    api.identity()
      .then(() => active && setConnection('online'))
      .catch((error: unknown) => {
        if (!active) return
        // 401/403已证明后端可达，表示浏览器尚未通过身份校验，不能把它当成服务断线。
        const needsAuth = error instanceof ApiError && (error.status === 401 || error.status === 403)
        setConnection(needsAuth ? 'auth-required' : 'offline')
        setPage('settings')
      })
    return () => { active = false }
  }, [api])

  function navigate(next: PageKey) {
    setPage(next)
    setMobileOpen(false)
  }

  function saveSettings(next: ConsoleSettings) {
    setSettings(next)
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ ...next, accessToken: '' }))
    sessionStorage.setItem('supportforge.access-token', next.accessToken)
  }

  const meta = pageMeta[page]

  return (
    <div className="app-shell">
      <div
        className={`mobile-overlay ${mobileOpen ? 'visible' : ''}`}
        onClick={() => setMobileOpen(false)}
      />

      <aside className={`sidebar ${mobileOpen ? 'open' : ''}`}>
        <div className="brand">
          <div className="brand-mark"><Icon name="bot" size={25} /></div>
          <div>
            <strong>SupportForge</strong>
            <span>Enterprise AI Support</span>
          </div>
        </div>

        <div className="workspace-label">工作空间</div>
        <nav className="nav-list" aria-label="主导航">
          {navigation.map((item) => (
            <button
              key={item.key}
              className={`nav-item ${page === item.key ? 'active' : ''}`}
              onClick={() => navigate(item.key)}
            >
              <span className="nav-icon"><Icon name={item.icon} size={19} /></span>
              <span className="nav-copy">
                <strong>{item.label}</strong>
                <small>{item.detail}</small>
              </span>
              {page === item.key && <Icon name="chevron" size={15} />}
            </button>
          ))}
        </nav>

        <div className="sidebar-spacer" />
        <div className="enterprise-card">
          <div className="enterprise-card-icon"><Icon name="shield" size={19} /></div>
          <div>
            <strong>企业安全上下文</strong>
            <span>{settings.roles.length} 个角色 · 租户隔离已配置</span>
          </div>
        </div>

        <div className="sidebar-identity">
          <div className="avatar">{settings.actorId.slice(0, 2).toUpperCase()}</div>
          <div className="identity-copy">
            <strong>{settings.actorId}</strong>
            <span>{settings.roles[0] || '未设置角色'}</span>
          </div>
          <button className="icon-button dark" onClick={() => navigate('settings')} aria-label="打开设置">
            <Icon name="settings" size={17} />
          </button>
        </div>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <button className="mobile-menu" onClick={() => setMobileOpen(true)} aria-label="打开导航">
            <Icon name="menu" />
          </button>
          <div className="page-heading">
            <h1>{meta.title}</h1>
            <p>{meta.subtitle}</p>
          </div>
          <div className={`connection-pill ${connection}`}>
            <span className="status-dot" />
            {connectionLabels[connection]}
          </div>
        </header>

        <section className="page-content">
          {connection === 'auth-required' && (
            <div className="inline-alert warning" role="status">
              后端连接正常。请在企业设置中填写有效的访问令牌，然后点击“验证并保存”。
            </div>
          )}
          {/* 隐藏页面而不卸载，保留消息、草稿、反馈和进行中的请求；新会话仍由聊天页按钮显式重置。 */}
          <div hidden={page !== 'chat'}>
            <ChatPage key={chatScope} api={api} settings={settings} />
          </div>
          {page === 'knowledge' && <KnowledgePage api={api} roles={settings.roles} />}
          {page === 'operations' && <OperationsPage api={api} />}
          {page === 'settings' && (
            <SettingsPage settings={settings} onSave={saveSettings} onCheck={(candidate) => new SupportForgeApi(candidate).identity()} />
          )}
        </section>
      </main>
    </div>
  )
}

import { useState, useEffect, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import PersonaEditPanel from '../components/PersonaEditPanel'
import { useToast } from '../components/Toast'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'

export default function PersonaDetail() {
  const { id } = useParams()
  const toast = useToast()
  const { t } = useLocale()
  const [persona, setPersona] = useState(null)
  const [events, setEvents] = useState([])
  const [thought, setThought] = useState(null)
  const [qqInput, setQqInput] = useState(persona?.aiQq || '')
  const [ownerInput, setOwnerInput] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [binding, setBinding] = useState(false)
  const [error, setError] = useState('')

  const bindQQ = async (qq, type = 'qq') => {
    if (binding) return
    const account = qq.trim()
    if (account && !/^\d{5,15}$/.test(account)) {
      toast.error(t('personaDetail.qqInvalid'))
      return
    }
    setBinding(true)
    try {
      await requestJson(`/api/personas/${id}/bind-channel`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type, account }),
      })
      setPersona(await requestJson(`/api/personas/${id}`))
      toast.success(t('personaDetail.qqBindSuccess'))
    } catch {
      setQqInput(persona?.aiQq || '')
      setOwnerInput(persona?.ownerQq || '')
      toast.error(t('personaDetail.qqBindFailed'))
    } finally {
      setBinding(false)
    }
  }

  const [loading, setLoading] = useState(true)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [relationState, setRelationState] = useState(null)

  // 当 persona 数据从外部更新时，同步 QQ 输入框状态
  useEffect(() => {
    setQqInput(persona?.aiQq || '')
    setOwnerInput(persona?.ownerQq || '')
  }, [persona?.aiQq, persona?.ownerQq])

  const refreshData = useCallback(async (signal) => {
    setRefreshing(true)
    setError('')
    try {
      const [data, evts, tht, rel] = await Promise.all([
        requestJson(`/api/personas/${id}`, { signal }),
        requestJson(`/api/events/${id}/today`, { signal }),
        requestJson(`/api/events/${id}/thought`, { signal }),
        requestJson(`/api/relationships/${id}`, { signal }),
      ])
      if (signal?.aborted) return
      setPersona(data)
      setEvents(Array.isArray(evts) ? evts : [])
      setThought(tht?.raw_thought || tht?.detailJson ? tht : null)
      setRelationState(rel)
    } catch (err) {
      if (!signal?.aborted) setError(err.message)
    } finally {
      if (!signal?.aborted) {
        setRefreshing(false)
        setLoading(false)
      }
    }
  }, [id])

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setPersona(null)
    refreshData(controller.signal)
    return () => controller.abort()
  }, [refreshData])

  if (loading) return (
    <div className="animate-pulse max-w-3xl mx-auto pt-8 space-y-6">
      <div className="h-8 w-48 bg-jade-600/6 rounded-full" />
      <div className="glass-card rounded-2xl p-8 space-y-4">
        <div className="h-4 w-64 bg-jade-600/6 rounded-full" />
        <div className="h-4 w-48 bg-jade-600/6 rounded-full" />
        <div className="h-4 w-56 bg-jade-600/6 rounded-full" />
      </div>
    </div>
  )

  if (!persona) return (
    <div className="glass-card rounded-3xl p-12 text-center max-w-md mx-auto mt-20">
      <h3 className="font-serif text-lg text-ink mb-2">{t('common.notFound')}</h3>
      {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}
      <button onClick={() => refreshData()} className="btn-jade text-sm mr-2">{t('common.retry')}</button>
      <Link to="/" className="btn-jade text-sm inline-block">{t('common.backToList')}</Link>
    </div>
  )

  const ctx = persona.characterCurrentContext || ''
  const name = persona.name || ctx || t('personaList.unknownName')
  const status = persona.status
  const trust = relationState?.trust ?? 0
  const closeness = relationState?.closeness ?? 0
  const tension = relationState?.tension ?? 0

  return (
    <div className="animate-fade-up max-w-3xl mx-auto">
      {/* 顶部导航 */}
      <div className="flex items-center justify-between mb-6 pt-4">
        <Link to="/" className="text-sm text-ink-soft hover:text-jade-600 transition-colors">{'← ' + t('common.back')}</Link>
        <button onClick={() => refreshData()} disabled={refreshing} className="text-sm text-jade-600 disabled:opacity-40 ml-auto mr-3">{t('common.refresh')}</button>
        <button onClick={() => setDrawerOpen(true)}
          aria-label={t('editPanel.title')}
          className="w-9 h-9 rounded-full flex items-center justify-center hover:bg-jade-50 transition-colors">
          <svg className="w-5 h-5 text-ink-soft" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
            <path strokeLinecap="round" strokeLinejoin="round" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.24-.438.613-.431.992a6.759 6.759 0 010 .255c-.007.378.138.75.43.99l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.99l-1.004-.828a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.281z" />
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </button>
      </div>
      {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}

      {/* 头部 */}
      <div className="glass-card rounded-2xl p-6 mb-5">
        <div className="flex items-start gap-4">
          <div className="w-16 h-16 rounded-2xl flex items-center justify-center text-white font-serif text-2xl font-semibold"
               style={{ background: 'linear-gradient(135deg, #52b788, #2d6a4f)' }}>
            {name?.[0] || '?'}
          </div>
          <div>
            <h1 className="font-serif text-2xl font-semibold text-jade-700">{name}</h1>
            <p className="text-ink-soft text-sm mt-1 line-clamp-2">{ctx}</p>
            <div className="flex items-center gap-2 mt-2 flex-wrap">
              <span className={`inline-block w-2 h-2 rounded-full ${
                status === 'active' ? 'bg-jade-400' : status === 'paused' ? 'bg-amber-warm' : 'bg-ink-soft/20'
              }`} />
              <span className="text-xs text-ink-soft/60">
                {status === 'active' ? t('personaDetail.statusActive') : status === 'paused' ? t('personaDetail.statusPaused') : t('personaDetail.statusArchived')}
              </span>
            </div>
          </div>
        </div>
      </div>

      <div className="glass-card rounded-2xl p-6 mb-5">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-3">{t('editPanel.channelBinding')}</h2>
        <p className="text-xs text-ink-soft mb-4">{t('personaDetail.qqBindingHint')}</p>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label htmlFor="bot-qq" className="block text-xs text-ink-soft mb-1">{t('editPanel.aiQq')}</label>
            <input id="bot-qq" value={qqInput} inputMode="numeric" disabled={binding}
              onChange={e => setQqInput(e.target.value)}
              onBlur={e => { if (e.target.value.trim() !== (persona.aiQq || '')) bindQQ(e.target.value) }}
              placeholder={t('personaDetail.qqPlaceholder')}
              className="w-full min-w-0 rounded-xl px-3 py-2.5 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40" />
          </div>
          <div>
            <label htmlFor="owner-qq" className="block text-xs text-ink-soft mb-1">{t('editPanel.ownerQq')}</label>
            <input id="owner-qq" value={ownerInput} inputMode="numeric" disabled={binding}
              onChange={e => setOwnerInput(e.target.value)}
              onBlur={e => { if (e.target.value.trim() !== (persona.ownerQq || '')) bindQQ(e.target.value, 'owner-qq') }}
              placeholder={t('editPanel.ownerQqPlaceholder')}
              className="w-full min-w-0 rounded-xl px-3 py-2.5 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40" />
          </div>
        </div>
        {(!persona.aiQq || !persona.ownerQq) && <p className="text-xs text-amber-700 mt-3">{t('personaDetail.qqIncomplete')}</p>}
      </div>

      {/* 关系阶段可视化 */}
      <RelationshipPhase phase={persona.relationshipPhase || 'stranger'} />

      {/* 关系仪表盘 */}
      <div className="glass-card rounded-2xl p-6 mb-5">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-5">{t('personaDetail.relationship')}</h2>
        <div className="space-y-4">
          <RelationBar label={t('personaDetail.trust')} value={trust} color="#2d6a4f" />
          <RelationBar label={t('personaDetail.closeness')} value={closeness} color="#52b788" />
          <RelationBar label={t('personaDetail.tension')} value={tension} color="#e6a817" max={100} />
        </div>
        {thought && (
          <div className="mt-5 p-4 rounded-xl bg-jade-50/50">
            <p className="text-xs text-ink-soft/60 mb-1">{t('personaDetail.recentMood')}</p>
            <p className="text-sm text-ink leading-relaxed italic">"{thought.raw_thought || thought.detailJson}"</p>
          </div>
        )}
      </div>

      {/* 今日事件线 */}
      <div className="glass-card rounded-2xl p-6 mb-5">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-5">{t('personaDetail.todayEvents')}</h2>
        {events.length === 0 ? (
          <p className="text-ink-soft text-sm">{t('personaDetail.noEvents')}</p>
        ) : (
          <EventTimeline events={events} />
        )}
      </div>

      {/* 测试聊天 */}
      <TestChat personaId={id} personaName={name} toast={toast} />

      {/* 操作区 */}
      <div className="flex items-center flex-wrap gap-3 mb-12">
        <button
          onClick={async () => {
            try {
              await requestJson(`/api/personas/${id}/toggle`, { method: 'POST' })
              await refreshData()
            } catch { toast.error(t('settings.saveFailed')) }
          }}
          className={`px-5 py-2.5 rounded-full text-sm font-medium transition-all ${
            status === 'active'
              ? 'bg-jade-50 text-jade-600 hover:bg-jade-100'
              : 'bg-amber-50 text-amber-600 hover:bg-amber-100'
          }`}>
          {status === 'active' ? t('personaDetail.togglePause') : t('personaDetail.toggleResume')}
        </button>
        <button
          onClick={async () => {
            try {
              const r = await fetch(`/api/personas/${id}/export`)
              if (!r.ok) throw new Error(`HTTP ${r.status}`)
              const url = URL.createObjectURL(await r.blob())
              const a = document.createElement('a')
              a.href = url
              a.download = `${name || 'persona'}.json`
              a.click()
              URL.revokeObjectURL(url)
            } catch { toast.error(t('settings.saveFailed')) }
          }}
          className="px-5 py-2.5 rounded-full text-sm font-medium bg-jade-50 text-jade-600 hover:bg-jade-100 transition-all">
          {t('personaDetail.export')}
        </button>
        <button
          onClick={async () => {
            if (!window.confirm(t('personaDetail.confirmArchive'))) return
            try {
              await requestJson(`/api/personas/${id}/archive`, { method: 'POST' })
              window.location.href = '/'
            } catch { toast.error(t('settings.saveFailed')) }
          }}
          className="px-5 py-2.5 rounded-full text-sm font-medium bg-red-50 text-red-400 hover:bg-red-100 transition-all ml-auto">
          {t('common.archive')}
        </button>
      </div>

      {/* 编辑面板 */}
      {drawerOpen && (
        <PersonaEditPanel persona={persona} onClose={() => setDrawerOpen(false)}
          onSaved={() => refreshData()} />
      )}
    </div>
  )
}

// ============================================================
// 事件时间轴组件
// ============================================================

function EventTimeline({ events }) {
  const { t } = useLocale()
  const now = new Date()
  const currentMinutes = now.getHours() * 60 + now.getMinutes()

  // 计算当前时间在事件列表中的位置（用于"现在线"）
  let nowLinePosition = -1
  for (let i = 0; i < events.length; i++) {
    const timeStr = events[i].eventTime || events[i].time || '00:00'
    const [h, m] = timeStr.split(':').map(Number)
    const eventMinutes = (h || 0) * 60 + (m || 0)
    if (currentMinutes < eventMinutes) {
      nowLinePosition = i
      break
    }
  }
  if (nowLinePosition === -1) nowLinePosition = events.length

  return (
    <div className="relative">
      {/* 垂直连接线 */}
      <div className="absolute left-[52px] top-0 bottom-0 w-px bg-jade-100" />

      {/* 现在线（红色虚线，标记当前时间位置） */}
      {nowLinePosition > 0 && nowLinePosition <= events.length && (
        <div
          className="absolute left-[44px] right-0 border-t-2 border-dashed border-red-300/50 z-10 flex items-center"
          style={{ top: `${nowLinePosition * 64 + 20}px` }}
        >
          <span className="text-[10px] text-red-400 bg-white/80 px-1.5 py-0.5 rounded-full ml-2">
            {t('personaDetail.now')} {String(now.getHours()).padStart(2, '0')}:{String(now.getMinutes()).padStart(2, '0')}
          </span>
        </div>
      )}

      <div className="space-y-0">
        {events.map((ev, i) => {
          const timeStr = ev.eventTime || ev.time || '00:00'
          const [eh, em] = timeStr.split(':').map(Number)
          const eventMinutes = (eh || 0) * 60 + (em || 0)

          const nextEv = events[i + 1]
          const nextTimeStr = nextEv ? (nextEv.eventTime || nextEv.time || '23:59') : '23:59'
          const [nh, nm] = nextTimeStr.split(':').map(Number)
          const nextEventMinutes = (nh || 23) * 60 + (nm || 59)

          const isActive = currentMinutes >= eventMinutes && currentMinutes < nextEventMinutes
          const isDone = currentMinutes >= nextEventMinutes
          const isFuture = currentMinutes < eventMinutes
          const evType = ev.eventType || ev.type

          // 圆点大小根据事件类型变化
          const dotSize = evType === 'sleep' ? 'w-4 h-4' : evType === 'moment' ? 'w-3 h-3' : 'w-2.5 h-2.5'

          return (
            <div key={i} className="flex items-start gap-4 py-3 relative">
              {/* 左侧时间 */}
              <div className="w-12 text-right flex-shrink-0">
                <span className={`text-xs font-mono ${isFuture ? 'text-ink-soft/30' : 'text-ink-soft'}`}>
                  {timeStr}
                </span>
              </div>

              {/* 中间圆点 */}
              <div className="flex flex-col items-center flex-shrink-0 relative z-10">
                <div className={`${dotSize} rounded-full mt-0.5 transition-all duration-500 ${
                  isDone ? 'bg-jade-400' :
                  isActive ? 'bg-jade-400 ring-2 ring-jade-400/30 animate-pulse' :
                  'bg-jade-200'
                }`} />
              </div>

              {/* 右侧内容 */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-0.5">
                  <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${
                    evType === 'sleep' ? 'bg-indigo-50 text-indigo-600' :
                    evType === 'moment' ? 'bg-amber-50 text-amber-600' :
                    'bg-jade-50 text-jade-600'
                  }`}>
                    {evType}
                  </span>
                  {isDone && <span className="text-[10px] text-jade-400">✓</span>}
                  {isActive && <span className="text-[10px] text-jade-500 animate-pulse font-medium">{'● ' + t('personaDetail.atThisMoment')}</span>}
                </div>
                <p className={`text-sm leading-relaxed ${isFuture ? 'text-ink-soft/30' : 'text-ink'}`}>
                  {ev.description || ev.desc || ''}
                </p>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function RelationBar({ label, value, color, max = 100 }) {
  const pct = Math.min(100, (value / max) * 100)
  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <span className="text-sm text-ink">{label}</span>
        <span className="text-sm font-mono text-ink-soft">{value}</span>
      </div>
      <div className="h-2 bg-jade-600/6 rounded-full overflow-hidden">
        <div className="h-full rounded-full transition-all duration-700"
             style={{ width: `${pct}%`, background: color }} />
      </div>
    </div>
  )
}

// ============================================================
// 关系阶段可视化组件
// ============================================================

const PHASE_CONFIG = [
  { key: 'stranger', icon: '👋' },
  { key: 'acquaintance', icon: '🤝' },
  { key: 'friend', icon: '💬' },
  { key: 'close_friend', icon: '🔒' },
]

function RelationshipPhase({ phase }) {
  const { t } = useLocale()
  const currentIndex = PHASE_CONFIG.findIndex(p => p.key === phase)

  const phaseLabels = {
    stranger: t('personaList.phaseStranger'),
    acquaintance: t('personaList.phaseAcquaintance'),
    friend: t('personaList.phaseFriend'),
    close_friend: t('personaList.phaseCloseFriend'),
  }

  const phaseDescs = {
    stranger: t('personaDetail.phaseStrangerDesc'),
    acquaintance: t('personaDetail.phaseAcquaintanceDesc'),
    friend: t('personaDetail.phaseFriendDesc'),
    close_friend: t('personaDetail.phaseCloseFriendDesc'),
  }

  return (
    <div className="glass-card rounded-2xl p-6 mb-5">
      <h2 className="font-serif text-lg font-semibold text-jade-600 mb-5">{t('personaDetail.phaseSectionTitle')}</h2>
      <div className="relative">
        {/* 连接线 */}
        <div className="absolute top-6 left-0 right-0 h-0.5 bg-jade-100" />
        <div
          className="absolute top-6 left-0 h-0.5 bg-jade-400 transition-all duration-700"
          style={{ width: `${Math.max(0, (currentIndex / (PHASE_CONFIG.length - 1)) * 100)}%` }}
        />

        {/* 阶段节点 */}
        <div className="relative flex justify-between">
          {PHASE_CONFIG.map((p, i) => {
            const isCurrent = i === currentIndex
            const isDone = i < currentIndex
            const isFuture = i > currentIndex

            return (
              <div key={p.key} className="flex flex-col items-center flex-1 group">
                {/* 圆点 */}
                <div
                  className={`w-12 h-12 rounded-full flex items-center justify-center text-lg transition-all duration-500 z-10 ${
                    isCurrent
                      ? 'bg-jade-400 text-white ring-4 ring-jade-200 shadow-lg'
                      : isDone
                        ? 'bg-jade-300 text-white'
                        : 'bg-white border-2 border-jade-100 text-jade-200'
                  }`}
                >
                  {isDone ? '✓' : p.icon}
                </div>

                {/* 标签 */}
                <span className={`text-xs font-medium mt-2 transition-colors ${
                  isCurrent ? 'text-jade-600' : isDone ? 'text-jade-500' : 'text-ink-soft/30'
                }`}>
                  {phaseLabels[p.key]}
                </span>

                {/* 描述（仅当前阶段显示） */}
                {isCurrent && (
                  <span className="text-[10px] text-ink-soft/50 mt-0.5 text-center max-w-[80px]">
                    {phaseDescs[p.key]}
                  </span>
                )}

                {/* hover 提示 */}
                <div className="absolute opacity-0 group-hover:opacity-100 transition-opacity -top-8 bg-jade-700 text-white text-[10px] px-2 py-1 rounded-lg pointer-events-none whitespace-nowrap z-20">
                  {phaseDescs[p.key]}
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

// ============================================================
// 测试聊天组件
// ============================================================

function TestChat({ personaId, personaName, toast }) {
  const { t } = useLocale()
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [expanded, setExpanded] = useState(false)

  const sendMessage = async () => {
    if (!input.trim() || sending) return
    const userMsg = input.trim()
    setInput('')
    setSending(true)

    // 先显示用户消息
    setMessages(prev => [...prev, { role: 'user', text: userMsg }])

    try {
      const res = await fetch('/api/sim/message', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ personaId, message: userMsg }),
      })
      const data = await res.json()

      if (data.ok) {
        // 模拟延迟后获取 AI 回复（实际回复通过消息队列异步发送）
        // 这里简单显示一个提示，让用户知道消息已发送
        setMessages(prev => [...prev, {
          role: 'assistant',
          text: t('personaDetail.testChatSent', { name: personaName }),
        }])
        toast.info(t('personaDetail.testChatSentToast'))
      } else {
        setMessages(prev => [...prev, {
          role: 'assistant',
          text: t('personaDetail.testChatError', { error: data.error || '未知错误' }),
        }])
        toast.error(data.error || t('personaDetail.sendFailed'))
      }
    } catch (e) {
      setMessages(prev => [...prev, {
        role: 'assistant',
        text: t('personaDetail.networkErrorDetail') + `: ${e.message}`,
      }])
      toast.error(t('personaDetail.networkError'))
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="glass-card rounded-2xl p-6 mb-5">
      <div className="flex items-center justify-between mb-4">
        <h2 className="font-serif text-lg font-semibold text-jade-600">{t('personaDetail.testChat')}</h2>
        <button
          onClick={() => setExpanded(!expanded)}
          className="text-xs text-jade-600 hover:text-jade-700 transition-colors"
        >
          {expanded ? t('personaDetail.testChatCollapse') : t('personaDetail.testChatExpand')}
        </button>
      </div>

      {expanded && (
        <div className="animate-fade-up">
          {/* 对话展示区 */}
          <div className="space-y-3 mb-4 max-h-64 overflow-y-auto">
            {messages.length === 0 ? (
              <p className="text-sm text-ink-soft/50 text-center py-4">
                {t('personaDetail.testChatExpandedDesc', { name: personaName })}
              </p>
            ) : (
              messages.map((msg, i) => (
                <div
                  key={i}
                  className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  <div
                    className={`max-w-[80%] px-4 py-2.5 rounded-2xl text-sm ${
                      msg.role === 'user'
                        ? 'chat-bubble-user text-white'
                        : 'chat-bubble-matchmaker text-ink'
                    }`}
                  >
                    {msg.text}
                  </div>
                </div>
              ))
            )}
          </div>

          {/* 输入区 */}
          <div className="flex items-center gap-2">
            <input
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && sendMessage()}
              placeholder={t('personaDetail.testChatPlaceholder', { name: personaName })}
              disabled={sending}
              className="flex-1 rounded-xl px-4 py-2.5 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 placeholder:text-ink-soft/40 transition-all disabled:opacity-50"
            />
            <button
              onClick={sendMessage}
              disabled={sending || !input.trim()}
              className="btn-jade text-sm py-2.5 px-5 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {sending ? t('personaDetail.testChatSending') : t('personaDetail.testChatSend')}
            </button>
          </div>
        </div>
      )}

      {!expanded && (
        <p className="text-sm text-ink-soft/60">
          {t('personaDetail.testChatCollapsedDesc', { name: personaName })}
        </p>
      )}
    </div>
  )
}

import { useState, useEffect, useRef, useCallback } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import SkeletonCard from '../components/SkeletonCard'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'

export default function PersonaList() {
  const { t } = useLocale()

  const [personas, setPersonas] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  const [sortBy, setSortBy] = useState('default')
  const [menuOpen, setMenuOpen] = useState(null)
  const menuRef = useRef(null)
  const navigate = useNavigate()

  const sortOptions = [
    { key: 'default', label: t('personaList.sortDefault') },
    { key: 'active', label: t('personaList.sortActive') },
    { key: 'trust', label: t('personaList.sortTrust') },
    { key: 'closeness', label: t('personaList.sortCloseness') },
    { key: 'name', label: t('personaList.sortName') },
  ]

  const load = useCallback(async (signal) => {
    setLoading(true)
    setError('')
    try {
      const data = await requestJson('/api/personas', { signal })
      if (!Array.isArray(data)) throw new Error('Invalid persona list')
      const populated = await Promise.all(data.map(async p => {
        try {
          const state = await requestJson(`/api/relationships/${p.id}`, { signal })
          return { ...p, trustScore: state.trust ?? 0, closenessScore: state.closeness ?? 0 }
        } catch { return p }
      }))
      if (!signal?.aborted) setPersonas(populated)
    } catch (err) {
      if (!signal?.aborted) setError(err.message)
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    load(controller.signal)
    return () => controller.abort()
  }, [load])

  useEffect(() => {
    const handler = (e) => { if (menuRef.current && !menuRef.current.contains(e.target)) setMenuOpen(null) }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  const filtered = personas.filter(p => {
    if (!search.trim()) return true
    const text = `${p.name || ''} ${p.characterCurrentContext || ''}`.toLowerCase()
    return text.includes(search.trim().toLowerCase())
  })

  const sorted = [...filtered].sort((a, b) => {
    switch (sortBy) {
      case 'active':
        return (Date.parse(b.lastUserMessageTime || '') || 0) - (Date.parse(a.lastUserMessageTime || '') || 0)
      case 'trust': return (b.trustScore ?? 0) - (a.trustScore ?? 0)
      case 'closeness': return (b.closenessScore ?? 0) - (a.closenessScore ?? 0)
      case 'name':
        return (getName(a, t('personaList.unknownName')) || '').localeCompare(getName(b, t('personaList.unknownName')) || '', 'zh-CN')
      default:
        return (Date.parse(b.createdAt || '') || 0) - (Date.parse(a.createdAt || '') || 0)
    }
  })

  const handleToggle = async (e, persona) => {
    e.stopPropagation()
    try {
      const j = await requestJson(`/api/personas/${persona.id}/toggle`, { method: 'POST' })
      setPersonas(prev => prev.map(p => p.id === persona.id ? { ...p, status: j.new_status } : p))
    } catch (err) { setError(err.message) }
  }

  const handleExport = async (persona) => {
    try {
    const r = await fetch(`/api/personas/${persona.id}/export`)
    if (!r.ok) throw new Error(`HTTP ${r.status}`)
    const text = await r.text()
    const blob = new Blob([text], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${getName(persona, t('personaList.unknownName')) || 'persona'}.json`
    a.click()
    URL.revokeObjectURL(url)
    setMenuOpen(null)
    } catch (err) { setError(err.message) }
  }

  const handleArchive = async (persona) => {
    if (!window.confirm(t('personaList.confirmArchive'))) return
    try {
      await requestJson(`/api/personas/${persona.id}/archive`, { method: 'POST' })
      setPersonas(prev => prev.filter(p => p.id !== persona.id))
      setMenuOpen(null)
    } catch (err) { setError(err.message) }
  }

  const handleImport = () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.json'
    input.onchange = async (e) => {
      const file = e.target.files[0]
      if (!file) return
      const text = await file.text()
      try {
        const j = await requestJson('/api/personas/import', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: text,
        })
        if (j.status === 'ok') {
          const newPersona = await requestJson(`/api/personas/${j.id}`)
          setPersonas(prev => [newPersona, ...prev])
        }
      } catch (err) {
        setError(t('personaList.importFail'))
      }
    }
    input.click()
  }

  return (
    <div className="animate-fade-up">
      {/* 标题栏 */}
      <div className="mb-8 pt-4">
        <div className="flex items-end justify-between flex-wrap gap-4 mb-4">
          <div>
            <h1 className="font-serif text-3xl font-semibold text-jade-700 tracking-wide">
              {t('personaList.title')}
            </h1>
            <p className="text-ink-soft mt-2 text-sm">
              {personas.length > 0
                ? t('personaList.countStatus', { total: personas.length, active: personas.filter(p => p.status === 'active').length })
                : t('personaList.subtitleEmpty')}
            </p>
          </div>
          <div className="flex items-center flex-wrap gap-2">
            <button onClick={() => load()} disabled={loading} className="text-sm text-jade-600 disabled:opacity-40">{t('common.refresh')}</button>
            <button onClick={handleImport}
              className="px-4 py-2.5 rounded-full text-sm font-medium bg-jade-50 text-jade-600 hover:bg-jade-100 transition-all">
              {t('common.import')}
            </button>
            <Link to="/create" className="btn-jade text-sm">{t('nav.create')}</Link>
          </div>
        </div>

        {/* 搜索与排序 */}
        <div className="flex items-center gap-3">
          <div className="relative flex-1 max-w-sm">
            <svg className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-ink-soft/30" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
              <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
            </svg>
            <input
              value={search} onChange={e => setSearch(e.target.value)}
              placeholder={t('common.search')}
              className="w-full rounded-2xl pl-10 pr-4 py-2.5 text-sm bg-white/50 border border-jade-600/8 focus:outline-none focus:border-jade-400/30 placeholder:text-ink-soft/30 transition-all" />
          </div>
          <select
            value={sortBy}
            onChange={e => setSortBy(e.target.value)}
            className="rounded-2xl px-4 py-2.5 text-sm bg-white/50 border border-jade-600/8 focus:outline-none focus:border-jade-400/30 text-ink-soft cursor-pointer hover:bg-white/70 transition-all"
          >
            {sortOptions.map(opt => (
              <option key={opt.key} value={opt.key}>{opt.label}</option>
            ))}
          </select>
        </div>
      </div>
      {error && <div role="alert" className="text-sm text-red-600 mb-4">{error} <button onClick={() => load()} className="underline ml-3">{t('common.retry')}</button></div>}

      {/* 加载态 */}
      {loading && (
        <>
          <SkeletonCard />
          <div className="mt-4"><SkeletonCard /></div>
          <div className="mt-4"><SkeletonCard /></div>
        </>
      )}

      {/* 空状态 */}
      {!loading && personas.length === 0 && (
        <div className="animate-fade-up">
          {/* 视觉化引导区域 */}
          <div className="text-center py-16">
            {/* 抽象人物轮廓图标 */}
            <div className="relative w-24 h-24 mx-auto mb-8">
              <div className="absolute inset-0 rounded-3xl bg-jade-100/50 animate-pulse" />
              <div className="absolute inset-2 rounded-2xl bg-jade-50 flex items-center justify-center">
                <svg className="w-12 h-12 text-jade-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.2">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z" />
                </svg>
              </div>
              {/* 装饰性浮动点 */}
              <div className="absolute -top-1 -right-1 w-3 h-3 rounded-full bg-jade-300 animate-bounce" style={{ animationDuration: '2s' }} />
              <div className="absolute -bottom-1 -left-1 w-2 h-2 rounded-full bg-jade-200 animate-bounce" style={{ animationDuration: '2.5s', animationDelay: '0.5s' }} />
            </div>

            <h2 className="font-serif text-2xl font-semibold text-jade-700 mb-3">
              {t('welcome.title')}
            </h2>
            <p className="text-ink-soft text-sm max-w-sm mx-auto leading-relaxed mb-10">
              {t('welcome.desc1')}
              <br />{t('welcome.desc2')}
            </p>

            {/* 两个并列按钮 */}
            <div className="flex flex-col sm:flex-row items-center justify-center gap-4">
              <Link to="/create" className="btn-jade text-sm py-3 px-8 flex items-center gap-2">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M8.625 12a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 01-2.555-.337A5.972 5.972 0 015.41 20.97a5.969 5.969 0 01-.474-.065 4.48 4.48 0 00.978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z" />
                </svg>
                {t('welcome.matchmakerBtn')}
              </Link>
              <Link to="/manual-create" className="px-8 py-3 rounded-full text-sm font-medium bg-white/60 border border-jade-600/10 text-jade-600 hover:bg-jade-50 hover:border-jade-400/30 transition-all flex items-center gap-2">
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M11.42 15.17L17.25 21A2.652 2.652 0 0021 17.25l-5.877-5.877M11.42 15.17l2.496-3.03c.317-.384.74-.626 1.208-.766M11.42 15.17l-4.655 5.653a2.548 2.548 0 11-3.586-3.586l6.837-5.63m5.108-.233c.55-.164 1.163-.188 1.743-.14a4.5 4.5 0 004.486-6.336l-3.276 3.277a3.004 3.004 0 01-2.25-2.25l3.276-3.276a4.5 4.5 0 00-6.336 4.486c.091 1.076-.071 2.264-.904 2.95l-.102.085m-1.745 1.437L5.909 7.5H4.5L2.25 3.75l1.5-1.5L7.5 4.5v1.409l4.032 4.032m-1.745 1.437l1.745-1.437" />
                </svg>
                {t('welcome.manualBtn')}
              </Link>
            </div>

            {/* 底部提示 */}
            <p className="text-xs text-ink-soft/40 mt-8">
              {t('welcome.importHint')}
              <button onClick={handleImport} className="text-jade-500 hover:text-jade-600 underline mx-1">{t('welcome.importLink')}</button>
              {t('welcome.importFromFile')}
            </p>
          </div>
        </div>
      )}

      {/* 卡片网格 */}
      {!loading && sorted.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          {sorted.map((p, i) => (
            <div
              key={p.id}
              onClick={() => navigate(`/persona/${p.id}`)}
              className="glass-card rounded-2xl p-5 cursor-pointer group relative"
              style={{ animationDelay: `${i * 60}ms` }}
            >
              <div className="absolute inset-0 opacity-0 group-hover:opacity-100 transition-opacity duration-500 pointer-events-none"
                   style={{
                     background: 'linear-gradient(105deg, transparent 40%, rgba(82,183,136,0.05) 45%, rgba(82,183,136,0.08) 50%, rgba(82,183,136,0.05) 55%, transparent 60%)',
                     animation: 'sheen 2s ease-in-out infinite',
                   }} />

              <div className="flex items-start gap-4">
                <div
                  className="w-14 h-14 rounded-2xl flex-shrink-0 flex items-center justify-center text-white font-serif text-xl font-semibold"
                  style={{ background: `linear-gradient(135deg, #52b788, #2d6a4f)` }}>
                  {getName(p, t('personaList.unknownName'))?.[0] || '?'}
                </div>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <h3 className="font-serif text-lg font-semibold text-jade-700 truncate">
                      {getName(p, t('personaList.unknownName'))}
                    </h3>
                    <span className={`inline-block w-2.5 h-2.5 rounded-full flex-shrink-0 ${
                      p.status === 'active' ? 'bg-jade-400' :
                      p.status === 'paused' ? 'bg-amber-warm' : 'bg-ink-soft/20'
                    }`} />
                  </div>
                  <p className="text-xs text-ink-soft leading-relaxed line-clamp-1">
                    {p.characterCurrentContext?.substring(0, 60) || t('personaList.lifeLoading')}
                  </p>

                  {/* 标签行：依恋类型 + 关系阶段 */}
                  <div className="flex items-center gap-1.5 mt-2 flex-wrap">
                    <AttachmentTag persona={p} />
                    <PhaseTag phase={p.relationshipPhase} />
                  </div>

                  {/* 最近消息预览 */}
                  <p className="text-xs text-ink-soft/40 mt-2 italic line-clamp-1">
                    "{getPreview(p, t)}"
                  </p>
                </div>
              </div>

              {/* 关系温度 */}
              <div className="flex items-center gap-2 mt-3 text-[11px] text-ink-soft/60">
                <span className="flex items-center gap-1">
                  <span className="text-jade-500">●</span>{t('personaList.trust')}
                </span>
                <span className="flex items-center gap-1">
                  <span className="text-jade-400">●</span>{t('personaList.closeness')}
                </span>
              </div>

              {/* 底栏操作 */}
              <div className="flex items-center gap-2 mt-3 pt-3 border-t border-jade-600/6">
                <button
                  onClick={(e) => handleToggle(e, p)}
                  className={`text-xs px-3 py-1.5 rounded-full font-medium transition-all ${
                    p.status === 'active'
                      ? 'bg-jade-50 text-jade-600 hover:bg-jade-100'
                      : 'bg-amber-50 text-amber-600 hover:bg-amber-100'
                  }`}>
                  {p.status === 'active' ? t('personaList.togglePause') : t('personaList.toggleResume')}
                </button>

                <div className="relative ml-auto" ref={menuOpen === p.id ? menuRef : null}>
                  <button
                    onClick={(e) => { e.stopPropagation(); setMenuOpen(menuOpen === p.id ? null : p.id) }}
                    className="w-8 h-8 rounded-full flex items-center justify-center hover:bg-jade-50 transition-colors text-ink-soft">
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="5" r="1.5" /><circle cx="12" cy="12" r="1.5" /><circle cx="12" cy="19" r="1.5" />
                    </svg>
                  </button>
                  {menuOpen === p.id && (
                    <div className="absolute right-0 top-8 w-32 glass-panel rounded-xl py-1.5 z-[9999] animate-fade-up shadow-lg">
                      <button onClick={(e) => { e.stopPropagation(); handleExport(p) }}
                        className="w-full text-left px-4 py-2 text-sm text-ink hover:bg-jade-50 transition-colors">
                        {t('common.export')}
                      </button>
                      <button onClick={(e) => { e.stopPropagation(); handleArchive(p) }}
                        className="w-full text-left px-4 py-2 text-sm text-red-400 hover:bg-red-50 transition-colors">
                        {t('common.archive')}
                      </button>
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function getName(persona, unknownLabel = '未命名') {
  return persona.name || persona.id?.substring(0, 6) || unknownLabel
}

function getPreview(persona, t) {
  if (persona.status === 'paused') return t('personaList.previewPaused')
  if (persona.characterCurrentContext) return persona.characterCurrentContext.slice(0, 50) + '...'
  return t('personaList.previewClickToView')
}

/** 根据依恋焦虑/回避计算依恋类型标签 */
function AttachmentTag({ persona }) {
  const { t } = useLocale()
  const anxiety = persona.attachmentAnxiety ?? 0.5
  const avoidance = persona.attachmentAvoidance ?? 0.3

  let label = t('personaList.attachmentSecure')
  let colorClass = 'bg-jade-50 text-jade-600'

  if (anxiety > 0.5 && avoidance > 0.5) {
    label = t('personaList.attachmentFearful')
    colorClass = 'bg-red-50 text-red-500'
  } else if (anxiety > 0.5) {
    label = t('personaList.attachmentAnxious')
    colorClass = 'bg-amber-50 text-amber-600'
  } else if (avoidance > 0.5) {
    label = t('personaList.attachmentAvoidant')
    colorClass = 'bg-blue-50 text-blue-500'
  }

  return (
    <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${colorClass}`}>
      {label}
    </span>
  )
}

/** 关系阶段标签 */
function PhaseTag({ phase }) {
  const { t } = useLocale()
  const phaseMap = {
    stranger: { label: t('personaList.phaseStranger'), color: 'bg-gray-50 text-gray-500' },
    acquaintance: { label: t('personaList.phaseAcquaintance'), color: 'bg-jade-50 text-jade-500' },
    friend: { label: t('personaList.phaseFriend'), color: 'bg-jade-50 text-jade-600' },
    close_friend: { label: t('personaList.phaseCloseFriend'), color: 'bg-jade-100 text-jade-700' },
  }
  const config = phaseMap[phase] || phaseMap.stranger
  return (
    <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${config.color}`}>
      {config.label}
    </span>
  )
}

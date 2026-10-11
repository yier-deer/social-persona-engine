import { useEffect, useRef, useState } from 'react'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'

export default function HealthIndicator() {
  const { t, locale } = useLocale()
  const [health, setHealth] = useState(null)
  const [expanded, setExpanded] = useState(false)
  const [lastCheck, setLastCheck] = useState(null)
  const panelRef = useRef(null)

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    const check = async () => {
      try {
        const data = await requestJson('/api/health', { signal: controller.signal })
        if (!cancelled) setHealth(data)
      } catch {
        if (!cancelled) setHealth({ javaStatus: 'unreachable' })
      } finally {
        if (!cancelled) setLastCheck(new Date())
      }
    }
    check()
    const interval = setInterval(check, 30000)
    return () => { cancelled = true; controller.abort(); clearInterval(interval) }
  }, [])

  useEffect(() => {
    if (!expanded) return
    const handler = event => {
      if (panelRef.current && !panelRef.current.contains(event.target)) setExpanded(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [expanded])

  const services = health?.services || {}
  const java = health?.javaStatus === 'ok' ? 'connected' : 'disconnected'
  const python = services.pythonCore?.status || (['ok', 'degraded'].includes(health?.pythonStatus?.status) ? 'connected' : 'disconnected')
  const napcat = services.napcat?.status || 'disconnected'
  const memory = services.memory?.status || (health?.pythonStatus?.memory_connected ? 'connected' : 'uninitialized')
  const llm = services.llm?.status || health?.llm?.status || 'unknown'
  const rows = [
    { key: 'java', label: 'Java', status: java },
    { key: 'python', label: 'Python', status: python },
    { key: 'napcat', label: 'NapCat', status: napcat },
    { key: 'llm', label: 'LLM', status: llm },
    { key: 'memory', label: t('health.memory'), status: memory },
    { key: 'redis', label: 'Redis', status: services.redis?.status || 'disconnected', optional: true },
  ]
  const dots = rows.slice(0, 3)
  const statusText = row => row.optional && row.status !== 'connected'
    ? t('health.optional') : t('health.' + row.status)
  const color = status => ['connected', 'configured'].includes(status) ? 'bg-jade-400'
    : ['unknown', 'uninitialized', 'unconfigured'].includes(status) ? 'bg-amber-400' : 'bg-red-400'

  return (
    <div className="fixed bottom-4 right-4 z-50" ref={panelRef}>
      {expanded && (
        <div className="glass-panel rounded-2xl p-5 mb-3 w-72 max-w-[calc(100vw-2rem)] animate-fade-up shadow-xl">
          <h4 className="font-serif text-sm font-semibold text-jade-700 mb-3">{t('health.title')}</h4>
          <div className="space-y-3">
            {rows.map(row => (
              <div key={row.key} className="flex items-center gap-3">
                <span className={'w-2 h-2 rounded-full flex-shrink-0 ' + (row.optional && row.status !== 'connected' ? 'bg-ink-soft/20' : color(row.status))} />
                <span className="text-xs text-ink-soft w-16">{row.label}</span>
                <span className="text-xs text-ink flex-1">{statusText(row)}</span>
              </div>
            ))}
          </div>
          {services.napcat?.botIds?.length > 0 && <p className="text-xs text-ink-soft mt-3 break-all">{services.napcat.botIds.join(', ')}</p>}
          <p className="text-[11px] text-ink-soft/60 mt-3 leading-relaxed">{t('health.llmHint')}</p>
          {lastCheck && <p className="text-[10px] text-ink-soft/40 mt-3">{t('health.lastCheck')} {lastCheck.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</p>}
        </div>
      )}
      <button onClick={() => setExpanded(value => !value)} aria-label={t('health.title')} aria-expanded={expanded}
        className={'flex items-center gap-3 glass-panel rounded-full px-3 py-1.5 hover:shadow-md ' + (expanded ? 'ring-2 ring-jade-400/30' : '')}>
        {dots.map(row => <span key={row.key} className="flex items-center gap-1">
          <span className={'w-2 h-2 rounded-full ' + color(row.status)} />
          <span className="text-[10px] text-ink-soft/60">{row.key === 'napcat' ? 'QQ' : row.label}</span>
        </span>)}
      </button>
    </div>
  )
}

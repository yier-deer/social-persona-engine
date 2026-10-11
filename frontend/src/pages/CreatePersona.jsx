import { useState, useRef, useEffect } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import LoadingSpinner from '../components/LoadingSpinner'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'

export default function CreatePersona() {
  const { t, locale } = useLocale()
  const navigate = useNavigate()
  const chatEnd = useRef(null)
  const [sessionId, setSessionId] = useState(null)
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [completed, setCompleted] = useState(false)
  const [currentStage, setCurrentStage] = useState('')
  const [creating, setCreating] = useState(false)
  const [creationSteps, setCreationSteps] = useState([])
  const [createdPersonaId, setCreatedPersonaId] = useState(null)
  const [createdName, setCreatedName] = useState('')
  const [error, setError] = useState('')
  const [startAttempt, setStartAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    setLoading(true)
    setError('')
    setSessionId(null)
    requestJson('/api/matchmaker/start', {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ languageHint: locale === 'en' ? 'en' : '' })
    })
      .then(data => {
        if (cancelled) return
        if (!data.sessionId || typeof data.reply !== 'string') throw new Error('Invalid interview session')
        setSessionId(data.sessionId)
        setCurrentStage(data.currentStage)
        setMessages([{ role: 'matchmaker', content: data.reply }])
      })
      .catch(() => {
        if (!cancelled) setError(t('createPersona.connectingError'))
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true; controller.abort() }
  }, [startAttempt])

  useEffect(() => {
    chatEnd.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const sendMessage = async () => {
    const text = input.trim()
    if (!sessionId || !text || loading || completed) return

    setInput('')
    setMessages(prev => [...prev, { role: 'user', content: text }])
    setLoading(true)
    setError('')

    try {
      const data = await requestJson('/api/matchmaker/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, userMessage: text, languageHint: locale === 'en' ? 'en' : '' }),
      })
      if (typeof data.reply !== 'string' || !data.currentStage) throw new Error(t('createPersona.serverError'))

      setMessages(prev => [...prev, { role: 'matchmaker', content: data.reply }])
      setCurrentStage(data.currentStage)
      if (data.complete) {
        setCompleted(true)
      }
    } catch (err) {
      setError(err.message || t('createPersona.serverError'))
      setInput(text)
      setMessages(prev => prev.slice(0, -1))
    } finally {
      setLoading(false)
    }
  }

  const handleConfirm = async () => {
    if (!sessionId || creating) return
    setError('')
    setCreating(true)
    const steps = [
      { id: 'generate', label: t('createPersona.creationSteps.generate'), status: 'pending' },
      { id: 'init', label: t('createPersona.creationSteps.init'), status: 'pending' },
      { id: 'events', label: t('createPersona.creationSteps.events'), status: 'pending' },
      { id: 'done', label: t('createPersona.creationSteps.done'), status: 'pending' },
    ]
    setCreationSteps(steps)

    const updateStep = (id, status) => {
      setCreationSteps(prev => prev.map(s => s.id === id ? { ...s, status } : s))
    }

    try {
      updateStep('generate', 'active')
      const query = new URLSearchParams({ sessionId })
      if (locale === 'en') query.set('languageHint', 'en')
      const data = await requestJson(`/api/matchmaker/confirm?${query}`, {
        method: 'POST',
      })
      if (data.complete && data.personaId) {
        setCreationSteps(steps.map(s => ({ ...s, status: 'done' })))
        setCreatedPersonaId(data.personaId)
        // 直接从 API 返回的 name 字段读取，不依赖正则匹配
        try {
          const pd = await requestJson(`/api/personas/${data.personaId}`)
          setCreatedName(pd.name || t('personaList.unknownName'))
        } catch { setCreatedName(t('personaList.unknownName')) }
      } else {
        const failSteps = steps.map(s => ({ ...s, status: s.status === 'pending' ? 'error' : s.status }))
        setCreationSteps(failSteps)
        setError(t('createPersona.createFailed') + (data.reply || t('createPersona.unknownError')))
        setCreating(false)
      }
    } catch (err) {
      setError(t('createPersona.createFailed') + err.message)
      setCreationSteps(prev => prev.map(s => ({ ...s, status: s.status === 'active' ? 'error' : s.status })))
      setCreating(false)
    }
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  return (
    <div className="animate-fade-up max-w-2xl mx-auto">
      <div className="mb-6 pt-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="font-serif text-2xl font-semibold text-jade-700 tracking-wide">
              {t('createPersona.pageTitle')}
            </h1>
            <p className="text-ink-soft mt-1 text-sm">
              {t('createPersona.pageSubtitle')}
            </p>
          </div>
          <Link to="/manual-create"
            className="px-4 py-2 rounded-full text-xs font-medium bg-jade-50/80 text-jade-600 hover:bg-jade-100 transition-all">
            {t('createPersona.manualBtn')}
          </Link>
        </div>
        {currentStage && (
          <StageIndicator stage={currentStage} />
        )}
      </div>

      {error && <div role="alert" className="text-sm text-red-600 mb-4">
        {error}
        {!sessionId && <button onClick={() => setStartAttempt(v => v + 1)} disabled={loading} className="underline ml-3">{t('common.retry')}</button>}
      </div>}
      <div className="glass-panel rounded-3xl overflow-hidden">
        <div className="h-[28rem] overflow-y-auto px-5 py-4 space-y-4">
          {messages.map((msg, i) => (
            <div
              key={i}
              className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'} animate-fade-up`}
            >
              <div className={`max-w-[80%] px-4 py-3 text-sm leading-relaxed whitespace-pre-wrap ${
                msg.role === 'user' ? 'chat-bubble-user' : 'chat-bubble-matchmaker'
              }`}>
                {msg.content}
              </div>
            </div>
          ))}

          {loading && (
            <div className="flex justify-start">
              <div className="chat-bubble-matchmaker px-4 py-3">
                <LoadingSpinner />
              </div>
            </div>
          )}

          <div ref={chatEnd} />
        </div>

        <div className="border-t border-jade-600/8 p-4 bg-white/30">
          {completed ? (
            <div className="text-center py-3">
              <p className="text-jade-600 text-sm mb-3 font-medium">
                {t('createPersona.completePrompt')}
              </p>
              <button
                onClick={handleConfirm}
                disabled={creating}
                className="btn-jade text-sm"
              >
                {creating ? t('createPersona.creating') : t('createPersona.confirmCreate')}
              </button>
            </div>
          ) : (
            <div className="flex gap-3">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={t('createPersona.placeholder')}
                rows={2}
                disabled={loading || !sessionId}
                className="flex-1 min-w-0 resize-none rounded-2xl px-4 py-3 text-sm bg-white/60 border border-jade-600/12
                           focus:outline-none focus:border-jade-400/40 focus:ring-2 focus:ring-jade-400/10
                           placeholder:text-ink-soft/40 transition-all duration-300"
              />
              <button
                onClick={sendMessage}
                disabled={loading || !sessionId || !input.trim()}
                aria-label={t('createPersona.send')}
                className="self-end w-11 h-11 rounded-full flex items-center justify-center flex-shrink-0
                           disabled:opacity-30 transition-all duration-300 shadow-md"
                style={{
                  background: input.trim() && !loading
                    ? 'linear-gradient(135deg, #40916c, #2d6a4f)'
                    : 'rgba(45,106,79,0.1)',
                }}
              >
                <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
                </svg>
              </button>
            </div>
          )}
        </div>
      </div>

      {/* 创建动画弹窗 */}
      {creating && (
        <CreationModal steps={creationSteps} personaId={createdPersonaId} name={createdName}
          onDone={() => navigate('/')}
          onView={() => navigate(`/persona/${createdPersonaId}`)} />
      )}
    </div>
  )
}

function CreationModal({ steps, personaId, name, onDone, onView }) {
  const { t } = useLocale()
  const [exitAnim, setExitAnim] = useState(false)
  const allDone = steps.every(s => s.status === 'done')

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center"
         style={{ background: 'rgba(26,26,46,0.55)', backdropFilter: 'blur(10px)' }}>
      <div className={`glass-panel rounded-3xl w-full max-w-sm p-8 mx-5 text-center transition-all duration-500 ${
        exitAnim ? 'scale-95 opacity-0' : 'scale-100 opacity-100'
      } ${allDone && personaId ? '' : ''}`}>
        {!allDone ? (
          <>
            <div className="w-16 h-16 mx-auto mb-5 rounded-2xl flex items-center justify-center animate-pulse"
                 style={{ background: 'linear-gradient(135deg, #d8ebe2, #e8f5e9)' }}>
              <svg className="w-8 h-8 text-jade-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.3">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.455 2.456L21.75 6l-1.036.259a3.375 3.375 0 00-2.455 2.456zM16.894 20.567L16.5 21.75l-.394-1.183a2.25 2.25 0 00-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 001.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 001.423 1.423l1.183.394-1.183.394a2.25 2.25 0 00-1.423 1.423z" />
              </svg>
            </div>
            <h2 className="font-serif text-lg font-semibold text-jade-700 mb-5">{t('createPersona.creatingTitle')}</h2>

            <div className="space-y-3 mb-6">
              {steps.map(s => (
                <div key={s.id} className="flex items-center gap-3">
                  <div className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 transition-all duration-300 ${
                    s.status === 'done' ? 'bg-jade-400' :
                    s.status === 'active' ? 'bg-jade-300 ring-2 ring-jade-300/30 animate-pulse' :
                    s.status === 'error' ? 'bg-red-300' :
                    'bg-jade-600/12'
                  }`}>
                    {s.status === 'done' ? <span className="text-white text-[10px]">✓</span> :
                     s.status === 'error' ? <span className="text-white text-[10px]">✕</span> : null}
                  </div>
                  <span className={`text-sm ${
                    s.status === 'done' ? 'text-jade-600' :
                    s.status === 'active' ? 'text-jade-500' :
                    s.status === 'error' ? 'text-red-400' :
                    'text-ink-soft/40'
                  }`}>{s.label}</span>
                </div>
              ))}
            </div>
          </>
        ) : (
          <>
            <div className="w-16 h-16 mx-auto mb-4 rounded-2xl flex items-center justify-center"
                 style={{ background: 'linear-gradient(135deg, #52b788, #2d6a4f)' }}>
              <span className="text-white text-2xl font-serif font-semibold">{name?.[0] || '✓'}</span>
            </div>
            <h2 className="font-serif text-xl font-semibold text-jade-700 mb-1">{t('createPersona.createdTitle', { name })}</h2>
            <p className="text-ink-soft text-sm mb-6">{t('createPersona.createdSubtitle')}</p>

            <div className="space-y-3">
              <button onClick={() => { setExitAnim(true); setTimeout(onView, 400) }}
                className="btn-jade w-full text-sm py-3">
                {t('createPersona.viewPersona', { name })}
              </button>
              <button onClick={() => { setExitAnim(true); setTimeout(onDone, 400) }}
                className="w-full py-3 rounded-full text-sm font-medium bg-jade-50 text-jade-600 hover:bg-jade-100 transition-all">
                {t('common.backToList')}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function StageIndicator({ stage }) {
  const { t } = useLocale()
  const stages = [
    'basic_profile', 'style_anchor', 'boundary_probe',
    'attachment_explore', 'system_detail', 'sample_confirm',
  ]
  const labels = {
    basic_profile: t('createPersona.stages.basic_profile'),
    style_anchor: t('createPersona.stages.style_anchor'),
    boundary_probe: t('createPersona.stages.boundary_probe'),
    attachment_explore: t('createPersona.stages.attachment_explore'),
    system_detail: t('createPersona.stages.system_detail'),
    sample_confirm: t('createPersona.stages.sample_confirm'),
  }
  const idx = stages.indexOf(stage)
  if (idx < 0) return null

  return (
    <div className="flex items-center gap-1.5 mt-3">
      {stages.map((s, i) => (
        <div key={s} className="flex items-center gap-1.5">
          <div className={`w-2 h-2 rounded-full transition-all duration-300 ${
            i < idx ? 'bg-jade-500' :
            i === idx ? 'bg-jade-400 ring-2 ring-jade-400/30' :
            'bg-jade-600/15'
          }`} />
          {i < stages.length - 1 && (
            <div className={`w-3 h-px ${i < idx ? 'bg-jade-300' : 'bg-jade-600/10'}`} />
          )}
        </div>
      ))}
      <span className="text-[11px] text-ink-soft/60 ml-2 font-medium">
        {labels[stage] || stage}
      </span>
    </div>
  )
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

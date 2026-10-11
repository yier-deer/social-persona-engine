import { useState, useEffect, useRef } from 'react'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'

export default function TestConnectionModal({ provider, apiKey, baseUrl, model, type, onClose, onConnected }) {
  const { t } = useLocale()

  const TEST_STEPS = [
    { id: 'send', label: t('providerSelector.testStepSend'), icon: '→' },
    { id: 'wait', label: t('providerSelector.testStepWait'), icon: '◷' },
    { id: 'verify', label: t('providerSelector.testStepVerify'), icon: '✓' },
  ]

  const [steps, setSteps] = useState(TEST_STEPS.map(s => ({ ...s, status: 'pending' })))
  const [result, setResult] = useState(null)
  const [running, setRunning] = useState(true)
  const started = useRef(false)

  const updateStep = (id, status) => {
    setSteps(prev => prev.map(s => s.id === id ? { ...s, status } : s))
  }

  const runTest = async () => {
    try {
      updateStep('send', 'active')
      await delay(400)
      updateStep('send', 'done')
      updateStep('wait', 'active')

      const data = await requestJson('/api/config/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider, apiKey, baseUrl, model, type: type || 'chat' }),
      })
      if (typeof data.success !== 'boolean') throw new Error(t('providerSelector.networkError'))

      updateStep('wait', 'done')
      updateStep('verify', 'active')
      await delay(300)
      updateStep('verify', 'done')

      setResult(data)
      setRunning(false)
    } catch (err) {
      setSteps(prev => prev.map(s => ({ ...s, status: s.status === 'active' ? 'error' : s.status })))
      setResult({ success: false, error: err.message || t('providerSelector.networkError') })
      setRunning(false)
    }
  }

  useEffect(() => {
    if (started.current) return
    started.current = true
    runTest()
  }, [])

  const allDone = steps.every(s => s.status === 'done')
  const hasError = steps.some(s => s.status === 'error')

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center"
         style={{ background: 'rgba(26,26,46,0.5)', backdropFilter: 'blur(8px)' }}>
      <div className="glass-panel rounded-3xl w-full max-w-md p-8 mx-5 animate-fade-up">
        <div className="text-center mb-6">
          <div className={`w-14 h-14 mx-auto mb-3 rounded-2xl flex items-center justify-center ${
            allDone && result?.success ? 'animate-none' : 'animate-pulse'
          }`} style={{
            background: allDone && result?.success
              ? 'linear-gradient(135deg, #52b788, #2d6a4f)'
              : allDone && !result?.success
                ? 'linear-gradient(135deg, #fecaca, #f87171)'
                : 'linear-gradient(135deg, #d8ebe2, #e8f5e9)',
          }}>
            {allDone && result?.success ? (
              <svg className="w-7 h-7 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            ) : allDone && !result?.success ? (
              <svg className="w-7 h-7 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            ) : (
              <svg className="w-7 h-7 text-jade-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.3">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09z" />
              </svg>
            )}
          </div>
          <h2 className="font-serif text-lg font-semibold text-jade-700">
            {running ? t('providerSelector.testing') : result?.success ? t('providerSelector.testSuccessTitle') : t('providerSelector.testFailedTitle')}
          </h2>
        </div>

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

        {result && (
          <div className={`rounded-xl p-4 mb-6 text-xs ${
            result.success ? 'bg-jade-50/60' : 'bg-red-50/60'
          }`}>
            {result.success ? (
              <>
                <div className="flex items-center gap-2 mb-2">
                  <span className="text-jade-600 font-medium">{result.model}</span>
                  <span className="text-jade-400">{result.latency_ms}ms</span>
                </div>
                <p className="text-ink-soft/70 break-all line-clamp-3">{result.response}</p>
              </>
            ) : (
              <p className="text-red-400 break-all">{result.error}</p>
            )}
          </div>
        )}

        <div className="flex items-center gap-3">
          {!running && (
            <button onClick={() => result?.success ? onConnected?.() : onClose()}
              className={`btn-jade flex-1 text-sm py-3 ${result?.success ? '' : 'opacity-60'}`}>
              {result?.success ? t('providerSelector.confirmConfig') : t('providerSelector.goBack')}
            </button>
          )}
          {running && (
            <button onClick={onClose} className="flex-1 py-3 rounded-full text-sm font-medium bg-jade-50 text-jade-600 hover:bg-jade-100 transition-all">
              {t('common.cancel')}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function delay(ms) { return new Promise(r => setTimeout(r, ms)) }

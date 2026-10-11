import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import ProviderSelector from './ProviderSelector'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'

/**
 * 首次引导向导组件
 * 4 步流程：LLM 配置 → 图像模型配置 → QQ 号 → 完成（导向牵线人）
 */
export default function OnboardingWizard({ onComplete }) {
  const { t, locale } = useLocale()
  const navigate = useNavigate()
  const [step, setStep] = useState(0)
  const [data, setData] = useState({ qq: '' })
  const [visible, setVisible] = useState(false)
  const [exitAnim, setExitAnim] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const exitTimer = useRef(null)

  /**
   * 显示/隐藏逻辑全部交给父组件 App.jsx 决定（localStorage + API hasKey 双重检测）。
   * 只要父组件渲染了本组件，就进入出场动画。
   */
  useEffect(() => {
    const timer = setTimeout(() => setVisible(true), 400)
    return () => { clearTimeout(timer); clearTimeout(exitTimer.current) }
  }, [])

  const handleLLMSaved = () => {
    setStep(1)
  }

  const handleImageSaved = () => {
    setStep(2)
  }

  const handleQQNext = async () => {
    const account = data.qq.trim()
    if (!/^\d{5,15}$/.test(account)) {
      setError(t('personaDetail.qqInvalid'))
      return
    }
    if (saving) return
    setSaving(true)
    setError('')
    try {
      await requestJson('/api/config/channels', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type: 'qq', account }),
      })
      setStep(3)
    } catch (err) {
      setError(err.message || t('settings.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  const handleSkip = () => {
    localStorage.setItem('hasConfigured', 'true')
    localStorage.setItem('hasSkipped', 'true')
    setExitAnim(true)
    exitTimer.current = setTimeout(() => onComplete?.(), 400)
  }

  const handleFinishAndGo = (path) => {
    localStorage.setItem('hasConfigured', 'true')
    setExitAnim(true)
    exitTimer.current = setTimeout(() => {
      onComplete?.()
      navigate(path)
    }, 400)
  }

  if (!visible && !exitAnim) return null

  return (
    <div className={`fixed inset-0 z-[100] flex items-center justify-center transition-all duration-400 ${
      exitAnim ? 'opacity-0' : 'opacity-100'
    }`} style={{ background: 'rgba(26,26,46,0.55)', backdropFilter: 'blur(8px)' }}>

      {/* Step 0: 配置语言大模型 */}
      {step === 0 && (
        <div className="relative w-full max-w-lg mx-5">
          {/* 步骤指示器 */}
          <div className="absolute -top-10 left-0 right-0 flex items-center justify-center gap-2">
            {[0, 1, 2, 3].map(i => (
              <div key={i} className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full transition-all ${
                  i < step ? 'bg-jade-500' : i === step ? 'bg-jade-400 ring-2 ring-jade-400/30' : 'bg-white/20'
                }`} />
                {i < 3 && <div className={`w-4 h-px ${i < step ? 'bg-jade-300/60' : 'bg-white/15'}`} />}
              </div>
            ))}
          </div>
          <ProviderSelector
            filterType="chat"
            onSave={handleLLMSaved}
            onClose={handleSkip}
          />
        </div>
      )}

      {/* Step 1: 配置图像大模型 */}
      {step === 1 && (
        <div className="relative w-full max-w-lg mx-5">
          <div className="absolute -top-10 left-0 right-0 flex items-center justify-center gap-2">
            {[0, 1, 2, 3].map(i => (
              <div key={i} className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full transition-all ${
                  i < step ? 'bg-jade-500' : i === step ? 'bg-jade-400 ring-2 ring-jade-400/30' : 'bg-white/20'
                }`} />
                {i < 3 && <div className={`w-4 h-px ${i < step ? 'bg-jade-300/60' : 'bg-white/15'}`} />}
              </div>
            ))}
          </div>
          <ProviderSelector
            filterType="image"
            endpoint="/api/config/image-provider"
            onSave={handleImageSaved}
            onSkip={() => setStep(2)}
            onClose={handleSkip}
          />
        </div>
      )}

      {/* Step 2: 配置 QQ 号 */}
      {step === 2 && (
        <div className={`glass-panel rounded-3xl w-full max-w-lg p-8 mx-5 transition-all duration-400 ${
          exitAnim ? 'scale-95 opacity-0' : 'scale-100 opacity-100'
        } animate-fade-up`}>
          {/* 步骤指示器 */}
          <div className="flex items-center gap-2 mb-6">
            {[0, 1, 2, 3].map(i => (
              <div key={i} className="flex items-center gap-2">
                <div className={`w-2.5 h-2.5 rounded-full transition-all ${
                  i < step ? 'bg-jade-500' : i === step ? 'bg-jade-400 ring-2 ring-jade-400/30' : 'bg-jade-600/12'
                }`} />
                {i < 3 && <div className={`w-5 h-px ${i < step ? 'bg-jade-300' : 'bg-jade-600/8'}`} />}
              </div>
            ))}
            <span className="text-xs text-ink-soft/50 ml-2">3/4</span>
          </div>

          <h2 className="font-serif text-xl font-semibold text-jade-700 mb-1">{t('onboarding.step2_title')}</h2>
          <p className="text-ink-soft text-sm mb-6">{t('onboarding.step2_desc')}</p>
          {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}

          <div className="space-y-4 mb-8">
            <div>
              <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('onboarding.step2_label')}</label>
              <input
                value={data.qq || ''}
                onChange={e => setData(prev => ({ ...prev, qq: e.target.value }))}
                placeholder={t('onboarding.step2_placeholder')}
                className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40"
                autoFocus
              />
            </div>
          </div>

          <div className="flex items-center justify-between">
            <button onClick={() => setStep(1)} className="text-sm text-ink-soft hover:text-jade-600">{'← '}{t('common.prev')}</button>
            <div className="flex items-center gap-3">
              <button onClick={handleSkip} className="text-sm text-ink-soft/50 hover:text-ink-soft transition-colors">
                {t('common.skipWizard')}
              </button>
              <button onClick={handleQQNext} disabled={saving} className="btn-jade text-sm disabled:opacity-40">{t('common.next')} →</button>
            </div>
          </div>
        </div>
      )}

      {/* Step 3: 完成 — 导向牵线人 */}
      {step === 3 && (
        <div className={`glass-panel rounded-3xl w-full max-w-lg p-10 mx-5 text-center transition-all duration-400 ${
          exitAnim ? 'scale-95 opacity-0' : 'scale-100 opacity-100'
        } animate-fade-up`}>
          {/* 庆祝动画 */}
          <div className="w-20 h-20 mx-auto mb-6 rounded-full flex items-center justify-center animate-check-pop"
               style={{ background: 'linear-gradient(135deg, #52b788, #2d6a4f)' }}>
            <svg className="w-10 h-10 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
              <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
            </svg>
          </div>

          <h2 className="font-serif text-2xl font-semibold text-jade-700 mb-2">{t('onboarding.step3_title')}</h2>
          <p className="text-ink-soft text-sm mb-8 max-w-sm mx-auto leading-relaxed">
            {t('onboarding.step3_desc1')}
            <br />{t('onboarding.step3_desc2')}
          </p>

          <button
            onClick={() => handleFinishAndGo('/create')}
            className="btn-jade text-sm py-3 px-10 flex items-center gap-2 mx-auto">
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
              <path strokeLinecap="round" strokeLinejoin="round" d="M8.625 12a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 01-2.555-.337A5.972 5.972 0 015.41 20.97a5.969 5.969 0 01-.474-.065 4.48 4.48 0 00.978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z" />
            </svg>
            {t('onboarding.step3_btn')}
          </button>

          <p className="text-xs text-ink-soft/40 mt-6">{t('onboarding.step3_footer')}</p>
        </div>
      )}
    </div>
  )
}

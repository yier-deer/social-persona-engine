import { useState, useEffect } from 'react'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'
import ProviderSelector from '../components/ProviderSelector'
import { useToast } from '../components/Toast'

export default function Settings() {
  const { locale, setLocale, t } = useLocale()
  const toast = useToast()
  const [keyCfg, setKeyCfg] = useState({ provider: '', hasKey: false, baseUrl: '', model: '' })
  const [imageCfg, setImageCfg] = useState({ imageProvider: '', hasImageKey: false, imageBaseUrl: '', imageModel: '' })
  const [channels, setChannels] = useState({ qq: '' })
  const [loading, setLoading] = useState(true)
  const [showProvider, setShowProvider] = useState(false)
  const [showImageProvider, setShowImageProvider] = useState(false)
  const [error, setError] = useState('')
  const [loadAttempt, setLoadAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()
    setLoading(true)
    setError('')
    Promise.all([
      requestJson('/api/config/key', { signal: controller.signal }),
      requestJson('/api/config/channels', { signal: controller.signal }),
      requestJson('/api/config/image-provider', { signal: controller.signal }),
    ]).then(([keyData, chData, imgData]) => {
      if (cancelled) return
      setKeyCfg(keyData)
      setChannels(chData)
      setImageCfg(imgData)
    }).catch(err => { if (!cancelled) setError(err.message) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true; controller.abort() }
  }, [loadAttempt])

  const handleProviderSaved = async () => {
    setShowProvider(false)
    toast.success(t('settings.saved'))
    try { setKeyCfg(await requestJson('/api/config/key')) } catch (err) { setError(err.message) }
  }

  const handleImageProviderSaved = async () => {
    setShowImageProvider(false)
    toast.success(t('settings.imageSaved'))
    try { setImageCfg(await requestJson('/api/config/image-provider')) } catch (err) { setError(err.message) }
  }

  const saveChannel = async (type, account) => {
    const trimmed = account.trim()
    if (trimmed && !/^\d{5,15}$/.test(trimmed)) {
      toast.error(t('personaDetail.qqInvalid'))
      return
    }
    try {
      await requestJson('/api/config/channels', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type, account: trimmed }),
      })
      const r = await requestJson('/api/config/channels')
      setChannels(r)
      toast.success(t('settings.qqSaved'))
    } catch {
      toast.error(t('settings.saveFailed'))
    }
  }

  if (loading) return <p className="text-sm text-ink-soft">{t('common.loading')}</p>

  return (
    <div className="animate-fade-up max-w-2xl mx-auto">
      <h1 className="font-serif text-2xl font-semibold text-jade-700 mb-8">{t('settings.title')}</h1>
      {error && <div role="alert" className="text-sm text-red-600 mb-5">{error} <button onClick={() => setLoadAttempt(v => v + 1)} className="underline ml-3">{t('common.retry')}</button></div>}

      {/* Language */}
      <div className="glass-card rounded-2xl p-6 mb-5">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-4">{t('locale.switchTo')}</h2>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setLocale('zh-CN')}
            className={`px-5 py-2.5 rounded-full text-sm font-medium transition-all ${
              locale === 'zh-CN'
                ? 'bg-jade-50 text-jade-700 ring-1 ring-jade-400/30'
                : 'bg-white/40 text-ink-soft hover:bg-white/60'
            }`}
          >
            🇨🇳 {t('locale.zh-CN')}
          </button>
          <button
            onClick={() => setLocale('en')}
            className={`px-5 py-2.5 rounded-full text-sm font-medium transition-all ${
              locale === 'en'
                ? 'bg-jade-50 text-jade-700 ring-1 ring-jade-400/30'
                : 'bg-white/40 text-ink-soft hover:bg-white/60'
            }`}
          >
            🇺🇸 {t('locale.en')}
          </button>
        </div>
      </div>

      {/* LLM Provider */}
      <div className="glass-card rounded-2xl p-6 mb-5">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-4">{t('settings.provider')}</h2>
        {keyCfg.hasKey ? (
          <div className="rounded-xl p-4 bg-jade-50/60 mb-4">
            <div className="flex items-center justify-between">
              <div>
                <span className="text-sm font-medium text-jade-700 capitalize">{keyCfg.provider}</span>
                <span className="text-xs text-jade-500 ml-3">{keyCfg.model}</span>
              </div>
              <span className="w-2 h-2 rounded-full bg-jade-400" title="Configured" />
            </div>
            {keyCfg.baseUrl && (
              <p className="text-[11px] text-ink-soft/40 mt-1 truncate">{keyCfg.baseUrl}</p>
            )}
          </div>
        ) : (
          <p className="text-sm text-ink-soft/60 mb-4">{t('settings.providerUnconfigured')}</p>
        )}
        <div className="flex items-center gap-3">
          <button onClick={() => setShowProvider(true)}
            className="px-5 py-2.5 rounded-full text-sm font-medium bg-jade-50 text-jade-600 hover:bg-jade-100 transition-all">
            {keyCfg.hasKey ? t('settings.providerChange') : t('settings.providerConfigure')}
          </button>
        </div>
      </div>

      {/* Image Provider */}
      <div className="glass-card rounded-2xl p-6 mb-5">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-4">{t('settings.imageProvider')}</h2>
        {imageCfg.hasImageKey ? (
          <div className="rounded-xl p-4 bg-jade-50/60 mb-4">
            <div className="flex items-center justify-between">
              <div>
                <span className="text-sm font-medium text-jade-700 capitalize">{imageCfg.imageProvider}</span>
                <span className="text-xs text-jade-500 ml-3">{imageCfg.imageModel}</span>
              </div>
              <span className="w-2 h-2 rounded-full bg-jade-400" title="Configured" />
            </div>
            {imageCfg.imageBaseUrl && (
              <p className="text-[11px] text-ink-soft/40 mt-1 truncate">{imageCfg.imageBaseUrl}</p>
            )}
          </div>
        ) : (
          <p className="text-sm text-ink-soft/60 mb-4">{t('settings.imageUnconfigured')}</p>
        )}
        <div className="flex items-center gap-3">
          <button onClick={() => setShowImageProvider(true)}
            className="px-5 py-2.5 rounded-full text-sm font-medium bg-indigo-50 text-indigo-600 hover:bg-indigo-100 transition-all">
            {imageCfg.hasImageKey ? t('settings.imageChange') : t('settings.imageConfigure')}
          </button>
        </div>
      </div>

      {/* QQ Channel */}
      <div className="glass-card rounded-2xl p-6 mb-5">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-4">{t('settings.channel')}</h2>
        <p className="text-xs text-ink-soft/60 mb-4 leading-relaxed">{t('settings.channelDesc')}</p>
        <div className="flex items-center gap-3">
          <label htmlFor="global-owner-qq" className="text-sm font-medium text-ink w-16 flex-shrink-0">{t('settings.channelLabel')}</label>
          <input
            id="global-owner-qq" inputMode="numeric"
            value={channels.qq || ''}
            onChange={e => setChannels(prev => ({ ...prev, qq: e.target.value }))}
            placeholder={t('settings.channelPlaceholder')}
            onBlur={e => saveChannel('qq', e.target.value)}
            className="flex-1 min-w-0 rounded-xl px-3 py-2.5 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40"
          />
          <span className={`w-2 h-2 rounded-full ${channels.qq ? 'bg-jade-400' : 'bg-jade-600/15'}`} />
        </div>
      </div>

      {/* QQ Setup Guide */}
      <div className="glass-card rounded-2xl p-6">
        <h2 className="font-serif text-lg font-semibold text-jade-600 mb-4">{t('settings.qqGuide')}</h2>
        <div className="space-y-4">
          {[
            {
              step: 1,
              icon: (
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z" />
                </svg>
              ),
              title: t('settings.qqStep1Title'),
              desc: t('settings.qqStep1Desc'),
            },
            {
              step: 2,
              icon: (
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 4.875c0-.621.504-1.125 1.125-1.125h4.5c.621 0 1.125.504 1.125 1.125v4.5c0 .621-.504 1.125-1.125 1.125h-4.5A1.125 1.125 0 013.75 9.375v-4.5zM3.75 14.625c0-.621.504-1.125 1.125-1.125h4.5c.621 0 1.125.504 1.125 1.125v4.5c0 .621-.504 1.125-1.125 1.125h-4.5a1.125 1.125 0 01-1.125-1.125v-4.5zM13.5 4.875c0-.621.504-1.125 1.125-1.125h4.5c.621 0 1.125.504 1.125 1.125v4.5c0 .621-.504 1.125-1.125 1.125h-4.5A1.125 1.125 0 0113.5 9.375v-4.5z" />
                </svg>
              ),
              title: t('settings.qqStep2Title'),
              desc: t('settings.qqStep2Desc'),
            },
            {
              step: 3,
              icon: (
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m13.35-.622l1.757-1.757a4.5 4.5 0 00-6.364-6.364l-4.5 4.5a4.5 4.5 0 001.242 7.244" />
                </svg>
              ),
              title: t('settings.qqStep3Title'),
              desc: t('settings.qqStep3Desc'),
              code: 'ws://127.0.0.1:8080/ws/qq',
            },
            {
              step: 4,
              icon: (
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12c0 1.268-.63 2.39-1.593 3.068a3.745 3.745 0 01-1.043 3.296 3.745 3.745 0 01-3.296 1.043A3.745 3.745 0 0112 21c-1.268 0-2.39-.63-3.068-1.593a3.746 3.746 0 01-3.296-1.043 3.745 3.745 0 01-1.043-3.296A3.745 3.745 0 013 12c0-1.268.63-2.39 1.593-3.068a3.745 3.745 0 011.043-3.296 3.746 3.746 0 013.296-1.043A3.746 3.746 0 0112 3c1.268 0 2.39.63 3.068 1.593a3.746 3.746 0 013.296 1.043 3.746 3.746 0 011.043 3.296A3.745 3.745 0 0121 12z" />
                </svg>
              ),
              title: t('settings.qqStep4Title'),
              desc: t('settings.qqStep4Desc'),
            },
          ].map(item => (
            <div key={item.step} className="flex gap-3">
              <div className="flex flex-col items-center flex-shrink-0">
                <div className="w-7 h-7 rounded-full bg-jade-50 flex items-center justify-center text-jade-600">
                  {item.icon}
                </div>
                {item.step < 4 && (
                  <div className="w-px flex-1 bg-jade-100 my-1" />
                )}
              </div>
              <div className="pb-4">
                <p className="text-sm font-medium text-ink mb-0.5">
                  <span className="text-jade-500 mr-1">{item.step}.</span>
                  {item.title}
                </p>
                <p className="text-xs text-ink-soft/70 leading-relaxed">{item.desc}</p>
                {item.code && (
                  <div className="flex items-center gap-2 mt-1.5">
                    <code className="bg-jade-50 px-2 py-1 rounded text-[11px] text-jade-700 font-mono">
                      {item.code}
                    </code>
                    <CopyButton text={item.code} />
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
        <p className="text-xs text-ink-soft/40 mt-2">
          {t('settings.qqFooter')} <a href="https://github.com/NapNeko/NapCatQQ" target="_blank" rel="noreferrer" className="text-jade-500 hover:text-jade-600 underline">github.com/NapNeko/NapCatQQ</a>
        </p>
      </div>

      {showProvider && (
        <ProviderSelector
          onSave={handleProviderSaved}
          onClose={() => setShowProvider(false)}
          initialProvider={keyCfg.provider}
          initialModel={keyCfg.model}
          initialUrl={keyCfg.baseUrl}
          hasKey={keyCfg.hasKey}
          filterType="chat"
        />
      )}
      {showImageProvider && (
        <ProviderSelector
          onSave={handleImageProviderSaved}
          onClose={() => setShowImageProvider(false)}
          initialProvider={imageCfg.imageProvider}
          initialModel={imageCfg.imageModel}
          initialUrl={imageCfg.imageBaseUrl}
          hasKey={imageCfg.hasImageKey}
          endpoint="/api/config/image-provider"
          filterType="image"
        />
      )}
    </div>
  )
}

function CopyButton({ text }) {
  const { t } = useLocale()
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    }
  }

  return (
    <button
      onClick={handleCopy}
      className={`text-[11px] px-2 py-1 rounded-full transition-all ${
        copied
          ? 'bg-jade-50 text-jade-600'
          : 'bg-white/40 text-ink-soft/50 hover:bg-jade-50 hover:text-jade-600'
      }`}
    >
      {copied ? t('common.copied') : t('common.copy')}
    </button>
  )
}

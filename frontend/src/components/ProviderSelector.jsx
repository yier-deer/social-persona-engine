import { useState, useEffect } from 'react'
import TestConnectionModal from './TestConnectionModal'
import { useLocale } from '../LocaleContext'
import { requestJson } from '../api'

export default function ProviderSelector({ onSave, onClose, onSkip, initialProvider, initialKey, initialModel, initialUrl, hasKey = false, endpoint = '/api/config/key', filterType }) {
  const { t } = useLocale()
  const [providers, setProviders] = useState([])
  const [selected, setSelected] = useState(initialProvider || '')
  const [apiKey, setApiKey] = useState(initialKey || '')
  const [model, setModel] = useState(initialModel || '')
  const [baseUrl, setBaseUrl] = useState(initialUrl || '')
  const [models, setModels] = useState([])
  const [refreshing, setRefreshing] = useState(false)
  const [refreshMsg, setRefreshMsg] = useState('')
  const [refreshError, setRefreshError] = useState(false)
  const [showTest, setShowTest] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const [loadingProviders, setLoadingProviders] = useState(true)

  const currentProvider = providers.find(p => p.id === selected) || {}
  const canKeepKey = hasKey && selected === initialProvider
  const canSave = !!selected && !loadingProviders && (apiKey.trim() || canKeepKey) && model.trim() && baseUrl.trim()

  const localized = (provider, field) => {
    const key = `providerSelector.${field}_${provider.id}`
    const translated = t(key)
    return translated === key ? provider[field] : translated
  }

  useEffect(() => {
    let cancelled = false
    const fetchUrl = filterType ? `/api/config/providers?type=${filterType}` : '/api/config/providers'
    setLoadingProviders(true)
    requestJson(fetchUrl)
      .then(data => {
        if (cancelled) return
        const list = Array.isArray(data) ? data : []
        setProviders(list)
        const sel = list.find(p => p.id === initialProvider) || list[0]
        setSelected(sel?.id || '')
        const available = sel?.models || []
        setModels(initialModel && !available.some(m => m.id === initialModel)
          ? [{ id: initialModel, name: initialModel }, ...available] : available)
        setModel(initialModel || available[0]?.id || '')
        setBaseUrl(initialUrl || sel?.default_url || '')
        if (!sel) setSaveError(t('providerSelector.loadFailed'))
      })
      .catch(() => { if (!cancelled) setSaveError(t('providerSelector.loadFailed')) })
      .finally(() => { if (!cancelled) setLoadingProviders(false) })
    return () => { cancelled = true }
  }, [filterType, initialProvider, initialModel, initialUrl])

  const selectProvider = (provider) => {
    if (provider.id === selected) return
    setSelected(provider.id)
    setModels(provider.models || [])
    setModel(provider.models?.[0]?.id || '')
    setBaseUrl(provider.default_url || '')
    setApiKey('')
    setRefreshMsg('')
    setSaveError('')
  }

  const refreshModels = async () => {
    if (!apiKey.trim()) return setRefreshMsg(t('providerSelector.enterKeyFirst'))
    setRefreshing(true)
    setRefreshMsg('')
    try {
      const data = await requestJson(`/api/config/providers/${selected}/refresh-models`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apiKey, baseUrl }),
      })
      if (data.success) {
        setModels(data.models)
        setRefreshMsg(t('providerSelector.modelsFetched', { count: data.models.length }))
        setRefreshError(false)
        setTimeout(() => setRefreshMsg(''), 3000)
      } else {
        setRefreshMsg(data.error)
        setRefreshError(true)
      }
    } catch {
      setRefreshMsg(t('providerSelector.networkError'))
      setRefreshError(true)
    } finally {
      setRefreshing(false)
    }
  }

  const handleSave = async () => {
    if (!canSave || saving) return false
    setSaving(true)
    setSaveError('')
    try {
      await requestJson(endpoint, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: selected, apiKey: apiKey.trim(), baseUrl: baseUrl.trim(), model: model.trim() }),
      })
      onSave?.({ provider: selected, model: model.trim(), baseUrl: baseUrl.trim() })
      return true
    } catch (error) {
      setSaveError(error.message || t('providerSelector.networkError'))
      return false
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-[200] flex items-center justify-center"
           style={{ background: 'rgba(26,26,46,0.55)', backdropFilter: 'blur(10px)' }}>
        <div className="glass-panel rounded-3xl w-full max-w-lg p-8 mx-5 animate-fade-up max-h-[85vh] overflow-y-auto relative">
          {/* 右上角关闭按钮 */}
          <button
            onClick={() => onClose?.()}
            className="absolute top-4 right-4 w-8 h-8 rounded-full flex items-center justify-center text-ink-soft/40 hover:text-ink-soft hover:bg-jade-50 transition-all"
            title={t('common.close')}
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>

          <h2 className="font-serif text-xl font-semibold text-jade-700 mb-6">{t('providerSelector.selectProvider')}</h2>
          {saveError && <p role="alert" className="text-sm text-red-600 mb-4">{saveError}</p>}

          {/* Provider 列表 */}
          <div className="grid grid-cols-2 gap-3 mb-6">
            {providers.map(p => (
              <div key={p.id}
                onClick={() => selectProvider(p)}
                className={`cursor-pointer rounded-2xl p-4 transition-all duration-300 ${
                  selected === p.id
                    ? 'ring-2 ring-jade-400/40 shadow-lg'
                    : 'hover:shadow-md opacity-70 hover:opacity-100'
                }`}
                style={{
                  background: selected === p.id
                    ? getGradient(p.id)
                    : 'rgba(255,255,255,0.6)',
                }}
              >
                <div className="flex items-center gap-2 mb-1">
                  <div className="w-8 h-8 rounded-lg flex items-center justify-center text-white font-bold text-sm"
                       style={{ background: 'rgba(255,255,255,0.2)' }}>
                    {getIcon(p.id)}
                  </div>
                  <span className={`font-semibold text-sm ${selected === p.id ? 'text-white drop-shadow-sm' : 'text-ink'}`}>
                    {localized(p, 'name')}
                  </span>
                </div>
                <p className={`text-xs leading-relaxed ${selected === p.id ? 'text-white/80 drop-shadow-sm' : 'text-ink-soft/50'}`}>
                  {localized(p, 'desc')}
                </p>
              </div>
            ))}
          </div>

          {/* Provider 详情配置 */}
          {selected && (
            <div className="space-y-4">
              <div>
                <div className="flex items-center justify-between mb-2">
                  <label className="text-xs font-medium text-ink-soft">{t('providerSelector.model')}</label>
                  {(currentProvider.models_url || baseUrl.trim()) && (
                    <button onClick={refreshModels} disabled={refreshing}
                      className="text-[11px] text-jade-500 hover:text-jade-600 transition-colors">
                      {refreshing ? t('providerSelector.refreshing') : t('providerSelector.fetchModels')}
                    </button>
                  )}
                </div>
                {refreshMsg && (
                  <p className={`text-[10px] mb-2 ${refreshError ? 'text-red-400' : 'text-jade-500'}`}>
                    {refreshMsg}
                  </p>
                )}
                {models.length > 0 ? (
                  <div className="space-y-1.5">
                    {models.map(m => (
                      <label key={m.id} className={`flex items-center gap-3 rounded-xl px-4 py-3 cursor-pointer transition-all ${
                        model === m.id ? 'bg-jade-50 ring-1 ring-jade-400/30' : 'bg-white/40 hover:bg-white/60'
                      }`}>
                        <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center ${
                          model === m.id ? 'border-jade-400' : 'border-jade-600/15'
                        }`}>
                          {model === m.id && <div className="w-2 h-2 rounded-full bg-jade-400" />}
                        </div>
                        <input type="radio" value={m.id} checked={model === m.id}
                          onChange={() => setModel(m.id)} className="hidden" />
                        <div>
                          <span className="text-sm font-medium text-ink">{m.name}</span>
                          {m.desc && <span className="text-[10px] text-ink-soft/40 ml-2">{m.desc}</span>}
                        </div>
                      </label>
                    ))}
                  </div>
                ) : (
                  <input value={model} onChange={e => setModel(e.target.value)}
                    placeholder={t('providerSelector.modelPlaceholder')}
                    className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40" />
                )}
              </div>

              <div>
                <label htmlFor="provider-api-key" className="block text-xs font-medium text-ink-soft mb-1.5">API Key</label>
                <input id="provider-api-key" type="password" autoComplete="off" value={apiKey} onChange={e => setApiKey(e.target.value)}
                  placeholder={currentProvider.id === 'anthropic' ? 'sk-ant-...' : 'sk-...'}
                  className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40" />
                {canKeepKey && <p className="text-[11px] text-ink-soft/60 mt-1">{t('providerSelector.keepKey')}</p>}
              </div>

              <div>
                <label className="block text-xs font-medium text-ink-soft mb-1.5">
                  {t('providerSelector.baseUrl')} <span className="text-ink-soft/30">({currentProvider.default_url || t('providerSelector.providerCustom')})</span>
                </label>
                <input value={baseUrl} onChange={e => setBaseUrl(e.target.value)}
                  placeholder={currentProvider.default_url}
                  className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40" />
              </div>

              <div className="flex items-center gap-3 pt-2">
                <button onClick={() => setShowTest(true)} disabled={!apiKey.trim() || !model.trim()}
                  className={`px-5 py-2.5 rounded-full text-sm font-medium bg-indigo-50 text-indigo-600 hover:bg-indigo-100 transition-all ${(!apiKey.trim() || !model.trim()) ? 'opacity-40 pointer-events-none' : ''}`}>
                  {t('providerSelector.testConnection')}
                </button>
                <button onClick={handleSave} disabled={!canSave || saving}
                  className={`btn-jade text-sm flex-1 ${(!canSave || saving) ? 'opacity-40 pointer-events-none' : ''}`}>
                  {saving ? t('providerSelector.saving') : t('providerSelector.save')}
                </button>
              </div>
              {onSkip && <button onClick={onSkip} className="w-full text-sm text-ink-soft/70 py-2">{t('common.skip')}</button>}
            </div>
          )}
        </div>
      </div>

      {showTest && (
        <TestConnectionModal
          provider={selected} apiKey={apiKey}
          baseUrl={baseUrl || currentProvider.default_url || ''}
          model={model} type={filterType === 'image' ? 'image' : 'chat'}
          onClose={() => setShowTest(false)}
          onConnected={async () => { if (await handleSave()) setShowTest(false) }} />
      )}
    </>
  )
}

function getGradient(id) {
  const map = {
    deepseek: 'linear-gradient(160deg, #2563eb 0%, #1e40af 60%, #1e3a5f 100%)',
    openai: 'linear-gradient(135deg, #166534, #22c55e)',
    anthropic: 'linear-gradient(135deg, #92400e, #f59e0b)',
    custom: 'linear-gradient(135deg, #4338ca, #818cf8)',
    openai_image: 'linear-gradient(135deg, #065f46, #34d399)',
    volcengine_image: 'linear-gradient(135deg, #3730a3, #6366f1)',
    custom_image: 'linear-gradient(135deg, #5b21b6, #a78bfa)',
  }
  return map[id] || map.custom
}

function getIcon(id) {
  const map = { deepseek: 'D', openai: 'O', anthropic: 'C', custom: '⚡', openai_image: '🎨', volcengine_image: '🌋', custom_image: '🖼' }
  return map[id] || '?'
}

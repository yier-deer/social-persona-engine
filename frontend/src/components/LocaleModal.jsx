import { useState, useEffect, useRef } from 'react'
import { useLocale } from '../LocaleContext'

const FIRST_VISIT_KEY = 'app_first_visit'

export default function LocaleModal() {
  const { locale, setLocale, t } = useLocale()
  const [visible, setVisible] = useState(false)
  const [exit, setExit] = useState(false)
  const exitTimer = useRef(null)

  useEffect(() => {
    const visited = localStorage.getItem(FIRST_VISIT_KEY)
    const showTimer = !visited ? setTimeout(() => setVisible(true), 300) : null
    return () => {
      clearTimeout(showTimer)
      clearTimeout(exitTimer.current)
    }
  }, [])

  if (!visible) return null

  const handleSelect = (lang) => {
    setLocale(lang)
    localStorage.setItem(FIRST_VISIT_KEY, 'true')
    setExit(true)
    exitTimer.current = setTimeout(() => setVisible(false), 400)
  }

  return (
    <div className={`fixed inset-0 z-[200] flex items-center justify-center transition-all duration-400 ${
      exit ? 'opacity-0' : 'opacity-100'
    }`} style={{ background: 'rgba(26,26,46,0.55)', backdropFilter: 'blur(12px)' }}>
      <div className={`glass-panel rounded-3xl w-full max-w-sm p-8 mx-5 text-center transition-all duration-400 ${
        exit ? 'scale-95 opacity-0' : 'scale-100 opacity-100'
      } animate-fade-up`}>
        <div className="w-14 h-14 mx-auto mb-5 rounded-2xl flex items-center justify-center"
             style={{ background: 'linear-gradient(135deg, #52b788, #2d6a4f)' }}>
          <span className="text-white text-xl font-bold font-serif">A</span>
        </div>

        <h2 className="font-serif text-xl font-semibold text-jade-700 mb-1">
          Language / 语言
        </h2>
        <p className="text-ink-soft text-sm mb-8">
          Select your preferred language. This choice is saved and can be changed later in Settings.
        </p>

        <div className="space-y-3">
          <button
            onClick={() => handleSelect('zh-CN')}
            className="w-full py-3.5 rounded-2xl flex items-center justify-center gap-3 text-sm font-medium transition-all
                       bg-white/60 border border-jade-600/10 hover:border-jade-400/30 hover:bg-white"
          >
            <span className="text-base">🇨🇳</span>
            <span className="text-ink">中文</span>
          </button>
          <button
            onClick={() => handleSelect('en')}
            className="w-full py-3.5 rounded-2xl flex items-center justify-center gap-3 text-sm font-medium transition-all
                       bg-white/60 border border-jade-600/10 hover:border-jade-400/30 hover:bg-white"
          >
            <span className="text-base">🇺🇸</span>
            <span className="text-ink">English</span>
          </button>
        </div>

        <p className="text-[10px] text-ink-soft/30 mt-6">
          You can always change this in Settings.
        </p>
      </div>
    </div>
  )
}

import { useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useLocale } from '../LocaleContext'

// ============================================================
// 常量定义 —— 选项配置与标签映射
// ============================================================

const SOCIAL_RHYTHM_OPTIONS = [
  { value: 'slow_warm', label: 'manualCreate.system.socialRhythmOptions.slow_warm', desc: 'manualCreate.system.socialRhythmOptions.slow_warm_desc' },
  { value: 'fast_excited', label: 'manualCreate.system.socialRhythmOptions.fast_excited', desc: 'manualCreate.system.socialRhythmOptions.fast_excited_desc' },
  { value: 'irregular', label: 'manualCreate.system.socialRhythmOptions.irregular', desc: 'manualCreate.system.socialRhythmOptions.irregular_desc' },
]

const CONFLICT_STYLE_OPTIONS = [
  { value: 'direct_confront', label: 'manualCreate.system.conflictStyleOptions.direct_confront', desc: 'manualCreate.system.conflictStyleOptions.direct_confront_desc' },
  { value: 'cold_shoulder', label: 'manualCreate.system.conflictStyleOptions.cold_shoulder', desc: 'manualCreate.system.conflictStyleOptions.cold_shoulder_desc' },
  { value: 'compromise', label: 'manualCreate.system.conflictStyleOptions.compromise', desc: 'manualCreate.system.conflictStyleOptions.compromise_desc' },
  { value: 'escape', label: 'manualCreate.system.conflictStyleOptions.escape', desc: 'manualCreate.system.conflictStyleOptions.escape_desc' },
]

const INPUT_METHOD_OPTIONS = [
  { value: 'phone_thumb', label: 'manualCreate.system.inputMethodOptions.phone_thumb', desc: 'manualCreate.system.inputMethodOptions.phone_thumb_desc' },
  { value: 'keyboard', label: 'manualCreate.system.inputMethodOptions.keyboard', desc: 'manualCreate.system.inputMethodOptions.keyboard_desc' },
  { value: 'voice', label: 'manualCreate.system.inputMethodOptions.voice', desc: 'manualCreate.system.inputMethodOptions.voice_desc' },
]

const RELATIONSHIP_PHASE_OPTIONS = [
  { value: 'stranger', label: 'manualCreate.system.relationshipPhaseOptions.stranger', desc: 'manualCreate.system.relationshipPhaseOptions.stranger_desc' },
  { value: 'acquaintance', label: 'manualCreate.system.relationshipPhaseOptions.acquaintance', desc: 'manualCreate.system.relationshipPhaseOptions.acquaintance_desc' },
  { value: 'friend', label: 'manualCreate.system.relationshipPhaseOptions.friend', desc: 'manualCreate.system.relationshipPhaseOptions.friend_desc' },
  { value: 'close_friend', label: 'manualCreate.system.relationshipPhaseOptions.close_friend', desc: 'manualCreate.system.relationshipPhaseOptions.close_friend_desc' },
]

const TYPING_STYLE_DEFAULT_OPTIONS = [
  { value: 'fragmented', label: 'manualCreate.system.typingStyleOptions.fragmented', desc: 'manualCreate.system.typingStyleOptions.fragmented_desc' },
  { value: 'composed', label: 'manualCreate.system.typingStyleOptions.composed', desc: 'manualCreate.system.typingStyleOptions.composed_desc' },
  { value: 'mixed', label: 'manualCreate.system.typingStyleOptions.mixed', desc: 'manualCreate.system.typingStyleOptions.mixed_desc' },
]

const STEPS = [
  { key: 'basic', label: 'manualCreate.steps.basic', icon: '👤', desc: 'manualCreate.steps.basicDesc' },
  { key: 'style', label: 'manualCreate.steps.style', icon: '✨', desc: 'manualCreate.steps.styleDesc' },
  { key: 'boundary', label: 'manualCreate.steps.boundary', icon: '🛡️', desc: 'manualCreate.steps.boundaryDesc' },
  { key: 'attachment', label: 'manualCreate.steps.attachment', icon: '💞', desc: 'manualCreate.steps.attachmentDesc' },
  { key: 'system', label: 'manualCreate.steps.system', icon: '⚙️', desc: 'manualCreate.steps.systemDesc' },
  { key: 'archive', label: 'manualCreate.steps.archive', icon: '📖', desc: 'manualCreate.steps.archiveDesc' },
  { key: 'confirm', label: 'manualCreate.steps.confirm', icon: '✓', desc: 'manualCreate.steps.confirmDesc' },
]

// ============================================================
// 辅助函数
// ============================================================

function generateLifeArchive(form) {
  const age = form.age || 20
  const birthYear = new Date().getFullYear() - age
  return JSON.stringify({
    name: form.name || '未命名',
    birth_date: form.birthday || `${birthYear}-01-01`,
    birth_place: '未设定',
    family: '普通家庭',
    childhood: ['童年经历待补充'],
    adolescence: ['青春期经历待补充'],
    young_adult: ['成年经历待补充'],
    future_milestones: [],
    personality_shapers: [`${form.personalityHint || '普通'}的性格特质`],
  })
}

function generateSampleChats(form) {
  return JSON.stringify([
    { scenario: '日常吐槽', user: '今天好累啊', assistant: '（示例占位，创建后由系统生成）' },
    { scenario: '被冷落', user: '...', assistant: '（示例占位，创建后由系统生成）' },
    { scenario: '深度对话', user: '你觉得我这个人怎么样', assistant: '（示例占位，创建后由系统生成）' },
  ])
}

function deriveBigFive(form) {
  const rhythmExtraversion = form.socialRhythm === 'fast_excited' ? 0.75 : form.socialRhythm === 'slow_warm' ? 0.45 : 0.6
  const conflictAgreeableness = form.conflictStyle === 'compromise' ? 0.75 : form.conflictStyle === 'direct_confront' ? 0.35 : 0.5
  const anxietyNeuroticism = form.attachmentAnxiety > 0.5 ? 0.6 : 0.35
  return {
    openness: form.bigFiveOpenness ?? 0.65,
    conscientiousness: form.bigFiveConscientiousness ?? 0.5,
    extraversion: form.bigFiveExtraversion ?? rhythmExtraversion,
    agreeableness: form.bigFiveAgreeableness ?? conflictAgreeableness,
    neuroticism: form.bigFiveNeuroticism ?? anxietyNeuroticism,
  }
}

// ============================================================
// 主组件
// ============================================================

export default function ManualCreate() {
  const navigate = useNavigate()
  const { t, locale } = useLocale()
  const [step, setStep] = useState(0)
  const [saving, setSaving] = useState(false)
  const [created, setCreated] = useState(null)
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [error, setError] = useState('')

  const [form, setForm] = useState({
    // 基础画像
    name: '',
    gender: 'female',
    age: 20,
    birthday: '',
    personalityHint: '',
    relationshipPurpose: '',
    relationshipPhase: 'stranger',
    // 风格锚定
    speechStyle: '',
    speechStyleReference: '',
    speechStyleDetails: '',
    conflictStyleHint: '',
    // 边界探知
    attachmentHint: '',
    conflictDetail: '',
    boundaryTolerance: '',
    // 依恋维度
    attachmentAnxiety: 0.5,
    attachmentAvoidance: 0.3,
    selfEsteemStability: 0.7,
    trustRecoverySpeed: 'medium',
    initiativeTendency: 0.35,
    // 系统细节
    socialRhythm: 'slow_warm',
    conflictStyle: 'direct_confront',
    inputMethod: 'phone_thumb',
    typingStyleDefault: 'fragmented',
    typingStyleOverrides: '',
    typingSpeed: 2.5,
    fragmentationLevel: 0.6,
    imageEnabled: false,
    characterAppearance: '',
    imageStylePrompt: '',
    // 高级：大五人格
    bigFiveOpenness: 0.65,
    bigFiveConscientiousness: 0.5,
    bigFiveExtraversion: 0.55,
    bigFiveAgreeableness: 0.5,
    bigFiveNeuroticism: 0.35,
    // 人生档案
    characterInitialWorldTime: '',
    characterCurrentContext: '',
    lifeStage: '',
    lifeStageDetail: '',
    currentLocation: '',
    lifeArchive: '',
    useAutoLifeArchive: true,
    // 确认
    sampleChats: '',
    useAutoSampleChats: true,
  })

  const update = useCallback((k, v) => setForm(prev => ({ ...prev, [k]: v })), [])

  const canProceed = () => {
    switch (step) {
      case 0: return form.name.trim().length > 0
      case 1: return true
      case 2: return true
      case 3: return true
      case 4: return true
      case 5: return true
      case 6: return true
      default: return true
    }
  }

  const buildAndSave = async () => {
    if (saving) return
    setSaving(true)
    setError('')
    try {
      const bigFive = showAdvanced
        ? {
            openness: form.bigFiveOpenness,
            conscientiousness: form.bigFiveConscientiousness,
            extraversion: form.bigFiveExtraversion,
            agreeableness: form.bigFiveAgreeableness,
            neuroticism: form.bigFiveNeuroticism,
          }
        : deriveBigFive(form)

      const lifeArchiveJson = form.useAutoLifeArchive
        ? generateLifeArchive(form)
        : (form.lifeArchive || generateLifeArchive(form))

      const sampleChatsJson = form.useAutoSampleChats
        ? generateSampleChats(form)
        : (form.sampleChats || '[]')

      const birthYear = form.birthday
        ? parseInt(form.birthday.split('-')[0])
        : new Date().getFullYear() - (form.age || 20)

      const matchmakerRawData = JSON.stringify({
        gender: form.gender || undefined,
        age: form.age || undefined,
        personality_hint: form.personalityHint || undefined,
        relationship_purpose: form.relationshipPurpose || undefined,
        speech_style_reference: form.speechStyleReference || undefined,
        speech_style_details: form.speechStyleDetails || undefined,
        conflict_style_hint: form.conflictStyleHint || undefined,
        attachment_hint: form.attachmentHint || undefined,
        conflict_detail: form.conflictDetail || undefined,
        boundary_tolerance: form.boundaryTolerance || undefined,
        trust_recovery_speed: form.trustRecoverySpeed || undefined,
        ...(locale === 'en' ? { language_hint: 'en' } : {}),
      })

      const config = {
        name: form.name || '未命名',
        bigFiveJson: JSON.stringify(bigFive),
        attachmentAnxiety: form.attachmentAnxiety,
        attachmentAvoidance: form.attachmentAvoidance,
        selfEsteemStability: form.selfEsteemStability,
        socialRhythm: form.socialRhythm,
        conflictStyle: form.conflictStyle,
        initiativeTendency: form.initiativeTendency,
        inputMethod: form.inputMethod,
        typingStyleJson: JSON.stringify({
          default_style: form.typingStyleDefault,
          fragmentation_level: form.fragmentationLevel,
          override_triggers: form.typingStyleOverrides || undefined,
        }),
        typingSpeed: form.typingSpeed,
        imageStylePrompt: form.imageEnabled ? (form.imageStylePrompt || 'anime style, soft lighting') : '',
        characterAppearance: form.imageEnabled ? (form.characterAppearance || '') : '',
        imageEnabled: form.imageEnabled ? 1 : 0,
        sampleChatsJson: sampleChatsJson,
        characterInitialWorldTime: form.characterInitialWorldTime || new Date().toISOString().split('T')[0],
        birthday: form.birthday || `${birthYear}-01-01`,
        characterCurrentContext: form.characterCurrentContext || buildDefaultContext(form),
        lifeStage: form.lifeStage || '',
        lifeStageDetail: form.lifeStageDetail || '',
        currentLocation: form.currentLocation || '',
        relationshipPhase: form.relationshipPhase,
        matchmakerRawData: matchmakerRawData,
        lifeArchive: lifeArchiveJson,
        aiQq: '',
        ownerQq: '',
      }

      const r = await fetch('/api/personas', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config),
      })
      const data = await r.json()
      if (!r.ok || data.status !== 'ok') {
        throw new Error(data.message || `HTTP ${r.status}`)
      }
      if (data.status === 'ok') {
        setCreated({ id: data.id, name: form.name || '新AI' })
      }
    } catch (e) {
      setError(t('manualCreate.createFailed') + e.message)
    } finally {
      setSaving(false)
    }
  }

  function buildDefaultContext(form) {
    const name = form.name || 'AI网友'
    const phase = form.relationshipPhase || 'stranger'
    const age = form.age || 20
    const personality = form.personalityHint || '普通'
    const purpose = form.relationshipPurpose || '日常聊天伙伴'

    const phaseContexts = {
      stranger: `${name}，${age}岁，${personality}。今天在社交软件上突然被一个陌生人加了好友，对方没有任何自我介绍，她感到困惑和警惕。`,
      acquaintance: `${name}，${age}岁，${personality}。和对方算是认识了，但还不是特别熟。偶尔会想起对方但不会主动频繁联系。`,
      friend: `${name}，${age}岁，${personality}。和对方已经是朋友了，平时会分享生活中的趣事，有烦恼时也会互相吐槽。`,
      close_friend: `${name}，${age}岁，${personality}。和对方是密友，几乎无话不谈。她信任对方，不需要伪装自己。`,
    }
    return phaseContexts[phase] || phaseContexts.stranger
  }

  // ==================== 成功页面 ====================
  if (created) {
    return (
      <div className="animate-fade-up max-w-md mx-auto mt-20 text-center">
        <div className="glass-card rounded-3xl p-10">
          <div className="w-16 h-16 mx-auto mb-4 rounded-2xl flex items-center justify-center"
               style={{ background: 'linear-gradient(135deg, #52b788, #2d6a4f)' }}>
            <span className="text-white text-2xl font-serif font-semibold">{created.name[0]}</span>
          </div>
          <h2 className="font-serif text-xl font-semibold text-jade-700 mb-1">{created.name}{t('manualCreate.successTitle')}</h2>
          <p className="text-ink-soft text-sm mb-6">{t('manualCreate.successDesc')}</p>
          <button onClick={() => navigate(`/persona/${created.id}`)}
            className="btn-jade w-full text-sm py-3 mb-3">{t('manualCreate.viewDetail')} {created.name}</button>
          <button onClick={() => navigate('/')}
            className="w-full py-3 rounded-full text-sm font-medium bg-jade-50 text-jade-600 hover:bg-jade-100 transition-all">{t('manualCreate.backToList')}</button>
        </div>
      </div>
    )
  }

  // ==================== 步骤进度条 ====================
  const StepProgress = () => (
    <div className="mb-8">
      <div className="flex items-center justify-between mb-3">
        {STEPS.map((s, i) => (
          <div key={s.key} className="flex flex-col items-center flex-1">
            <button
              onClick={() => i < step && setStep(i)}
              disabled={i > step}
              className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-medium transition-all duration-300 ${
                i === step
                  ? 'bg-jade-500 text-white ring-4 ring-jade-200'
                  : i < step
                    ? 'bg-jade-400 text-white cursor-pointer hover:bg-jade-500'
                    : 'bg-jade-100 text-jade-300'
              }`}
            >
              {i < step ? '✓' : i + 1}
            </button>
            <span className={`text-[10px] mt-1.5 font-medium transition-colors ${
              i === step ? 'text-jade-600' : i < step ? 'text-jade-500' : 'text-jade-200'
            }`}>
              {t(s.label)}
            </span>
          </div>
        ))}
      </div>
      <div className="relative h-1 bg-jade-100 rounded-full overflow-hidden">
        <div
          className="absolute top-0 left-0 h-full bg-jade-400 rounded-full transition-all duration-500"
          style={{ width: `${(step / (STEPS.length - 1)) * 100}%` }}
        />
      </div>
      <p className="text-xs text-ink-soft/50 mt-2 text-center">
        {t('manualCreate.stepLabel')} {step + 1} / {STEPS.length}：{t(STEPS[step].desc)}
      </p>
    </div>
  )

  // ==================== 各步骤内容 ====================
  const renderStepContent = () => {
    switch (step) {
      case 0: return <StepBasic form={form} update={update} t={t} />
      case 1: return <StepStyle form={form} update={update} t={t} />
      case 2: return <StepBoundary form={form} update={update} t={t} />
      case 3: return <StepAttachment form={form} update={update} t={t} />
      case 4: return <StepSystem form={form} update={update} showAdvanced={showAdvanced} setShowAdvanced={setShowAdvanced} t={t} />
      case 5: return <StepArchive form={form} update={update} t={t} />
      case 6: return <StepConfirm form={form} update={update} t={t} />
      default: return null
    }
  }

  return (
    <div className="animate-fade-up max-w-2xl mx-auto">
      <div className="mb-6 pt-4">
        <h1 className="font-serif text-2xl font-semibold text-jade-700 tracking-wide">{t('manualCreate.title')}</h1>
        <p className="text-ink-soft mt-1 text-sm">{t('manualCreate.subtitle')}</p>
      </div>

      <StepProgress />

      {error && <p role="alert" className="text-sm text-red-600 mb-4">{error}</p>}

      <div className="glass-card rounded-2xl p-6">
        {renderStepContent()}

        {/* 底部导航 */}
        <div className="flex items-center justify-between mt-8 pt-5 border-t border-jade-600/8">
          {step > 0 ? (
            <button onClick={() => setStep(step - 1)}
              className="text-sm text-ink-soft hover:text-jade-600 transition-colors">
              ← {t('common.prev')}
            </button>
          ) : <div />}

          {step < STEPS.length - 1 ? (
            <button
              onClick={() => setStep(step + 1)}
              disabled={!canProceed()}
              className="btn-jade text-sm disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {t('common.next')} →
            </button>
          ) : (
            <button
              onClick={buildAndSave}
              disabled={saving || !canProceed()}
              className="btn-jade text-sm disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {saving ? t('manualCreate.creating') : t('manualCreate.createBtn')}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

// ============================================================
// 步骤 0：基础画像
// ============================================================

function StepBasic({ form, update, t }) {
  return (
    <div className="space-y-5">
      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">
          {t('manualCreate.basic.name')} <span className="text-red-400">*</span>
        </label>
        <input
          value={form.name}
          onChange={e => update('name', e.target.value)}
          placeholder={t('manualCreate.basic.nameReq')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 focus:ring-2 focus:ring-jade-400/10 transition-all"
        />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.basic.gender')}</label>
          <select
            value={form.gender}
            onChange={e => update('gender', e.target.value)}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
          >
            <option value="female">{t('manualCreate.basic.genderFemale')}</option>
            <option value="male">{t('manualCreate.basic.genderMale')}</option>
            <option value="nonbinary">{t('manualCreate.basic.genderOther')}</option>
          </select>
        </div>
        <div>
          <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.basic.age')}</label>
          <input
            type="number"
            min={10}
            max={99}
            value={form.age}
            onChange={e => update('age', parseInt(e.target.value) || 20)}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
          />
        </div>
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.basic.birthday')}</label>
        <input
          type="date"
          value={form.birthday}
          onChange={e => update('birthday', e.target.value)}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.basic.personality')}</label>
        <textarea
          value={form.personalityHint}
          onChange={e => update('personalityHint', e.target.value)}
          rows={2}
          placeholder={t('manualCreate.basic.personalityHint')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.basic.relationshipPurpose')}</label>
        <input
          value={form.relationshipPurpose}
          onChange={e => update('relationshipPurpose', e.target.value)}
          placeholder={t('manualCreate.basic.relationshipPurposeHint')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-2">{t('manualCreate.basic.relationshipPhase')}</label>
        <div className="space-y-2">
          {RELATIONSHIP_PHASE_OPTIONS.map(opt => (
            <RadioCard
              key={opt.value}
              checked={form.relationshipPhase === opt.value}
              onChange={() => update('relationshipPhase', opt.value)}
              label={t(opt.label)}
              desc={t(opt.desc)}
            />
          ))}
        </div>
      </div>
    </div>
  )
}

// ============================================================
// 步骤 1：风格锚定
// ============================================================

function StepStyle({ form, update, t }) {
  return (
    <div className="space-y-5">
      <div className="bg-jade-50/50 rounded-xl p-4 mb-4">
        <p className="text-xs text-jade-600 leading-relaxed">
          💡 {t('manualCreate.style.tip')}
        </p>
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.style.speechStyle')}</label>
        <textarea
          value={form.speechStyle}
          onChange={e => update('speechStyle', e.target.value)}
          rows={2}
          placeholder={t('manualCreate.style.speechStyleHint')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.style.reference')}</label>
        <input
          value={form.speechStyleReference}
          onChange={e => update('speechStyleReference', e.target.value)}
          placeholder={t('manualCreate.style.referenceHint')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.style.details')}</label>
        <textarea
          value={form.speechStyleDetails}
          onChange={e => update('speechStyleDetails', e.target.value)}
          rows={2}
          placeholder={t('manualCreate.style.detailsHint')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.style.conflictStyle')}</label>
        <textarea
          value={form.conflictStyleHint}
          onChange={e => update('conflictStyleHint', e.target.value)}
          rows={2}
          placeholder={t('manualCreate.style.conflictStyleHint')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>
    </div>
  )
}

// ============================================================
// 步骤 2：边界探知
// ============================================================

function StepBoundary({ form, update, t }) {
  return (
    <div className="space-y-5">
      <div className="bg-jade-50/50 rounded-xl p-4 mb-4">
        <p className="text-xs text-jade-600 leading-relaxed">
          💡 {t('manualCreate.boundary.tip')}
        </p>
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">
          {t('manualCreate.boundary.attachmentQ')}
        </label>
        <textarea
          value={form.attachmentHint}
          onChange={e => update('attachmentHint', e.target.value)}
          rows={3}
          placeholder={t('manualCreate.boundary.attachmentPlaceholder')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">
          {t('manualCreate.boundary.conflictQ')}
        </label>
        <textarea
          value={form.conflictDetail}
          onChange={e => update('conflictDetail', e.target.value)}
          rows={3}
          placeholder={t('manualCreate.boundary.conflictPlaceholder')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">
          {t('manualCreate.boundary.initiativeQ')}
        </label>
        <textarea
          value={form.boundaryTolerance}
          onChange={e => update('boundaryTolerance', e.target.value)}
          rows={2}
          placeholder={t('manualCreate.boundary.initiativePlaceholder')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>
    </div>
  )
}

// ============================================================
// 步骤 3：依恋维度
// ============================================================

function StepAttachment({ form, update, t }) {
  return (
    <div className="space-y-6">
      <div className="bg-jade-50/50 rounded-xl p-4 mb-4">
        <p className="text-xs text-jade-600 leading-relaxed">
          💡 {t('manualCreate.attachment.tip')}
        </p>
      </div>

      <SliderField
        label={t('manualCreate.attachment.anxiety')}
        value={form.attachmentAnxiety}
        onChange={v => update('attachmentAnxiety', v)}
        hint={t('manualCreate.attachment.anxietyHint')}
      />

      <SliderField
        label={t('manualCreate.attachment.avoidance')}
        value={form.attachmentAvoidance}
        onChange={v => update('attachmentAvoidance', v)}
        hint={t('manualCreate.attachment.avoidanceHint')}
      />

      <SliderField
        label={t('manualCreate.attachment.selfEsteem')}
        value={form.selfEsteemStability}
        onChange={v => update('selfEsteemStability', v)}
        hint={t('manualCreate.attachment.selfEsteemHint')}
      />

      <SliderField
        label={t('manualCreate.attachment.initiative')}
        value={form.initiativeTendency}
        onChange={v => update('initiativeTendency', v)}
        hint={t('manualCreate.attachment.initiativeHint')}
      />

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-2">{t('manualCreate.attachment.trustRecovery')}</label>
        <div className="space-y-2">
          {[
            { value: 'fast', label: t('manualCreate.attachment.trustFast'), desc: t('manualCreate.attachment.trustFastDesc') },
            { value: 'medium', label: t('manualCreate.attachment.trustMedium'), desc: t('manualCreate.attachment.trustMediumDesc') },
            { value: 'slow', label: t('manualCreate.attachment.trustSlow'), desc: t('manualCreate.attachment.trustSlowDesc') },
          ].map(opt => (
            <RadioCard
              key={opt.value}
              checked={form.trustRecoverySpeed === opt.value}
              onChange={() => update('trustRecoverySpeed', opt.value)}
              label={opt.label}
              desc={opt.desc}
            />
          ))}
        </div>
      </div>
    </div>
  )
}

// ============================================================
// 步骤 4：系统细节
// ============================================================

function StepSystem({ form, update, showAdvanced, setShowAdvanced, t }) {
  return (
    <div className="space-y-5">
      <div>
        <label className="block text-xs font-medium text-ink-soft mb-2">{t('manualCreate.system.socialRhythm')}</label>
        <div className="space-y-2">
          {SOCIAL_RHYTHM_OPTIONS.map(s => (
            <RadioCard
              key={s.value}
              checked={form.socialRhythm === s.value}
              onChange={() => update('socialRhythm', s.value)}
              label={t(s.label)}
              desc={t(s.desc)}
            />
          ))}
        </div>
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-2">{t('manualCreate.system.conflictStyle')}</label>
        <div className="space-y-2">
          {CONFLICT_STYLE_OPTIONS.map(s => (
            <RadioCard
              key={s.value}
              checked={form.conflictStyle === s.value}
              onChange={() => update('conflictStyle', s.value)}
              label={t(s.label)}
              desc={t(s.desc)}
            />
          ))}
        </div>
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-2">{t('manualCreate.system.inputMethod')}</label>
        <div className="space-y-2">
          {INPUT_METHOD_OPTIONS.map(s => (
            <RadioCard
              key={s.value}
              checked={form.inputMethod === s.value}
              onChange={() => update('inputMethod', s.value)}
              label={t(s.label)}
              desc={t(s.desc)}
            />
          ))}
        </div>
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-2">{t('manualCreate.system.typingStyle')}</label>
        <div className="space-y-2">
          {TYPING_STYLE_DEFAULT_OPTIONS.map(s => (
            <RadioCard
              key={s.value}
              checked={form.typingStyleDefault === s.value}
              onChange={() => update('typingStyleDefault', s.value)}
              label={t(s.label)}
              desc={t(s.desc)}
            />
          ))}
        </div>
      </div>

      <div>
        <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.system.typingOverrides')}</label>
        <textarea
          value={form.typingStyleOverrides}
          onChange={e => update('typingStyleOverrides', e.target.value)}
          rows={2}
          placeholder={t('manualCreate.system.typingOverridesHint')}
          className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
        />
      </div>

      <SliderField
        label={t('manualCreate.system.typingSpeed')}
        value={form.typingSpeed}
        onChange={v => update('typingSpeed', v)}
        min={0.5}
        max={10}
        step={0.1}
        hint={t('manualCreate.system.typingSpeedHint')}
      />

      <SliderField
        label={t('manualCreate.system.fragmentation')}
        value={form.fragmentationLevel}
        onChange={v => update('fragmentationLevel', v)}
        hint={t('manualCreate.system.fragmentationHint')}
      />

      {/* 图片生成 */}
      <div className="pt-4 border-t border-jade-600/8">
        <ToggleField
          label={t('manualCreate.system.imageToggle')}
          checked={form.imageEnabled}
          onChange={v => update('imageEnabled', v)}
          desc={t('manualCreate.system.imageDesc')}
        />
        {form.imageEnabled && (
          <div className="mt-3 space-y-3">
            <div>
              <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.system.appearance')}</label>
              <textarea
                value={form.characterAppearance || ''}
                onChange={e => update('characterAppearance', e.target.value)}
                rows={2}
                placeholder={t('manualCreate.system.appearanceHint')}
                className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.system.imageStyle')}</label>
              <textarea
                value={form.imageStylePrompt}
                onChange={e => update('imageStylePrompt', e.target.value)}
                rows={2}
                placeholder={t('manualCreate.system.imageStyleHint')}
                className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
              />
            </div>
          </div>
        )}
      </div>

      {/* 高级模式：大五人格 */}
      <div className="pt-4 border-t border-jade-600/8">
        <button
          onClick={() => setShowAdvanced(!showAdvanced)}
          className="flex items-center gap-2 text-xs text-jade-600 hover:text-jade-700 transition-colors"
        >
          <span className={`transform transition-transform ${showAdvanced ? 'rotate-90' : ''}`}>▶</span>
          {showAdvanced ? t('manualCreate.system.advancedCollapse') : t('manualCreate.system.advancedToggle')}
        </button>

        {showAdvanced && (
          <div className="mt-4 space-y-5 animate-fade-up">
            <div className="bg-amber-50/50 rounded-xl p-3">
              <p className="text-[11px] text-amber-700 leading-relaxed">
                ⚠️ {t('manualCreate.system.advancedWarning')}
              </p>
            </div>
            <SliderField label={t('manualCreate.system.bigFiveOpenness')} value={form.bigFiveOpenness} onChange={v => update('bigFiveOpenness', v)}
              hint={t('manualCreate.system.bigFiveOpennessHint')} />
            <SliderField label={t('manualCreate.system.bigFiveConscientiousness')} value={form.bigFiveConscientiousness} onChange={v => update('bigFiveConscientiousness', v)}
              hint={t('manualCreate.system.bigFiveConscientiousnessHint')} />
            <SliderField label={t('manualCreate.system.bigFiveExtraversion')} value={form.bigFiveExtraversion} onChange={v => update('bigFiveExtraversion', v)}
              hint={t('manualCreate.system.bigFiveExtraversionHint')} />
            <SliderField label={t('manualCreate.system.bigFiveAgreeableness')} value={form.bigFiveAgreeableness} onChange={v => update('bigFiveAgreeableness', v)}
              hint={t('manualCreate.system.bigFiveAgreeablenessHint')} />
            <SliderField label={t('manualCreate.system.bigFiveNeuroticism')} value={form.bigFiveNeuroticism} onChange={v => update('bigFiveNeuroticism', v)}
              hint={t('manualCreate.system.bigFiveNeuroticismHint')} />
          </div>
        )}
      </div>
    </div>
  )
}

// ============================================================
// 步骤 5：人生档案
// ============================================================

function StepArchive({ form, update, t }) {
  return (
    <div className="space-y-5">
      <div className="bg-jade-50/50 rounded-xl p-4 mb-4">
        <p className="text-xs text-jade-600 leading-relaxed">
          💡 {t('manualCreate.archive.tip')}
        </p>
      </div>

      <ToggleField
        label={t('manualCreate.archive.autoToggle')}
        checked={form.useAutoLifeArchive}
        onChange={v => update('useAutoLifeArchive', v)}
        desc={t('manualCreate.archive.autoDesc')}
      />

      {!form.useAutoLifeArchive && (
        <div className="animate-fade-up space-y-4">
          <div>
            <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.archive.worldTime')}</label>
            <input
              type="date"
              value={form.characterInitialWorldTime}
              onChange={e => update('characterInitialWorldTime', e.target.value)}
              className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
            />
            <p className="text-[10px] text-ink-soft/40 mt-1">{t('manualCreate.archive.worldTimeHint')}</p>
          </div>

          <div>
            <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.archive.currentContext')}</label>
            <textarea
              value={form.characterCurrentContext}
              onChange={e => update('characterCurrentContext', e.target.value)}
              rows={3}
              placeholder={t('manualCreate.archive.currentContextHint')}
              className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all"
            />
          </div>

          <div>
            <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.archive.archiveJson')}</label>
            <textarea
              value={form.lifeArchive}
              onChange={e => update('lifeArchive', e.target.value)}
              rows={6}
              placeholder={`{\n  "name": "名字",\n  "birth_place": "出生地",\n  "family": "家庭背景",\n  "childhood": ["童年经历1", "童年经历2"],\n  ...\n}`}
              className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none font-mono transition-all"
            />
            <p className="text-[10px] text-ink-soft/40 mt-1">{t('manualCreate.archive.archiveJsonHint')}</p>
          </div>
        </div>
      )}

      {form.useAutoLifeArchive && (
        <div className="bg-jade-50/30 rounded-xl p-4 animate-fade-up">
          <p className="text-xs text-jade-600 font-medium mb-2">{t('manualCreate.archive.autoContent')}</p>
          <ul className="text-[11px] text-ink-soft space-y-1">
            <li>• {t('manualCreate.archive.autoItem1')}</li>
            <li>• {t('manualCreate.archive.autoItem2')}</li>
            <li>• {t('manualCreate.archive.autoItem3')}</li>
          </ul>
          <p className="text-[10px] text-ink-soft/50 mt-2">{t('manualCreate.archive.autoFooter')}</p>
        </div>
      )}

      <div className="pt-4 border-t border-jade-600/8">
        <p className="text-xs font-medium text-ink-soft mb-3">{t('manualCreate.confirm.summaryCards.life')}</p>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.archive.lifeStage')}</label>
            <input
              value={form.lifeStage}
              onChange={e => update('lifeStage', e.target.value)}
              placeholder={t('manualCreate.archive.lifeStageHint')}
              className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.archive.currentLocation')}</label>
            <input
              value={form.currentLocation}
              onChange={e => update('currentLocation', e.target.value)}
              placeholder={t('manualCreate.archive.currentLocationHint')}
              className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
            />
          </div>
        </div>

        <div className="mt-3">
          <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.archive.lifeStageDetail')}</label>
          <input
            value={form.lifeStageDetail}
            onChange={e => update('lifeStageDetail', e.target.value)}
            placeholder={t('manualCreate.archive.lifeStageDetailHint')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all"
          />
        </div>
      </div>
    </div>
  )
}

// ============================================================
// 步骤 6：确认创建
// ============================================================

function StepConfirm({ form, update, t }) {
  const bigFive = deriveBigFive(form)

  return (
    <div className="space-y-5">
      <div className="bg-jade-50/50 rounded-xl p-4 mb-4">
        <p className="text-xs text-jade-600 leading-relaxed">
          💡 {t('manualCreate.confirm.tip')}
        </p>
      </div>

      {/* 配置摘要卡片 */}
      <div className="space-y-3">
        <SummaryCard title={t('manualCreate.confirm.summaryCards.basic')} icon="👤">
          <SummaryItem label={t('manualCreate.basic.name')} value={form.name || t('manualCreate.confirm.summaryNotSet')} />
          <SummaryItem label={t('manualCreate.basic.gender')} value={form.gender === 'female' ? t('manualCreate.basic.genderFemale') : form.gender === 'male' ? t('manualCreate.basic.genderMale') : t('manualCreate.basic.genderOther')} />
          <SummaryItem label={t('manualCreate.basic.age')} value={`${form.age}岁`} />
          <SummaryItem label={t('manualCreate.basic.personality')} value={form.personalityHint || t('manualCreate.confirm.summaryNotSet')} />
          <SummaryItem label={t('manualCreate.basic.relationshipPhase')} value={t((RELATIONSHIP_PHASE_OPTIONS.find(o => o.value === form.relationshipPhase) || RELATIONSHIP_PHASE_OPTIONS[0]).label)} />
        </SummaryCard>

        {(form.lifeStage || form.lifeStageDetail || form.currentLocation) ? (
          <SummaryCard title={t('manualCreate.confirm.summaryCards.life')} icon="📍">
            {form.lifeStage && <SummaryItem label={t('manualCreate.archive.lifeStage')} value={form.lifeStage} />}
            {form.lifeStageDetail && <SummaryItem label={t('manualCreate.archive.lifeStageDetail')} value={form.lifeStageDetail} />}
            {form.currentLocation && <SummaryItem label={t('manualCreate.archive.currentLocation')} value={form.currentLocation} />}
          </SummaryCard>
        ) : null}

        <SummaryCard title={t('manualCreate.confirm.summaryCards.style')} icon="✨">
          <SummaryItem label={t('manualCreate.style.speechStyle')} value={form.speechStyle || t('manualCreate.confirm.summaryNotSet')} />
          {form.speechStyleReference && <SummaryItem label={t('manualCreate.style.reference')} value={form.speechStyleReference} />}
        </SummaryCard>

        <SummaryCard title={t('manualCreate.confirm.summaryCards.attachment')} icon="💞">
          <SummaryItem label={t('manualCreate.attachment.anxiety')} value={form.attachmentAnxiety.toFixed(2)} />
          <SummaryItem label={t('manualCreate.attachment.avoidance')} value={form.attachmentAvoidance.toFixed(2)} />
          <SummaryItem label={t('manualCreate.attachment.selfEsteem')} value={form.selfEsteemStability.toFixed(2)} />
          <SummaryItem label={t('manualCreate.attachment.initiative')} value={form.initiativeTendency.toFixed(2)} />
        </SummaryCard>

        <SummaryCard title={t('manualCreate.confirm.summaryCards.system')} icon="⚙️">
          <SummaryItem label={t('manualCreate.system.socialRhythm')} value={t((SOCIAL_RHYTHM_OPTIONS.find(o => o.value === form.socialRhythm) || SOCIAL_RHYTHM_OPTIONS[0]).label)} />
          <SummaryItem label={t('manualCreate.system.conflictStyle')} value={t((CONFLICT_STYLE_OPTIONS.find(o => o.value === form.conflictStyle) || CONFLICT_STYLE_OPTIONS[0]).label)} />
          <SummaryItem label={t('manualCreate.system.typingSpeed')} value={`${form.typingSpeed}${t('manualCreate.confirm.charsPerSec')}`} />
          <SummaryItem label={t('manualCreate.system.fragmentation')} value={form.fragmentationLevel.toFixed(2)} />
          <SummaryItem label={t('manualCreate.system.imageToggle')} value={form.imageEnabled ? t('manualCreate.confirm.imageOn') : t('manualCreate.confirm.imageOff')} />
        </SummaryCard>

        <SummaryCard title={t('manualCreate.confirm.summaryCards.bigFive')} icon="🧠">
          <SummaryItem label={t('manualCreate.system.bigFiveOpenness')} value={bigFive.openness.toFixed(2)} />
          <SummaryItem label={t('manualCreate.system.bigFiveConscientiousness')} value={bigFive.conscientiousness.toFixed(2)} />
          <SummaryItem label={t('manualCreate.system.bigFiveExtraversion')} value={bigFive.extraversion.toFixed(2)} />
          <SummaryItem label={t('manualCreate.system.bigFiveAgreeableness')} value={bigFive.agreeableness.toFixed(2)} />
          <SummaryItem label={t('manualCreate.system.bigFiveNeuroticism')} value={bigFive.neuroticism.toFixed(2)} />
        </SummaryCard>
      </div>

      {/* 示例对话 */}
      <div className="pt-4 border-t border-jade-600/8">
        <ToggleField
          label={t('manualCreate.confirm.sampleToggle')}
          checked={form.useAutoSampleChats}
          onChange={v => update('useAutoSampleChats', v)}
          desc={t('manualCreate.confirm.sampleDesc')}
        />

        {!form.useAutoSampleChats && (
          <div className="mt-3 animate-fade-up">
            <label className="block text-xs font-medium text-ink-soft mb-1.5">{t('manualCreate.confirm.sampleJson')}</label>
            <textarea
              value={form.sampleChats}
              onChange={e => update('sampleChats', e.target.value)}
              rows={6}
              placeholder='[
  {
    "role": "matchmaker",
    "content": "模拟对话1的内容"
  },
  {
    "role": "matchmaker",
    "content": "模拟对话2的内容"
  }
]'
              className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none font-mono transition-all"
            />
            <p className="text-[10px] text-ink-soft/40 mt-1">{t('manualCreate.confirm.sampleJsonHint')}</p>
          </div>
        )}
      </div>
    </div>
  )
}

// ============================================================
// 通用 UI 组件
// ============================================================

function RadioCard({ checked, onChange, label, desc }) {
  return (
    <label
      className={`flex items-start gap-3 rounded-xl px-4 py-3 cursor-pointer transition-all duration-200 ${
        checked
          ? 'bg-jade-50 ring-1 ring-jade-400/30 shadow-sm'
          : 'bg-white/40 hover:bg-white/60'
      }`}
    >
      <div
        className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 mt-0.5 transition-colors ${
          checked ? 'border-jade-400' : 'border-jade-600/15'
        }`}
      >
        {checked && <div className="w-2 h-2 rounded-full bg-jade-400" />}
      </div>
      <div className="flex-1">
        <span className="text-sm font-medium text-ink">{label}</span>
        {desc && <p className="text-xs text-ink-soft/60 mt-0.5 leading-relaxed">{desc}</p>}
      </div>
      <input type="radio" checked={checked} onChange={onChange} className="hidden" />
    </label>
  )
}

function SliderField({ label, value, onChange, min = 0, max = 1, step = 0.05, hint }) {
  return (
    <div>
      <div className="flex items-center justify-between mb-1.5">
        <label className="text-xs font-medium text-ink-soft">{label}</label>
        <span className="text-sm font-mono text-jade-600 font-medium">{value}</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={e => onChange(parseFloat(e.target.value))}
        className="w-full accent-jade-500 h-1.5 rounded-full appearance-none bg-jade-100 cursor-pointer"
        style={{
          background: `linear-gradient(to right, #52b788 0%, #52b788 ${((value - min) / (max - min)) * 100}%, #d8ebe2 ${((value - min) / (max - min)) * 100}%, #d8ebe2 100%)`,
        }}
      />
      {hint && <p className="text-[10px] text-ink-soft/40 mt-1 leading-relaxed">{hint}</p>}
    </div>
  )
}

function ToggleField({ label, checked, onChange, desc }) {
  return (
    <div className="flex items-center justify-between rounded-xl px-4 py-3 bg-white/40 hover:bg-white/60 transition-colors cursor-pointer"
         onClick={() => onChange(!checked)}>
      <div>
        <span className="text-sm font-medium text-ink">{label}</span>
        {desc && <p className="text-xs text-ink-soft/50 mt-0.5">{desc}</p>}
      </div>
      <div
        className={`w-11 h-6 rounded-full transition-colors relative flex-shrink-0 ${
          checked ? 'bg-jade-400' : 'bg-jade-600/15'
        }`}
      >
        <div
          className={`absolute top-1 w-4 h-4 rounded-full bg-white shadow transition-transform duration-200 ${
            checked ? 'translate-x-6' : 'translate-x-1'
          }`}
        />
      </div>
    </div>
  )
}

function SummaryCard({ title, icon, children }) {
  return (
    <div className="bg-white/40 rounded-xl p-4">
      <div className="flex items-center gap-2 mb-3">
        <span className="text-sm">{icon}</span>
        <h4 className="text-xs font-semibold text-jade-600 uppercase tracking-wider">{title}</h4>
      </div>
      <div className="grid grid-cols-2 gap-x-4 gap-y-1.5">
        {children}
      </div>
    </div>
  )
}

function SummaryItem({ label, value }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[11px] text-ink-soft">{label}</span>
      <span className="text-xs text-ink font-medium truncate max-w-[120px]">{value}</span>
    </div>
  )
}

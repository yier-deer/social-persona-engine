import { useState, useEffect } from 'react'
import { useToast } from './Toast'
import { useLocale } from '../LocaleContext'

// ============================================================
// 选项常量（与 ManualCreate.jsx 保持一致）
// ============================================================

function getSocialRhythmOptions(t) {
  return [
    { value: 'slow_warm', label: t('manualCreate.system.socialRhythmOptions.slow_warm'), desc: t('manualCreate.system.socialRhythmOptions.slow_warm_desc') },
    { value: 'fast_excited', label: t('manualCreate.system.socialRhythmOptions.fast_excited'), desc: t('manualCreate.system.socialRhythmOptions.fast_excited_desc') },
    { value: 'irregular', label: t('manualCreate.system.socialRhythmOptions.irregular'), desc: t('manualCreate.system.socialRhythmOptions.irregular_desc') },
  ]
}

function getConflictStyleOptions(t) {
  return [
    { value: 'direct_confront', label: t('manualCreate.system.conflictStyleOptions.direct_confront'), desc: t('manualCreate.system.conflictStyleOptions.direct_confront_desc') },
    { value: 'cold_shoulder', label: t('manualCreate.system.conflictStyleOptions.cold_shoulder'), desc: t('manualCreate.system.conflictStyleOptions.cold_shoulder_desc') },
    { value: 'compromise', label: t('manualCreate.system.conflictStyleOptions.compromise'), desc: t('manualCreate.system.conflictStyleOptions.compromise_desc') },
    { value: 'escape', label: t('manualCreate.system.conflictStyleOptions.escape'), desc: t('manualCreate.system.conflictStyleOptions.escape_desc') },
  ]
}

function getInputMethodOptions(t) {
  return [
    { value: 'phone_thumb', label: t('manualCreate.system.inputMethodOptions.phone_thumb'), desc: t('manualCreate.system.inputMethodOptions.phone_thumb_desc') },
    { value: 'keyboard', label: t('manualCreate.system.inputMethodOptions.keyboard'), desc: t('manualCreate.system.inputMethodOptions.keyboard_desc') },
    { value: 'voice', label: t('manualCreate.system.inputMethodOptions.voice'), desc: t('manualCreate.system.inputMethodOptions.voice_desc') },
  ]
}

function getRelationshipPhaseOptions(t) {
  return [
    { value: 'stranger', label: t('manualCreate.system.relationshipPhaseOptions.stranger'), desc: t('manualCreate.system.relationshipPhaseOptions.stranger_desc') },
    { value: 'acquaintance', label: t('manualCreate.system.relationshipPhaseOptions.acquaintance'), desc: t('manualCreate.system.relationshipPhaseOptions.acquaintance_desc') },
    { value: 'friend', label: t('manualCreate.system.relationshipPhaseOptions.friend'), desc: t('manualCreate.system.relationshipPhaseOptions.friend_desc') },
    { value: 'close_friend', label: t('manualCreate.system.relationshipPhaseOptions.close_friend'), desc: t('manualCreate.system.relationshipPhaseOptions.close_friend_desc') },
  ]
}

function getTypingStyleOptions(t) {
  return [
    { value: 'fragmented', label: t('manualCreate.system.typingStyleOptions.fragmented'), desc: t('manualCreate.system.typingStyleOptions.fragmented_desc') },
    { value: 'composed', label: t('manualCreate.system.typingStyleOptions.composed'), desc: t('manualCreate.system.typingStyleOptions.composed_desc') },
    { value: 'mixed', label: t('manualCreate.system.typingStyleOptions.mixed'), desc: t('manualCreate.system.typingStyleOptions.mixed_desc') },
  ]
}

function getTrustRecoveryOptions(t) {
  return [
    { value: 'fast', label: t('manualCreate.attachment.trustFast'), desc: t('manualCreate.attachment.trustFastDesc') },
    { value: 'medium', label: t('manualCreate.attachment.trustMedium'), desc: t('manualCreate.attachment.trustMediumDesc') },
    { value: 'slow', label: t('manualCreate.attachment.trustSlow'), desc: t('manualCreate.attachment.trustSlowDesc') },
  ]
}

// ============================================================
// JSON 安全解析
// ============================================================

function parseJsonField(jsonStr, defaultVal) {
  if (!jsonStr) return defaultVal
  try { return JSON.parse(jsonStr) }
  catch { return defaultVal }
}

// ============================================================
// 构建初始表单数据
// ============================================================

function buildInitialForm(persona) {
  const bigFive = parseJsonField(persona.bigFiveJson, {})
  const typingStyle = parseJsonField(persona.typingStyleJson, {})
  const rawData = parseJsonField(persona.matchmakerRawData, {})

  return {
    rawData,
    // 基础信息
    name: persona.name || '',
    birthday: persona.birthday || '',
    characterCurrentContext: persona.characterCurrentContext || '',
    lifeStage: persona.lifeStage || '',
    lifeStageDetail: persona.lifeStageDetail || '',
    currentLocation: persona.currentLocation || '',
    relationshipPhase: persona.relationshipPhase || 'stranger',
    // 风格锚定（matchmakerRawData 中 LLM 输出 snake_case 键名）
    speechStyle: [rawData.speech_style, rawData.speech_style_reference, rawData.speech_style_details].filter(Boolean).join('；') || '',
    speechStyleReference: rawData.speech_style_reference || '',
    speechStyleDetails: rawData.speech_style_details || '',
    conflictStyleHint: rawData.conflict_style_hint || '',
    // 边界探知（matchmakerRawData 中 LLM 输出 snake_case 键名）
    attachmentHint: rawData.attachment_hint || '',
    conflictDetail: rawData.conflict_detail || '',
    boundaryTolerance: rawData.boundary_tolerance || '',
    // 依恋维度
    attachmentAnxiety: persona.attachmentAnxiety ?? 0.5,
    attachmentAvoidance: persona.attachmentAvoidance ?? 0.3,
    selfEsteemStability: persona.selfEsteemStability ?? 0.7,
    trustRecoverySpeed: rawData.trust_recovery_speed || 'medium',
    initiativeTendency: persona.initiativeTendency ?? 0.35,
    // 社交特性
    socialRhythm: persona.socialRhythm || 'slow_warm',
    conflictStyle: persona.conflictStyle || 'direct_confront',
    // 打字与输入
    inputMethod: persona.inputMethod || 'phone_thumb',
    typingStyleDefault: typingStyle.default_style || 'fragmented',
    typingStyleOverrides: typingStyle.override_triggers || '',
    typingSpeed: persona.typingSpeed ?? 2.5,
    fragmentationLevel: typingStyle.fragmentation_level ?? 0.6,
    // 大五人格
    bigFiveOpenness: bigFive.openness ?? 0.65,
    bigFiveConscientiousness: bigFive.conscientiousness ?? 0.5,
    bigFiveExtraversion: bigFive.extraversion ?? 0.55,
    bigFiveAgreeableness: bigFive.agreeableness ?? 0.5,
    bigFiveNeuroticism: bigFive.neuroticism ?? 0.35,
    // 图片生成
    imageEnabled: persona.imageEnabled === 1,
    characterAppearance: persona.characterAppearance || '',
    imageStylePrompt: persona.imageStylePrompt || '',
    // 通道绑定
    aiQq: persona.aiQq || '',
    ownerQq: persona.ownerQq || '',
  }
}

// ============================================================
// 构建保存请求体
// ============================================================

function buildSavePayload(form) {
  // 牵线人中收集的中间字段（LLM 输出 snake_case 键名，保持一致）
  const rawData = {
    ...form.rawData,
    speech_style: form.speechStyle || undefined,
    speech_style_reference: form.speechStyleReference || undefined,
    speech_style_details: form.speechStyleDetails || undefined,
    conflict_style_hint: form.conflictStyleHint || undefined,
    attachment_hint: form.attachmentHint || undefined,
    conflict_detail: form.conflictDetail || undefined,
    boundary_tolerance: form.boundaryTolerance || undefined,
    trust_recovery_speed: form.trustRecoverySpeed || undefined,
  }
  return {
    name: form.name || undefined,
    bigFiveJson: JSON.stringify({
      openness: form.bigFiveOpenness,
      conscientiousness: form.bigFiveConscientiousness,
      extraversion: form.bigFiveExtraversion,
      agreeableness: form.bigFiveAgreeableness,
      neuroticism: form.bigFiveNeuroticism,
    }),
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
    imageStylePrompt: form.imageStylePrompt || undefined,
    characterAppearance: form.characterAppearance || undefined,
    imageEnabled: form.imageEnabled ? 1 : 0,
    characterCurrentContext: form.characterCurrentContext || undefined,
    lifeStage: form.lifeStage || undefined,
    lifeStageDetail: form.lifeStageDetail || undefined,
    currentLocation: form.currentLocation || undefined,
    birthday: form.birthday || undefined,
    relationshipPhase: form.relationshipPhase || undefined,
    aiQq: form.aiQq.trim(),
    ownerQq: form.ownerQq.trim(),
    matchmakerRawData: JSON.stringify(rawData),
  }
}

// ============================================================
// 主组件
// ============================================================

export default function PersonaEditPanel({ persona, onClose, onSaved }) {
  const { t } = useLocale()
  const toast = useToast()
  const [form, setForm] = useState(() => buildInitialForm(persona))
  const [saving, setSaving] = useState(false)
  const [activeTab, setActiveTab] = useState('basic')
  const [showAdvanced, setShowAdvanced] = useState(false)

  /** 当 persona 变化时重新初始化表单（处理面板重开场景） */
  useEffect(() => {
    setForm(buildInitialForm(persona))
    setActiveTab('basic')
    setShowAdvanced(false)
  }, [persona])

  const update = (key, value) => setForm(prev => ({ ...prev, [key]: value }))

  const handleSave = async () => {
    setSaving(true)
    try {
      const payload = buildSavePayload(form)
      const res = await fetch(`/api/personas/${persona.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      const data = await res.json()
      if (data.status === 'ok') {
        toast.success(t('editPanel.saveSuccess'))
        onSaved?.()
        onClose()
      } else {
        toast.error(data.message || t('editPanel.saveFailed'))
      }
    } catch {
      toast.error(t('editPanel.networkError'))
    } finally {
      setSaving(false)
    }
  }

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) onClose()
  }

  const TABS = [
    { key: 'basic', label: t('editPanel.tabBasic') },
    { key: 'style', label: t('editPanel.tabStyle') },
    { key: 'boundary', label: t('editPanel.tabBoundary') },
    { key: 'attachment', label: t('editPanel.tabAttachment') },
    { key: 'system', label: t('editPanel.tabSystem') },
    { key: 'advanced', label: t('editPanel.tabAdvanced') },
  ]

  return (
    <div className="fixed inset-0 z-[100] flex justify-end" onClick={handleBackdropClick}>
      <div className="absolute inset-0" style={{ background: 'rgba(26,26,46,0.3)' }} />
      <div className="relative w-full max-w-md glass-panel rounded-l-3xl overflow-hidden animate-fade-up flex flex-col"
           onClick={e => e.stopPropagation()}
           style={{ maxHeight: '100vh' }}>

        {/* 头部 */}
        <div className="flex items-center justify-between px-6 pt-6 pb-3 sticky top-0 z-10"
             style={{ background: 'linear-gradient(to bottom, #fff 60%, transparent)' }}>
          <h2 className="font-serif text-lg font-semibold text-jade-700">{t('editPanel.title')}</h2>
          <button onClick={onClose}
            className="w-8 h-8 rounded-full flex items-center justify-center hover:bg-jade-50 transition-colors text-ink-soft">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* 标签导航 */}
        <div className="px-6 pb-3 flex gap-1 overflow-x-auto">
          {TABS.map(tab => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`px-3 py-1.5 rounded-full text-xs font-medium whitespace-nowrap transition-all ${
                activeTab === tab.key
                  ? 'bg-jade-100 text-jade-700'
                  : 'text-ink-soft/60 hover:text-ink-soft hover:bg-jade-50/50'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* 表单内容区 */}
        <div className="flex-1 overflow-y-auto px-6 pb-6 space-y-6">
          {activeTab === 'basic' && <TabBasic form={form} update={update} />}
          {activeTab === 'style' && <TabStyle form={form} update={update} />}
          {activeTab === 'boundary' && <TabBoundary form={form} update={update} />}
          {activeTab === 'attachment' && <TabAttachment form={form} update={update} />}
          {activeTab === 'system' && <TabSystem form={form} update={update} showAdvanced={showAdvanced} setShowAdvanced={setShowAdvanced} />}
          {activeTab === 'advanced' && <TabAdvanced form={form} update={update} />}
        </div>

        {/* 底部操作栏 */}
        <div className="sticky bottom-0 px-6 py-4 border-t border-jade-600/8"
             style={{ background: 'linear-gradient(to top, #fff 60%, transparent)' }}>
          <div className="flex gap-3">
            <button onClick={onClose}
              className="flex-1 py-3 rounded-full text-sm font-medium bg-jade-50 text-jade-600 hover:bg-jade-100 transition-all">
              {t('common.cancel')}
            </button>
            <button onClick={handleSave} disabled={saving}
              className="btn-jade flex-1 text-sm py-3">
              {saving ? t('editPanel.saving') : t('common.save')}
            </button>
          </div>
        </div>

      </div>
    </div>
  )
}

// ============================================================
// 标签页：基础信息
// ============================================================

function TabBasic({ form, update }) {
  const { t } = useLocale()
  const phaseOptions = getRelationshipPhaseOptions(t)
  return (
    <div className="space-y-5">
      <Section title={t('editPanel.basicSection')}>
        <Field label={t('editPanel.name')}>
          <input value={form.name} onChange={e => update('name', e.target.value)}
            placeholder={t('editPanel.namePlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 placeholder:text-ink-soft/40 transition-all" />
        </Field>
        <Field label={t('editPanel.birthday')}>
          <input type="date" value={form.birthday} onChange={e => update('birthday', e.target.value)}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all" />
        </Field>
        <Field label={t('editPanel.currentContext')}>
          <textarea value={form.characterCurrentContext} onChange={e => update('characterCurrentContext', e.target.value)}
            rows={3} placeholder={t('editPanel.currentContextPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 placeholder:text-ink-soft/40 transition-all resize-none" />
        </Field>
        <Field label={t('editPanel.lifeStage')}>
          <select value={form.lifeStage} onChange={e => update('lifeStage', e.target.value)}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all">
            <option value="">{t('editPanel.lifeStageAuto')}</option>
            <option value="student">{t('editPanel.lifeStageStudent')}</option>
            <option value="working">{t('editPanel.lifeStageWorking')}</option>
            <option value="traveling">{t('editPanel.lifeStageTraveling')}</option>
            <option value="at_home">{t('editPanel.lifeStageAtHome')}</option>
            <option value="job_hunting">{t('editPanel.lifeStageJobHunting')}</option>
          </select>
        </Field>
        <Field label={t('editPanel.lifeStageDetail')}>
          <input value={form.lifeStageDetail} onChange={e => update('lifeStageDetail', e.target.value)}
            placeholder={t('editPanel.lifeStageDetailPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 placeholder:text-ink-soft/40 transition-all" />
        </Field>
        <Field label={t('editPanel.currentLocation')}>
          <input value={form.currentLocation} onChange={e => update('currentLocation', e.target.value)}
            placeholder={t('editPanel.currentLocationPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 placeholder:text-ink-soft/40 transition-all" />
        </Field>
      </Section>

      <Section title={t('personaDetail.phaseSectionTitle')}>
        <div className="space-y-2">
          {phaseOptions.map(opt => (
            <RadioCard
              key={opt.value}
              checked={form.relationshipPhase === opt.value}
              onChange={() => update('relationshipPhase', opt.value)}
              label={opt.label}
              desc={opt.desc}
            />
          ))}
        </div>
      </Section>

      <Section title={t('editPanel.channelBinding')}>
        <Field label={t('editPanel.aiQq')}>
          <input value={form.aiQq} onChange={e => update('aiQq', e.target.value)}
            placeholder={t('editPanel.aiQqPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 placeholder:text-ink-soft/40 transition-all" />
        </Field>
        <Field label={t('editPanel.ownerQq')}>
          <input value={form.ownerQq} onChange={e => update('ownerQq', e.target.value)}
            placeholder={t('editPanel.ownerQqPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 placeholder:text-ink-soft/40 transition-all" />
        </Field>
      </Section>
    </div>
  )
}

// ============================================================
// 标签页：风格锚定
// ============================================================

function TabStyle({ form, update }) {
  const { t } = useLocale()
  return (
    <div className="space-y-5">
      <Section title={t('editPanel.speechStyleSection')}>
        <Field label={t('editPanel.speechStyle')}>
          <textarea value={form.speechStyle} onChange={e => update('speechStyle', e.target.value)}
            rows={2} placeholder={t('editPanel.speechStylePlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all" />
        </Field>
        <Field label={t('editPanel.speechStyleReference')}>
          <input value={form.speechStyleReference} onChange={e => update('speechStyleReference', e.target.value)}
            placeholder={t('editPanel.speechStyleReferencePlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all" />
        </Field>
        <Field label={t('editPanel.speechStyleDetails')}>
          <textarea value={form.speechStyleDetails} onChange={e => update('speechStyleDetails', e.target.value)}
            rows={2} placeholder={t('editPanel.speechStyleDetailsPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all" />
        </Field>
        <Field label={t('editPanel.conflictStyleHint')}>
          <textarea value={form.conflictStyleHint} onChange={e => update('conflictStyleHint', e.target.value)}
            rows={2} placeholder={t('editPanel.conflictStyleHintPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all" />
        </Field>
      </Section>
    </div>
  )
}

// ============================================================
// 标签页：边界探知
// ============================================================

function TabBoundary({ form, update }) {
  const { t } = useLocale()
  return (
    <div className="space-y-5">
      <div className="bg-jade-50/50 rounded-xl p-4">
        <p className="text-xs text-jade-600 leading-relaxed">
          {t('editPanel.boundaryTip')}
        </p>
      </div>

      <Section title={t('editPanel.boundarySection')}>
        <Field label={t('editPanel.attachmentHintLabel')}>
          <textarea value={form.attachmentHint} onChange={e => update('attachmentHint', e.target.value)}
            rows={3} placeholder={t('editPanel.attachmentHintPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all" />
        </Field>
        <Field label={t('editPanel.conflictDetailLabel')}>
          <textarea value={form.conflictDetail} onChange={e => update('conflictDetail', e.target.value)}
            rows={3} placeholder={t('editPanel.conflictDetailPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all" />
        </Field>
        <Field label={t('editPanel.boundaryToleranceLabel')}>
          <textarea value={form.boundaryTolerance} onChange={e => update('boundaryTolerance', e.target.value)}
            rows={2} placeholder={t('editPanel.boundaryTolerancePlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all" />
        </Field>
      </Section>
    </div>
  )
}

// ============================================================
// 标签页：依恋维度
// ============================================================

function TabAttachment({ form, update }) {
  const { t } = useLocale()
  const trustRecoveryOptions = getTrustRecoveryOptions(t)
  return (
    <div className="space-y-6">
      <Section title={t('editPanel.attachmentSection')}>
        <SliderField label={t('editPanel.anxietyLabel')} value={form.attachmentAnxiety} onChange={v => update('attachmentAnxiety', v)}
          hint={t('editPanel.anxietyHint')} />
        <SliderField label={t('editPanel.avoidanceLabel')} value={form.attachmentAvoidance} onChange={v => update('attachmentAvoidance', v)}
          hint={t('editPanel.avoidanceHint')} />
        <SliderField label={t('editPanel.selfEsteemLabel')} value={form.selfEsteemStability} onChange={v => update('selfEsteemStability', v)}
          hint={t('editPanel.selfEsteemHint')} />
        <SliderField label={t('editPanel.initiativeLabel')} value={form.initiativeTendency} onChange={v => update('initiativeTendency', v)}
          hint={t('editPanel.initiativeHint')} />
      </Section>

      <Section title={t('editPanel.trustRecoverySection')}>
        <div className="space-y-2">
          {trustRecoveryOptions.map(opt => (
            <RadioCard
              key={opt.value}
              checked={form.trustRecoverySpeed === opt.value}
              onChange={() => update('trustRecoverySpeed', opt.value)}
              label={opt.label}
              desc={opt.desc}
            />
          ))}
        </div>
      </Section>
    </div>
  )
}

// ============================================================
// 标签页：系统细节
// ============================================================

function TabSystem({ form, update, showAdvanced, setShowAdvanced }) {
  const { t } = useLocale()
  const socialRhythmOptions = getSocialRhythmOptions(t)
  const conflictStyleOptions = getConflictStyleOptions(t)
  const inputMethodOptions = getInputMethodOptions(t)
  const typingStyleOptions = getTypingStyleOptions(t)
  return (
    <div className="space-y-5">
      <Section title={t('editPanel.socialSection')}>
        <Field label={t('editPanel.replyRhythm')}>
          <div className="space-y-1.5">
            {socialRhythmOptions.map(s => (
              <RadioCard key={s.value} checked={form.socialRhythm === s.value}
                onChange={() => update('socialRhythm', s.value)} label={s.label} desc={s.desc} />
            ))}
          </div>
        </Field>
        <Field label={t('editPanel.conflictStyleLabel')}>
          <div className="space-y-1.5">
            {conflictStyleOptions.map(s => (
              <RadioCard key={s.value} checked={form.conflictStyle === s.value}
                onChange={() => update('conflictStyle', s.value)} label={s.label} desc={s.desc} />
            ))}
          </div>
        </Field>
      </Section>

      <Section title={t('editPanel.typingSection')}>
        <Field label={t('editPanel.inputMethod')}>
          <div className="space-y-1.5">
            {inputMethodOptions.map(s => (
              <RadioCard key={s.value} checked={form.inputMethod === s.value}
                onChange={() => update('inputMethod', s.value)} label={s.label} desc={s.desc} />
            ))}
          </div>
        </Field>
        <Field label={t('editPanel.defaultTypingStyle')}>
          <div className="space-y-1.5">
            {typingStyleOptions.map(s => (
              <RadioCard key={s.value} checked={form.typingStyleDefault === s.value}
                onChange={() => update('typingStyleDefault', s.value)} label={s.label} desc={s.desc} />
            ))}
          </div>
        </Field>
        <Field label={t('editPanel.typingOverrides')}>
          <textarea value={form.typingStyleOverrides} onChange={e => update('typingStyleOverrides', e.target.value)}
            rows={2} placeholder={t('editPanel.typingOverridesPlaceholder')}
            className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 resize-none transition-all" />
        </Field>
        <SliderField label={t('editPanel.typingSpeed')} value={form.typingSpeed} onChange={v => update('typingSpeed', v)}
          min={0.5} max={10} step={0.1} />
        <SliderField label={t('editPanel.fragmentation')} value={form.fragmentationLevel} onChange={v => update('fragmentationLevel', v)}
          hint={t('editPanel.fragmentationHint')} />
      </Section>

      <Section title={t('editPanel.imageSection')}>
        <ToggleField label={t('editPanel.imageToggle')} checked={form.imageEnabled} onChange={v => update('imageEnabled', v)}
          desc={t('editPanel.imageToggleDesc')} />
        {form.imageEnabled && (
          <>
            <Field label={t('editPanel.appearance')}>
              <input value={form.characterAppearance || ''} onChange={e => update('characterAppearance', e.target.value)}
                placeholder={t('editPanel.appearancePlaceholder')}
                className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all" />
            </Field>
            <Field label={t('editPanel.imageStyle')}>
              <input value={form.imageStylePrompt} onChange={e => update('imageStylePrompt', e.target.value)}
                placeholder={t('editPanel.imageStylePlaceholder')}
                className="w-full rounded-xl px-4 py-3 text-sm bg-white/60 border border-jade-600/10 focus:outline-none focus:border-jade-400/40 transition-all" />
            </Field>
          </>
        )}
      </Section>

      {/* 大五人格高级设置 */}
      <div className="pt-4 border-t border-jade-600/8">
        <button
          onClick={() => setShowAdvanced(!showAdvanced)}
          className="flex items-center gap-2 text-xs text-jade-600 hover:text-jade-700 transition-colors"
        >
          <span className={`transform transition-transform ${showAdvanced ? 'rotate-90' : ''}`}>▶</span>
          {showAdvanced ? t('editPanel.advancedCollapse') : t('editPanel.advancedToggle')}
        </button>

        {showAdvanced && (
          <div className="mt-4 space-y-5 animate-fade-up">
            <div className="bg-amber-50/50 rounded-xl p-3">
              <p className="text-[11px] text-amber-700 leading-relaxed">
                {t('editPanel.advancedWarning')}
              </p>
            </div>
            <SliderField label={t('editPanel.openness')} value={form.bigFiveOpenness} onChange={v => update('bigFiveOpenness', v)}
              hint={t('editPanel.opennessHint')} />
            <SliderField label={t('editPanel.conscientiousness')} value={form.bigFiveConscientiousness} onChange={v => update('bigFiveConscientiousness', v)}
              hint={t('editPanel.conscientiousnessHint')} />
            <SliderField label={t('editPanel.extraversion')} value={form.bigFiveExtraversion} onChange={v => update('bigFiveExtraversion', v)}
              hint={t('editPanel.extraversionHint')} />
            <SliderField label={t('editPanel.agreeableness')} value={form.bigFiveAgreeableness} onChange={v => update('bigFiveAgreeableness', v)}
              hint={t('editPanel.agreeablenessHint')} />
            <SliderField label={t('editPanel.neuroticism')} value={form.bigFiveNeuroticism} onChange={v => update('bigFiveNeuroticism', v)}
              hint={t('editPanel.neuroticismHint')} />
          </div>
        )}
      </div>
    </div>
  )
}

// ============================================================
// 标签页：高级设置
// ============================================================

function TabAdvanced({ form, update }) {
  const { t } = useLocale()
  return (
    <div className="space-y-5">
      <div className="bg-amber-50/50 rounded-xl p-4">
        <p className="text-xs text-amber-700 leading-relaxed">
          {t('editPanel.advancedTip')}
        </p>
      </div>

      <Section title={t('editPanel.bigFiveDirect')}>
        <SliderField label={t('editPanel.openness')} value={form.bigFiveOpenness} onChange={v => update('bigFiveOpenness', v)}
          hint={t('editPanel.opennessHint')} />
        <SliderField label={t('editPanel.conscientiousness')} value={form.bigFiveConscientiousness} onChange={v => update('bigFiveConscientiousness', v)}
          hint={t('editPanel.conscientiousnessHint')} />
        <SliderField label={t('editPanel.extraversion')} value={form.bigFiveExtraversion} onChange={v => update('bigFiveExtraversion', v)}
          hint={t('editPanel.extraversionHint')} />
        <SliderField label={t('editPanel.agreeableness')} value={form.bigFiveAgreeableness} onChange={v => update('bigFiveAgreeableness', v)}
          hint={t('editPanel.agreeablenessHint')} />
        <SliderField label={t('editPanel.neuroticism')} value={form.bigFiveNeuroticism} onChange={v => update('bigFiveNeuroticism', v)}
          hint={t('editPanel.neuroticismHint')} />
      </Section>
    </div>
  )
}

// ============================================================
// 通用 UI 组件
// ============================================================

function Section({ title, children }) {
  return (
    <div>
      <h3 className="font-serif text-sm font-semibold text-jade-600 mb-3 pb-2 border-b border-jade-600/8">
        {title}
      </h3>
      <div className="space-y-3.5">
        {children}
      </div>
    </div>
  )
}

function Field({ label, children }) {
  return (
    <div>
      <label className="block text-xs font-medium text-ink-soft mb-1.5">{label}</label>
      {children}
    </div>
  )
}

function SliderField({ label, value, onChange, min = 0, max = 1, step = 0.05, hint }) {
  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <label className="text-xs font-medium text-ink-soft">{label}</label>
        <span className="text-sm font-mono text-jade-600">{value}</span>
      </div>
      <input type="range" min={min} max={max} step={step}
        value={value} onChange={e => onChange(parseFloat(e.target.value))}
        className="w-full accent-jade-500" />
      {hint && <p className="text-[10px] text-ink-soft/40 mt-0.5">{hint}</p>}
    </div>
  )
}

function ToggleField({ label, checked, onChange, desc }) {
  return (
    <label className="flex items-center justify-between rounded-xl px-3 py-2.5 bg-white/40 hover:bg-white/60 transition-colors cursor-pointer">
      <div>
        <span className="text-sm text-ink">{label}</span>
        {desc && <p className="text-xs text-ink-soft/50 mt-0.5">{desc}</p>}
      </div>
      <button onClick={e => { e.preventDefault(); onChange(!checked) }}
        className={`w-10 h-5 rounded-full transition-colors relative flex-shrink-0 ${checked ? 'bg-jade-400' : 'bg-jade-600/15'}`}>
        <span className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-all ${checked ? 'translate-x-[22px]' : 'translate-x-0.5'}`} />
      </button>
    </label>
  )
}

function RadioCard({ checked, onChange, label, desc }) {
  return (
    <label className={`flex items-start gap-3 rounded-xl px-3 py-2.5 cursor-pointer transition-all ${
      checked ? 'bg-jade-50 ring-1 ring-jade-400/30' : 'bg-white/40 hover:bg-white/60'
    }`}>
      <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 mt-0.5 ${
        checked ? 'border-jade-400' : 'border-jade-600/15'
      }`}>
        {checked && <div className="w-2 h-2 rounded-full bg-jade-400" />}
      </div>
      <div>
        <span className="text-sm font-medium text-ink">{label}</span>
        {desc && <p className="text-xs text-ink-soft/60 mt-0.5">{desc}</p>}
      </div>
      <input type="radio" checked={checked} onChange={onChange} className="hidden" />
    </label>
  )
}

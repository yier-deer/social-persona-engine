import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LocaleModal from '../components/LocaleModal'
import ProviderSelector from '../components/ProviderSelector'
import PersonaEditPanel from '../components/PersonaEditPanel'
import PersonaDetail from '../pages/PersonaDetail'
import PersonaList from '../pages/PersonaList'
import CreatePersona from '../pages/CreatePersona'
import HealthIndicator from '../components/HealthIndicator'
import OnboardingWizard from '../components/OnboardingWizard'
import Settings from '../pages/Settings'
import ManualCreate from '../pages/ManualCreate'
import TestConnectionModal from '../components/TestConnectionModal'
import { StrictMode } from 'react'

const { toast, setLocale } = vi.hoisted(() => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
  setLocale: vi.fn(),
}))

vi.mock('../LocaleContext', () => ({
  useLocale: () => ({ locale: 'zh-CN', setLocale, t: key => key }),
}))
vi.mock('../components/Toast', () => ({ useToast: () => toast }))

const providers = [
  { id: 'deepseek', name: 'DeepSeek', type: 'chat', default_url: 'https://api.deepseek.com/v1', models: [{ id: 'deepseek-chat', name: 'DeepSeek Chat' }] },
  { id: 'openai', name: 'OpenAI', type: 'chat', default_url: 'https://api.openai.com/v1', models: [{ id: 'gpt-4o', name: 'GPT-4o' }] },
  { id: 'custom', name: 'Custom', type: 'chat', default_url: '', models: [] },
  { id: 'openai_image', name: 'DALL-E', type: 'image', default_url: 'https://api.openai.com/v1', models: [{ id: 'dall-e-3', name: 'DALL-E 3' }] },
]
const persona = { id: 'audit-persona', name: 'Zoe', status: 'active', aiQq: '111111', ownerQq: '222222', characterCurrentContext: 'An office worker', createdAt: '2026-10-10T10:00:00', matchmakerRawData: JSON.stringify({ gender: 'female', age: 22, language_hint: 'en', personality_hint: 'reserved', speech_style_reference: 'short' }) }
const response = (data, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => data })
const router = (element, path = '/') => render(
  <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
    <Routes><Route path="*" element={element} /><Route path="/persona/:id" element={element} /></Routes>
  </MemoryRouter>
)

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  vi.stubGlobal('fetch', vi.fn(async (url, options) => {
    if (url.startsWith('/api/config/providers')) {
      const type = new URL(url, 'http://localhost').searchParams.get('type')
      return response(providers.filter(p => !type || p.type === type))
    }
    if (url === '/api/personas') return response([persona])
    if (url === '/api/personas/audit-persona') return response(options?.method ? { status: 'ok' } : persona)
    if (url.includes('/today')) return response([])
    if (url.includes('/thought')) return response({})
    if (url.includes('/relationships/')) return response({ trust: 30, closeness: 15, tension: 0 })
    return response({ status: 'ok' })
  }))
})

describe('first visit and provider configuration', () => {
  it('unmounts the language overlay after the exit animation', () => {
    vi.useFakeTimers()
    render(<LocaleModal />)
    act(() => vi.advanceTimersByTime(300))
    fireEvent.click(screen.getByRole('button', { name: /中文/ }))
    act(() => vi.advanceTimersByTime(400))
    expect(screen.queryByText('Language / 语言')).not.toBeInTheDocument()
  })

  it('selects a valid image provider before saving', async () => {
    const onSave = vi.fn()
    render(<ProviderSelector filterType="image" endpoint="/api/config/image-provider" onSave={onSave} />)
    await screen.findByText('DALL-E')
    fireEvent.change(screen.getByPlaceholderText('sk-...'), { target: { value: 'audit-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: 'providerSelector.save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ provider: 'openai_image', model: 'dall-e-3' })))
  })

  it('changes model and URL together when changing an existing provider', async () => {
    const onSave = vi.fn()
    render(<ProviderSelector initialProvider="deepseek" initialModel="deepseek-chat" initialUrl="https://api.deepseek.com/v1" filterType="chat" onSave={onSave} />)
    fireEvent.click(await screen.findByText('OpenAI'))
    fireEvent.change(screen.getByPlaceholderText('sk-...'), { target: { value: 'audit-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: 'providerSelector.save' }))
    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ provider: 'openai', model: 'gpt-4o', baseUrl: 'https://api.openai.com/v1' })))
  })

  it.each(['http', 'network'])('keeps failed provider saves open and usable: %s', async failure => {
    const onSave = vi.fn()
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => {
      if (options?.method === 'POST') {
        return failure === 'http' ? Promise.resolve(response({ message: 'disk unavailable' }, 500)) : Promise.reject(new Error('offline'))
      }
      return originalFetch(url, options)
    }))
    render(<ProviderSelector filterType="chat" onSave={onSave} />)
    await screen.findByText('DeepSeek')
    fireEvent.change(screen.getByPlaceholderText('sk-...'), { target: { value: 'audit-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: 'providerSelector.save' }))
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(onSave).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'providerSelector.save' })).toBeEnabled()
  })

  it('can skip the optional image provider while retaining the QQ setup step', async () => {
    router(<OnboardingWizard onComplete={vi.fn()} />)
    await screen.findByText('DeepSeek')
    fireEvent.change(screen.getByPlaceholderText('sk-...'), { target: { value: 'audit-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: 'providerSelector.save' }))
    await screen.findByText('DALL-E')
    fireEvent.click(screen.getByRole('button', { name: 'common.skip' }))
    expect(await screen.findByPlaceholderText('onboarding.step2_placeholder')).toBeInTheDocument()
  })

  it('does not finish onboarding when saving the owner QQ fails', async () => {
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => url === '/api/config/channels' && options?.method === 'POST'
      ? Promise.resolve(response({ message: 'disk unavailable' }, 500)) : originalFetch(url, options)))
    router(<OnboardingWizard onComplete={vi.fn()} />)
    await screen.findByText('DeepSeek')
    fireEvent.change(screen.getByPlaceholderText('sk-...'), { target: { value: 'audit-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: 'providerSelector.save' }))
    await screen.findByText('DALL-E')
    fireEvent.change(screen.getByPlaceholderText('sk-...'), { target: { value: 'audit-placeholder' } })
    fireEvent.click(screen.getByRole('button', { name: 'providerSelector.save' }))
    const input = await screen.findByPlaceholderText('onboarding.step2_placeholder')
    fireEvent.change(input, { target: { value: '222222' } })
    fireEvent.click(screen.getByRole('button', { name: /common.next/ }))
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(input).toBeInTheDocument()
    expect(localStorage.getItem('hasConfigured')).toBeNull()
  })

  it('allows metadata changes in settings while retaining a saved API key', async () => {
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => url === '/api/config/key' && !options?.method
      ? Promise.resolve(response({ provider: 'deepseek', hasKey: true, model: 'deepseek-chat', baseUrl: 'https://api.deepseek.com/v1' })) : originalFetch(url, options)))
    render(<Settings />)
    fireEvent.click(await screen.findByRole('button', { name: 'settings.providerChange' }))
    await screen.findByText('DeepSeek')
    fireEvent.change(screen.getByDisplayValue('https://api.deepseek.com/v1'), { target: { value: 'http://localhost:8888/v1' } })
    const save = screen.getByRole('button', { name: 'providerSelector.save' })
    expect(save).toBeEnabled()
    fireEvent.click(save)
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    const request = fetch.mock.calls.find(([url, options]) => url === '/api/config/key' && options?.method === 'POST')
    expect(JSON.parse(request[1].body)).toMatchObject({ apiKey: '', baseUrl: 'http://localhost:8888/v1' })
  })
})

describe('persona administration', () => {
  it('preserves raw personality metadata when saving an unrelated field', async () => {
    render(<PersonaEditPanel persona={persona} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    const request = fetch.mock.calls.find(([, options]) => options?.method === 'PUT')
    expect(JSON.parse(JSON.parse(request[1].body).matchmakerRawData)).toMatchObject(JSON.parse(persona.matchmakerRawData))
  })

  it('persists the general speech style text edited in the persona panel', async () => {
    render(<PersonaEditPanel persona={persona} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'editPanel.tabStyle' }))
    fireEvent.change(screen.getByPlaceholderText('editPanel.speechStylePlaceholder'), { target: { value: 'brief and gentle' } })
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    await waitFor(() => expect(toast.success).toHaveBeenCalled())
    const request = fetch.mock.calls.find(([, options]) => options?.method === 'PUT')
    expect(JSON.parse(JSON.parse(request[1].body).matchmakerRawData).speech_style).toBe('brief and gentle')
  })

  it('shows the speech style saved in the raw persona configuration', () => {
    render(<PersonaEditPanel persona={{ ...persona, matchmakerRawData: '{"speech_style":"brief and gentle"}' }} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'editPanel.tabStyle' }))
    expect(screen.getByPlaceholderText('editPanel.speechStylePlaceholder')).toHaveValue('brief and gentle')
  })

  it('shows a manual creation failure and restores the create button', async () => {
    vi.spyOn(window, 'alert').mockImplementation(() => {})
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => url === '/api/personas' && options?.method === 'POST'
      ? Promise.resolve(response({ message: 'disk unavailable' }, 500)) : originalFetch(url, options)))
    router(<ManualCreate />)
    fireEvent.change(screen.getByPlaceholderText('manualCreate.basic.nameReq'), { target: { value: 'Demo Persona' } })
    for (let i = 0; i < 6; i++) fireEvent.click(screen.getByRole('button', { name: /common.next/ }))
    fireEvent.click(screen.getByRole('button', { name: 'manualCreate.createBtn' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('disk unavailable')
    expect(screen.getByRole('button', { name: 'manualCreate.createBtn' })).toBeEnabled()
  })

  it('reports a failed QQ binding without changing the bot identifier', async () => {
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => options?.method === 'POST'
      ? Promise.resolve(response({ message: 'binding unavailable' }, 500)) : originalFetch(url, options)))
    router(<PersonaDetail />, '/persona/audit-persona')
    await screen.findByText('Zoe')
    const input = screen.getByPlaceholderText('personaDetail.qqPlaceholder')
    fireEvent.change(input, { target: { value: '333333' } })
    fireEvent.blur(input)
    await waitFor(() => expect(toast.error).toHaveBeenCalled())
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('shows both the bot and owner QQ identifiers', async () => {
    router(<PersonaDetail />, '/persona/audit-persona')
    await screen.findByText('Zoe')
    expect(screen.getByDisplayValue('111111')).toBeInTheDocument()
    expect(screen.getByDisplayValue('222222')).toBeInTheDocument()
  })

  it('searches by name and fetches new personas when returning to the list', async () => {
    const view = router(<PersonaList />)
    await screen.findByText('Zoe')
    fireEvent.change(screen.getByPlaceholderText('common.search'), { target: { value: 'Zoe' } })
    expect(screen.getByText('Zoe')).toBeInTheDocument()
    view.unmount()
    fetch.mockResolvedValue(response([{ ...persona, id: 'new', name: 'New Persona' }]))
    router(<PersonaList />)
    expect(await screen.findByText('New Persona')).toBeInTheDocument()
  })

  it('sorts ISO creation dates with the newest persona first', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 31000)
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => url === '/api/personas'
      ? Promise.resolve(response([{ ...persona, id: 'older', name: 'Older', createdAt: '2025-01-01T10:00:00' }, { ...persona, id: 'newer', name: 'Newer', createdAt: '2026-10-10T10:00:00' }]))
      : originalFetch(url, options)))
    router(<PersonaList />)
    await screen.findByText('Newer')
    expect(screen.getAllByRole('heading', { level: 3 }).map(e => e.textContent)).toEqual(['Newer', 'Older'])
  })

  it('sorts by actual relationship values returned by the backend', async () => {
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => {
      if (url === '/api/personas') return Promise.resolve(response([{ ...persona, id: 'low', name: 'Low Trust' }, { ...persona, id: 'high', name: 'High Trust' }]))
      if (url.includes('/relationships/')) return Promise.resolve(response({ trust: url.endsWith('/high') ? 90 : 20, closeness: 15 }))
      return originalFetch(url, options)
    }))
    router(<PersonaList />)
    await screen.findByText('High Trust')
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'trust' } })
    expect(screen.getAllByRole('heading', { level: 3 }).map(e => e.textContent)).toEqual(['High Trust', 'Low Trust'])
  })

  it('can refresh relationship state after a QQ conversation', async () => {
    router(<PersonaDetail />, '/persona/audit-persona')
    await screen.findByText('Zoe')
    const originalFetch = fetch
    vi.stubGlobal('fetch', vi.fn((url, options) => url.includes('/relationships/')
      ? Promise.resolve(response({ trust: 82, closeness: 47, tension: 0 })) : originalFetch(url, options)))
    fireEvent.click(screen.getByRole('button', { name: 'common.refresh' }))
    expect(await screen.findByText('82')).toBeInTheDocument()
  })
})

describe('visible readiness and interview errors', () => {
  it('sends one model connection test in React StrictMode', async () => {
    fetch.mockResolvedValue(response({ success: true, response: 'ok', model: 'audit', latency_ms: 1 }))
    render(<StrictMode><TestConnectionModal provider="custom" apiKey="audit-placeholder" baseUrl="http://localhost" model="audit" onClose={vi.fn()} /></StrictMode>)
    await screen.findByText('providerSelector.testSuccessTitle')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('blocks sending interview messages until a session starts successfully', async () => {
    fetch.mockResolvedValue(response({ message: 'startup failed' }, 500))
    router(<CreatePersona />)
    await screen.findByText('createPersona.connectingError')
    const input = screen.getByPlaceholderText('createPersona.placeholder')
    expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: 'common.retry' })).toBeEnabled()
  })

  it('shows identified NapCat connectivity without calling it Redis memory', async () => {
    fetch.mockResolvedValue(response({ javaStatus: 'ok', pythonStatus: { status: 'degraded', memory_connected: false }, services: { pythonCore: { status: 'connected' }, redis: { status: 'disconnected', optional: true }, napcat: { status: 'connected', botIds: ['111111'] } } }))
    render(<HealthIndicator />)
    fireEvent.click(screen.getByRole('button'))
    expect(await screen.findByText('NapCat')).toBeInTheDocument()
    expect(screen.getByText('111111')).toBeInTheDocument()
  })
})

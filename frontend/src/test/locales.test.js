import { describe, expect, it } from 'vitest'
import en from '../locales/en.json'
import zh from '../locales/zh-CN.json'

describe('demo status copy', () => {
  it.each([['zh-CN', zh], ['en', en]])('has the setup and failure messages in %s', (_, locale) => {
    expect(locale.personaDetail.qqInvalid).toBeTruthy()
    expect(locale.personaDetail.qqBindingHint).toBeTruthy()
    expect(locale.personaDetail.qqIncomplete).toBeTruthy()
    expect(locale.manualCreate.createFailed).toBeTruthy()
    expect(locale.providerSelector.loadFailed).toBeTruthy()
    expect(locale.health.llmHint).toBeTruthy()
  })
})

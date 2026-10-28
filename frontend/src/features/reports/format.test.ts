// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { describe, expect, it } from 'vitest'
import { t } from '../../shared/i18n'
import { formatDuration } from './format'

describe('a duration as the supervisor reads it', () => {
  const en = t('en')

  it('keeps the two largest units', () => {
    expect(formatDuration(en, 5 * 86_400 + 4 * 3600 + 59)).toBe('5 d 4 h')
    expect(formatDuration(en, 432_000)).toBe('5 d')
    expect(formatDuration(en, 3 * 3600 + 12 * 60)).toBe('3 h 12 min')
    expect(formatDuration(en, 7200)).toBe('2 h')
    expect(formatDuration(en, 8 * 60 + 30)).toBe('8 min')
  })

  it('says less than a minute for a stay of seconds, and never shows a negative time', () => {
    expect(formatDuration(en, 12)).toBe('< 1 min')
    expect(formatDuration(en, -5)).toBe('< 1 min')
    expect(formatDuration(t('fr'), 86_400)).toBe('1 j')
  })
})

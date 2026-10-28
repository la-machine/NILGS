// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { t } from '../../shared/i18n'
import { HistoryCard } from './CaseRecord'
import { TrackingCard } from './TrackingCard'

describe('where an application stands, for its applicant', () => {
  afterEach(cleanup)

  it('says the state, since when, what it means and whose turn it is', () => {
    render(<TrackingCard texts={t('en')} lang="en" now={new Date('2026-10-27T12:00:00Z')}
      application={{ state: 'INFO_REQUESTED', since: '2026-10-24T10:00:00Z', waitingOn: 'APPLICANT' }} />)
    expect(screen.getByText('Information requested')).toBeTruthy()
    expect(screen.getByText('for 3 day(s)')).toBeTruthy()
    expect(screen.getByText('Information or documents are needed from you: see the request below.')).toBeTruthy()
    expect(screen.getByText('It is your turn to act.')).toBeTruthy()
  })

  it('tells a closed application from one the office is handling', () => {
    render(<TrackingCard texts={t('en')} lang="en" now={new Date('2026-10-24T12:00:00Z')}
      application={{ state: 'UNDER_REVIEW', since: '2026-10-24T10:00:00Z', waitingOn: 'OFFICE' }} />)
    expect(screen.getByText('since today')).toBeTruthy()
    expect(screen.getByText('The office is handling it: nothing to do on your side for now.')).toBeTruthy()
    cleanup()
    render(<TrackingCard texts={t('en')} lang="en" application={{ state: 'APPROVED', since: '2026-10-24T10:00:00Z', waitingOn: null }} />)
    expect(screen.getByText('This application is closed.')).toBeTruthy()
  })

  it('names the party in the applicant\'s history, a person in the staff\'s', () => {
    render(<HistoryCard texts={t('en')} lang="en" history={[
      { from: 'DRAFT', to: 'SUBMITTED', by: 'APPLICANT', at: '2026-10-24T09:00:00Z' },
      { from: 'COMPLETENESS_REVIEW', to: 'UNDER_REVIEW', by: 'OFFICE', at: '2026-10-24T10:00:00Z' },
    ]} />)
    expect(screen.getByText(/^You ·/)).toBeTruthy()
    expect(screen.getByText(/^The office ·/)).toBeTruthy()
    cleanup()
    render(<HistoryCard texts={t('en')} lang="en" history={[
      { from: 'COMPLETENESS_REVIEW', to: 'UNDER_REVIEW', actorName: 'Ines Intake', reason: null, at: '2026-10-24T10:00:00Z' },
    ]} />)
    expect(screen.getByText(/^Ines Intake ·/)).toBeTruthy()
  })
})
